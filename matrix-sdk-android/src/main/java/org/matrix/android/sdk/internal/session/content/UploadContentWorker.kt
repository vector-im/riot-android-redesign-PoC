/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.crypto.model.rest.EncryptedFileInfo
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.session.DefaultFileService
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.send.MultipleEventSendingDispatcherWorker
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import timber.log.Timber
import java.io.File
import javax.inject.Inject

private data class NewAttachmentAttributes(
        val newWidth: Int? = null,
        val newHeight: Int? = null,
        val newFileSize: Long
)

/**
 * Possible previous worker: None
 * Possible next worker    : Always [MultipleEventSendingDispatcherWorker]
 */
internal class UploadContentWorker(val context: Context, params: WorkerParameters)
    : SessionSafeCoroutineWorker<UploadContentWorker.Params>(context, params, Params::class.java) {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            override val sessionId: String,
            val localEchoIds: List<LocalEchoIdentifiers>,
            val attachment: ContentAttachmentData,
            val isEncrypted: Boolean,
            val compressBeforeSending: Boolean,
            override val lastFailureMessage: String? = null
    ) : SessionWorkerParams

    @Inject lateinit var fileUploader: FileUploader
    @Inject lateinit var contentUploadStateTracker: DefaultContentUploadStateTracker
    @Inject lateinit var fileService: DefaultFileService
    @Inject lateinit var cancelSendTracker: CancelSendTracker
    @Inject lateinit var imageCompressor: ImageCompressor
    @Inject lateinit var videoCompressor: VideoCompressor
    @Inject lateinit var thumbnailExtractor: ThumbnailExtractor
    @Inject lateinit var localEchoRepository: LocalEchoRepository
    @Inject lateinit var temporaryFileCreator: TemporaryFileCreator

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: Params): Result {
        Timber.v("Starting upload media work with params $params")
        // Just defensive code to ensure that we never have an uncaught exception that could break the queue
        return try {
            internalDoWork(params)
        } catch (failure: Throwable) {
            Timber.e(failure)
            handleFailure(params, failure)
        }
    }

    override fun buildErrorParams(params: Params, message: String): Params {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    private suspend fun internalDoWork(params: Params): Result {
        val allCancelled = params.localEchoIds.all { cancelSendTracker.isCancelRequestedFor(it.eventId, it.roomId) }
        if (allCancelled) {
            // there is no point in uploading the image!
            return Result.success(inputData)
                    .also { Timber.e("## Send: Work cancelled by user") }
        }

        val attachment = params.attachment
        val filesToDelete = mutableListOf<File>()

        return try {
            val inputStream = context.contentResolver.openInputStream(attachment.queryUri)
                    ?: return Result.success(
                            WorkerParamsFactory.toData(
                                    params.copy(
                                            lastFailureMessage = "Cannot openInputStream for file: " + attachment.queryUri.toString()
                                    )
                            )
                    )

            // always use a temporary file, it guaranties that we could report progress on upload and simplifies the flows
            val workingFile = temporaryFileCreator.create()
                    .also { filesToDelete.add(it) }
            workingFile.outputStream().use { outputStream ->
                inputStream.use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val progressListener = object : ProgressRequestBody.Listener {
                override fun onProgress(current: Long, total: Long) {
                    notifyTracker(params) {
                        if (isStopped) {
                            contentUploadStateTracker.setFailure(it, Throwable("Cancelled"))
                        } else {
                            contentUploadStateTracker.setProgress(it, current, total)
                        }
                    }
                }
            }

            var uploadedFileEncryptedFileInfo: EncryptedFileInfo? = null

            try {
                val fileToUpload: File
                var newAttachmentAttributes = NewAttachmentAttributes(
                        params.attachment.width?.toInt(),
                        params.attachment.height?.toInt(),
                        params.attachment.size
                )

                if (attachment.type == ContentAttachmentData.Type.IMAGE
                        // Do not compress gif
                        && attachment.mimeType != MimeTypes.Gif
                        && params.compressBeforeSending) {
                    notifyTracker(params) { contentUploadStateTracker.setCompressingImage(it) }

                    fileToUpload = imageCompressor.compress(workingFile, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)
                            .also { compressedFile ->
                                // Get new Bitmap size
                                compressedFile.inputStream().use {
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeStream(it, null, options)
                                    newAttachmentAttributes = NewAttachmentAttributes(
                                            newWidth = options.outWidth,
                                            newHeight = options.outHeight,
                                            newFileSize = compressedFile.length()
                                    )
                                }
                            }
                            .also { filesToDelete.add(it) }
                } else if (attachment.type == ContentAttachmentData.Type.VIDEO
                        // Do not compress gif
                        && attachment.mimeType != MimeTypes.Gif
                        && params.compressBeforeSending) {
                    fileToUpload = videoCompressor.compress(workingFile, object : ProgressListener {
                        override fun onProgress(progress: Int, total: Int) {
                            notifyTracker(params) { contentUploadStateTracker.setCompressingVideo(it, progress.toFloat()) }
                        }
                    })
                            .let { videoCompressionResult ->
                                when (videoCompressionResult) {
                                    is VideoCompressionResult.Success           -> {
                                        val compressedFile = videoCompressionResult.compressedFile
                                        var compressedWidth: Int? = null
                                        var compressedHeight: Int? = null

                                        tryOrNull {
                                            context.contentResolver.openFileDescriptor(compressedFile.toUri(), "r")?.use { pfd ->
                                                MediaMetadataRetriever().let {
                                                    it.setDataSource(pfd.fileDescriptor)
                                                    compressedWidth = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                                                    compressedHeight = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                                                }
                                            }
                                        }

                                        // Get new Video file size and dimensions
                                        newAttachmentAttributes = newAttachmentAttributes.copy(
                                                newFileSize = compressedFile.length(),
                                                newWidth = compressedWidth ?: newAttachmentAttributes.newWidth,
                                                newHeight = compressedHeight ?: newAttachmentAttributes.newHeight
                                        )
                                        compressedFile
                                                .also { filesToDelete.add(it) }
                                    }
                                    VideoCompressionResult.CompressionNotNeeded,
                                    VideoCompressionResult.CompressionCancelled,
                                    is VideoCompressionResult.CompressionFailed -> {
                                        workingFile
                                    }
                                }
                            }
                } else {
                    fileToUpload = workingFile
                    // Fix: OpenableColumns.SIZE may return -1 or 0
                    if (params.attachment.size <= 0) {
                        newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = fileToUpload.length())
                    }
                }

                val encryptedFile: File?
                val contentUploadResponse = if (params.isEncrypted) {
                    Timber.v("## Encrypt file")
                    encryptedFile = temporaryFileCreator.create()
                            .also { filesToDelete.add(it) }

                    uploadedFileEncryptedFileInfo =
                            MXEncryptedAttachments.encrypt(fileToUpload.inputStream(), attachment.getSafeMimeType(), encryptedFile) { read, total ->
                                notifyTracker(params) {
                                    contentUploadStateTracker.setEncrypting(it, read.toLong(), total.toLong())
                                }
                            }
                    Timber.v("## Uploading file")
                    fileUploader.uploadFile(
                            file = encryptedFile,
                            filename = null,
                            mimeType = MimeTypes.OctetStream,
                            progressListener = progressListener
                    )
                } else {
                    Timber.v("## Uploading clear file")
                    encryptedFile = null
                    fileUploader.uploadFile(
                            file = fileToUpload,
                            filename = attachment.name,
                            mimeType = attachment.getSafeMimeType(),
                            progressListener = progressListener
                    )
                }

                Timber.v("## Update cache storage for ${contentUploadResponse.contentUri}")
                try {
                    fileService.storeDataFor(
                            mxcUrl = contentUploadResponse.contentUri,
                            filename = params.attachment.name,
                            mimeType = params.attachment.getSafeMimeType(),
                            originalFile = workingFile,
                            encryptedFile = encryptedFile
                    )
                    Timber.v("## cache storage updated")
                } catch (failure: Throwable) {
                    Timber.e(failure, "## Failed to update file cache")
                }

                val uploadThumbnailResult = dealWithThumbnail(params)

                handleSuccess(params,
                        contentUploadResponse.contentUri,
                        uploadedFileEncryptedFileInfo,
                        uploadThumbnailResult?.uploadedThumbnailUrl,
                        uploadThumbnailResult?.uploadedThumbnailEncryptedFileInfo,
                        newAttachmentAttributes)
            } catch (t: Throwable) {
                Timber.e(t, "## ERROR ${t.localizedMessage}")
                handleFailure(params, t)
            }
        } catch (e: Exception) {
            Timber.e(e, "## ERROR")
            handleFailure(params, e)
        } finally {
            // Delete all temporary files
            filesToDelete.forEach {
                tryOrNull { it.delete() }
            }
        }
    }

    private data class UploadThumbnailResult(
            val uploadedThumbnailUrl: String,
            val uploadedThumbnailEncryptedFileInfo: EncryptedFileInfo?
    )

    /**
     * If appropriate, it will create and upload a thumbnail
     */
    private suspend fun dealWithThumbnail(params: Params): UploadThumbnailResult? {
        return thumbnailExtractor.extractThumbnail(params.attachment)
                ?.let { thumbnailData ->
                    val thumbnailProgressListener = object : ProgressRequestBody.Listener {
                        override fun onProgress(current: Long, total: Long) {
                            notifyTracker(params) { contentUploadStateTracker.setProgressThumbnail(it, current, total) }
                        }
                    }

                    try {
                        if (params.isEncrypted) {
                            Timber.v("Encrypt thumbnail")
                            notifyTracker(params) { contentUploadStateTracker.setEncryptingThumbnail(it) }
                            val encryptionResult = MXEncryptedAttachments.encryptAttachment(thumbnailData.bytes.inputStream(), thumbnailData.mimeType)
                            val contentUploadResponse = fileUploader.uploadByteArray(
                                    byteArray = encryptionResult.encryptedByteArray,
                                    filename = null,
                                    mimeType = MimeTypes.OctetStream,
                                    progressListener = thumbnailProgressListener
                            )
                            UploadThumbnailResult(
                                    contentUploadResponse.contentUri,
                                    encryptionResult.encryptedFileInfo
                            )
                        } else {
                            val contentUploadResponse = fileUploader.uploadByteArray(
                                    byteArray = thumbnailData.bytes,
                                    filename = "thumb_${params.attachment.name}",
                                    mimeType = thumbnailData.mimeType,
                                    progressListener = thumbnailProgressListener
                            )
                            UploadThumbnailResult(
                                    contentUploadResponse.contentUri,
                                    null
                            )
                        }
                    } catch (t: Throwable) {
                        Timber.e(t, "Thumbnail upload failed")
                        null
                    }
                }
    }

    private fun handleFailure(params: Params, failure: Throwable): Result {
        notifyTracker(params) { contentUploadStateTracker.setFailure(it, failure) }

        return Result.success(
                WorkerParamsFactory.toData(
                        params.copy(
                                lastFailureMessage = failure.toMatrixErrorStr()
                        )
                )
        )
    }

    private suspend fun handleSuccess(params: Params,
                                      attachmentUrl: String,
                                      encryptedFileInfo: EncryptedFileInfo?,
                                      thumbnailUrl: String?,
                                      thumbnailEncryptedFileInfo: EncryptedFileInfo?,
                                      newAttachmentAttributes: NewAttachmentAttributes): Result {
        notifyTracker(params) { contentUploadStateTracker.setSuccess(it) }
        params.localEchoIds.forEach {
            updateEvent(it.eventId, attachmentUrl, encryptedFileInfo, thumbnailUrl, thumbnailEncryptedFileInfo, newAttachmentAttributes)
        }

        val sendParams = MultipleEventSendingDispatcherWorker.Params(
                sessionId = params.sessionId,
                localEchoIds = params.localEchoIds,
                isEncrypted = params.isEncrypted
        )
        return Result.success(WorkerParamsFactory.toData(sendParams)).also {
            Timber.v("## handleSuccess $attachmentUrl, work is stopped $isStopped")
        }
    }

    private suspend fun updateEvent(eventId: String,
                                    url: String,
                                    encryptedFileInfo: EncryptedFileInfo?,
                                    thumbnailUrl: String? = null,
                                    thumbnailEncryptedFileInfo: EncryptedFileInfo?,
                                    newAttachmentAttributes: NewAttachmentAttributes) {
        localEchoRepository.updateEcho(eventId) { _, event ->
            val messageContent: MessageContent? = event.asDomain().content.toModel()
            val updatedContent = when (messageContent) {
                is MessageImageContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes)
                is MessageVideoContent -> messageContent.update(url, encryptedFileInfo, thumbnailUrl, thumbnailEncryptedFileInfo, newAttachmentAttributes)
                is MessageFileContent  -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
                is MessageAudioContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
                else                   -> messageContent
            }
            event.content = ContentMapper.map(updatedContent.toContent())
        }
    }

    private fun notifyTracker(params: Params, function: (String) -> Unit) {
        params.localEchoIds.forEach { function.invoke(it.eventId) }
    }

    private fun MessageImageContent.update(url: String,
                                           encryptedFileInfo: EncryptedFileInfo?,
                                           newAttachmentAttributes: NewAttachmentAttributes?): MessageImageContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                info = info?.copy(
                        width = newAttachmentAttributes?.newWidth ?: info.width,
                        height = newAttachmentAttributes?.newHeight ?: info.height,
                        size = newAttachmentAttributes?.newFileSize ?: info.size
                )
        )
    }

    private fun MessageVideoContent.update(url: String,
                                           encryptedFileInfo: EncryptedFileInfo?,
                                           thumbnailUrl: String?,
                                           thumbnailEncryptedFileInfo: EncryptedFileInfo?,
                                           newAttachmentAttributes: NewAttachmentAttributes?): MessageVideoContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                videoInfo = videoInfo?.copy(
                        thumbnailUrl = if (thumbnailEncryptedFileInfo == null) thumbnailUrl else null,
                        thumbnailFile = thumbnailEncryptedFileInfo?.copy(url = thumbnailUrl),
                        width = newAttachmentAttributes?.newWidth ?: videoInfo.width,
                        height = newAttachmentAttributes?.newHeight ?: videoInfo.height,
                        size = newAttachmentAttributes?.newFileSize ?: videoInfo.size
                )
        )
    }

    private fun MessageFileContent.update(url: String,
                                          encryptedFileInfo: EncryptedFileInfo?,
                                          size: Long): MessageFileContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                info = info?.copy(size = size)
        )
    }

    private fun MessageAudioContent.update(url: String,
                                           encryptedFileInfo: EncryptedFileInfo?,
                                           size: Long): MessageAudioContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                audioInfo = audioInfo?.copy(size = size)
        )
    }

    companion object {
        private const val MAX_IMAGE_SIZE = 640
    }
}

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
import android.net.Uri
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.internal.di.Authenticated
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.network.awaitResponse
import org.matrix.android.sdk.internal.network.toFailure
import org.matrix.android.sdk.internal.session.homeserver.DefaultHomeServerCapabilitiesService
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

internal class FileUploader @Inject constructor(
        @Authenticated private val okHttpClient: OkHttpClient,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val homeServerCapabilitiesService: DefaultHomeServerCapabilitiesService,
        private val context: Context,
        private val temporaryFileCreator: TemporaryFileCreator,
        contentUrlResolver: ContentUrlResolver,
        moshi: Moshi
) {

    private val uploadUrl = contentUrlResolver.uploadUrl
    private val responseAdapter = moshi.adapter(ContentUploadResponse::class.java)

    suspend fun uploadFile(file: File,
                           filename: String?,
                           mimeType: String?,
                           progressListener: ProgressRequestBody.Listener? = null): ContentUploadResponse {
        // Check size limit
        val maxUploadFileSize = homeServerCapabilitiesService.getHomeServerCapabilities().maxUploadFileSize

        if (maxUploadFileSize != HomeServerCapabilities.MAX_UPLOAD_FILE_SIZE_UNKNOWN
                && file.length() > maxUploadFileSize) {
            // Known limitation and file too big for the server, save the pain to upload it
            throw Failure.ServerError(
                    error = MatrixError(
                            code = MatrixError.M_TOO_LARGE,
                            message = "Cannot upload files larger than ${maxUploadFileSize / 1048576L}mb"
                    ),
                    httpCode = 413
            )
        }

        val uploadBody = object : RequestBody() {
            override fun contentLength() = file.length()

            // Disable okhttp auto resend for 'large files'
            override fun isOneShot() = contentLength() == 0L || contentLength() >= 1_000_000

            override fun contentType(): MediaType? {
                return mimeType?.toMediaTypeOrNull()
            }

            override fun writeTo(sink: BufferedSink) {
                file.source().use { sink.writeAll(it) }
            }
        }

        return upload(uploadBody, filename, progressListener)
    }

    suspend fun uploadByteArray(byteArray: ByteArray,
                                filename: String?,
                                mimeType: String?,
                                progressListener: ProgressRequestBody.Listener? = null): ContentUploadResponse {
        val uploadBody = byteArray.toRequestBody(mimeType?.toMediaTypeOrNull())
        return upload(uploadBody, filename, progressListener)
    }

    suspend fun uploadFromUri(uri: Uri,
                              filename: String?,
                              mimeType: String?,
                              progressListener: ProgressRequestBody.Listener? = null): ContentUploadResponse {
        val inputStream = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)
        } ?: throw FileNotFoundException()
        val workingFile = temporaryFileCreator.create()
        workingFile.outputStream().use {
            inputStream.copyTo(it)
        }
        return uploadFile(workingFile, filename, mimeType, progressListener).also {
            tryOrNull { workingFile.delete() }
        }
    }

    private suspend fun upload(uploadBody: RequestBody,
                               filename: String?,
                               progressListener: ProgressRequestBody.Listener?): ContentUploadResponse {
        val urlBuilder = uploadUrl.toHttpUrlOrNull()?.newBuilder() ?: throw RuntimeException()

        val httpUrl = urlBuilder
                .apply {
                    if (filename != null) {
                        addQueryParameter("filename", filename)
                    }
                }
                .build()

        val requestBody = if (progressListener != null) ProgressRequestBody(uploadBody, progressListener) else uploadBody

        val request = Request.Builder()
                .url(httpUrl)
                .post(requestBody)
                .build()

        return okHttpClient.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw response.toFailure(globalErrorReceiver)
            } else {
                response.body?.source()?.let {
                    responseAdapter.fromJson(it)
                }
                        ?: throw IOException()
            }
        }
    }
}

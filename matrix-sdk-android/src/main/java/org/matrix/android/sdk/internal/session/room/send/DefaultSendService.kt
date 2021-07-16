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

package org.matrix.android.sdk.internal.session.room.send

import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.OptionItem
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.send.SendService
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.CancelableBag
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.NoOpCancellable
import org.matrix.android.sdk.internal.crypto.CryptoSessionInfoProvider
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.WorkManagerProvider
import org.matrix.android.sdk.internal.session.content.UploadContentWorker
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.CancelableWork
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import org.matrix.android.sdk.internal.worker.startChain
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val UPLOAD_WORK = "UPLOAD_WORK"

internal class DefaultSendService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val workManagerProvider: WorkManagerProvider,
        @SessionId private val sessionId: String,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
        private val taskExecutor: TaskExecutor,
        private val localEchoRepository: LocalEchoRepository,
        private val eventSenderProcessor: EventSenderProcessor,
        private val cancelSendTracker: CancelSendTracker
) : SendService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultSendService
    }

    private val workerFutureListenerExecutor = Executors.newSingleThreadExecutor()

    override fun sendEvent(eventType: String, content: JsonDict?): Cancelable {
        return localEchoEventFactory.createEvent(roomId, eventType, content)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendTextMessage(text: CharSequence, msgType: String, autoMarkdown: Boolean): Cancelable {
        return localEchoEventFactory.createTextEvent(roomId, msgType, text, autoMarkdown)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendFormattedTextMessage(text: String, formattedText: String, msgType: String): Cancelable {
        return localEchoEventFactory.createFormattedTextEvent(roomId, TextContent(text, formattedText), msgType)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendPoll(question: String, options: List<OptionItem>): Cancelable {
        return localEchoEventFactory.createPollEvent(roomId, question, options)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun sendOptionsReply(pollEventId: String, optionIndex: Int, optionValue: String): Cancelable {
        return localEchoEventFactory.createOptionsReplyEvent(roomId, pollEventId, optionIndex, optionValue)
                .also { createLocalEcho(it) }
                .let { sendEvent(it) }
    }

    override fun redactEvent(event: Event, reason: String?): Cancelable {
        // TODO manage media/attachements?
        val redactionEcho = localEchoEventFactory.createRedactEvent(roomId, event.eventId!!, reason)
                .also { createLocalEcho(it) }
        return eventSenderProcessor.postRedaction(redactionEcho, reason)
    }

    override fun resendTextMessage(localEcho: TimelineEvent): Cancelable {
        if (localEcho.root.isTextMessage() && localEcho.root.sendState.hasFailed()) {
            localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
            return sendEvent(localEcho.root)
        }
        return NoOpCancellable
    }

    override fun resendMediaMessage(localEcho: TimelineEvent): Cancelable {
        if (localEcho.root.sendState.hasFailed()) {
            val clearContent = localEcho.root.getClearContent()
            val messageContent = clearContent?.toModel<MessageContent>() as? MessageWithAttachmentContent ?: return NoOpCancellable

            val url = messageContent.getFileUrl() ?: return NoOpCancellable
            if (url.startsWith("mxc://")) {
                // We need to resend only the message as the attachment is ok
                localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                return sendEvent(localEcho.root)
            }

            // we need to resend the media
            return when (messageContent) {
                is MessageImageContent -> {
                    // The image has not yet been sent
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.info!!.size,
                            mimeType = messageContent.mimeType,
                            width = messageContent.info.width.toLong(),
                            height = messageContent.info.height.toLong(),
                            name = messageContent.body,
                            queryUri = Uri.parse(messageContent.url),
                            type = ContentAttachmentData.Type.IMAGE
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                is MessageVideoContent -> {
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.videoInfo?.size ?: 0L,
                            mimeType = messageContent.mimeType,
                            width = messageContent.videoInfo?.width?.toLong(),
                            height = messageContent.videoInfo?.height?.toLong(),
                            duration = messageContent.videoInfo?.duration?.toLong(),
                            name = messageContent.body,
                            queryUri = Uri.parse(messageContent.url),
                            type = ContentAttachmentData.Type.VIDEO
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                is MessageFileContent  -> {
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.info!!.size,
                            mimeType = messageContent.mimeType,
                            name = messageContent.getFileName(),
                            queryUri = Uri.parse(messageContent.url),
                            type = ContentAttachmentData.Type.FILE
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                is MessageAudioContent -> {
                    val attachmentData = ContentAttachmentData(
                            size = messageContent.audioInfo?.size ?: 0,
                            duration = messageContent.audioInfo?.duration?.toLong() ?: 0L,
                            mimeType = messageContent.mimeType,
                            name = messageContent.body,
                            queryUri = Uri.parse(messageContent.url),
                            type = ContentAttachmentData.Type.AUDIO,
                            waveform = messageContent.audioWaveformInfo?.waveform
                    )
                    localEchoRepository.updateSendState(localEcho.eventId, roomId, SendState.UNSENT)
                    internalSendMedia(listOf(localEcho.root), attachmentData, true)
                }
                else                   -> NoOpCancellable
            }
        }
        return NoOpCancellable
    }

    override fun deleteFailedEcho(localEcho: TimelineEvent) {
        taskExecutor.executorScope.launch {
            localEchoRepository.deleteFailedEcho(roomId, localEcho)
        }
    }

    override fun cancelSend(eventId: String) {
        cancelSendTracker.markLocalEchoForCancel(eventId, roomId)
        // This is maybe the current task, so cancel it too
        eventSenderProcessor.cancel(eventId, roomId)
        taskExecutor.executorScope.launch {
            localEchoRepository.deleteFailedEcho(roomId, eventId)
        }
    }

    override fun resendAllFailedMessages() {
        taskExecutor.executorScope.launch {
            val eventsToResend = localEchoRepository.getAllFailedEventsToResend(roomId)
            eventsToResend.forEach {
                if (it.root.isTextMessage()) {
                    resendTextMessage(it)
                } else if (it.root.isAttachmentMessage()) {
                    resendMediaMessage(it)
                }
            }
            localEchoRepository.updateSendState(roomId, eventsToResend.map { it.eventId }, SendState.UNSENT)
        }
    }

    override fun cancelAllFailedMessages() {
        taskExecutor.executorScope.launch {
            localEchoRepository.getAllFailedEventsToResend(roomId).forEach { event ->
                cancelSend(event.eventId)
            }
        }
    }

    override fun sendMedias(attachments: List<ContentAttachmentData>,
                            compressBeforeSending: Boolean,
                            roomIds: Set<String>): Cancelable {
        return attachments.mapTo(CancelableBag()) {
            sendMedia(it, compressBeforeSending, roomIds)
        }
    }

    override fun sendMedia(attachment: ContentAttachmentData,
                           compressBeforeSending: Boolean,
                           roomIds: Set<String>): Cancelable {
        // Create an event with the media file path
        // Ensure current roomId is included in the set
        val allRoomIds = (roomIds + roomId).toList()

        // Create local echo for each room
        val allLocalEchoes = allRoomIds.map {
            localEchoEventFactory.createMediaEvent(it, attachment).also { event ->
                createLocalEcho(event)
            }
        }
        return internalSendMedia(allLocalEchoes, attachment, compressBeforeSending)
    }

    /**
     * We use the roomId of the local echo event
     */
    private fun internalSendMedia(allLocalEchoes: List<Event>, attachment: ContentAttachmentData, compressBeforeSending: Boolean): Cancelable {
        val cancelableBag = CancelableBag()

        allLocalEchoes.groupBy { cryptoSessionInfoProvider.isRoomEncrypted(it.roomId!!) }
                .apply {
                    keys.forEach { isRoomEncrypted ->
                        // Should never be empty
                        val localEchoes = get(isRoomEncrypted).orEmpty()
                        val uploadWork = createUploadMediaWork(localEchoes, attachment, isRoomEncrypted, compressBeforeSending)

                        val dispatcherWork = createMultipleEventDispatcherWork(isRoomEncrypted)

                        workManagerProvider.workManager
                                .beginUniqueWork(buildWorkName(UPLOAD_WORK), ExistingWorkPolicy.APPEND_OR_REPLACE, uploadWork)
                                .then(dispatcherWork)
                                .enqueue()
                                .also { operation ->
                                    operation.result.addListener(Runnable {
                                        if (operation.result.isCancelled) {
                                            Timber.e("CHAIN WAS CANCELLED")
                                        } else if (operation.state.value is Operation.State.FAILURE) {
                                            Timber.e("CHAIN DID FAIL")
                                        }
                                    }, workerFutureListenerExecutor)
                                }

                        cancelableBag.add(CancelableWork(workManagerProvider.workManager, dispatcherWork.id))
                    }
                }

        return cancelableBag
    }

    private fun sendEvent(event: Event): Cancelable {
        return eventSenderProcessor.postEvent(event, cryptoSessionInfoProvider.isRoomEncrypted(event.roomId!!))
    }

    private fun createLocalEcho(event: Event) {
        localEchoEventFactory.createLocalEcho(event)
    }

    private fun buildWorkName(identifier: String): String {
        return "${roomId}_$identifier"
    }

    private fun createUploadMediaWork(allLocalEchos: List<Event>,
                                      attachment: ContentAttachmentData,
                                      isRoomEncrypted: Boolean,
                                      compressBeforeSending: Boolean): OneTimeWorkRequest {
        val localEchoIds = allLocalEchos.map {
            LocalEchoIdentifiers(it.roomId!!, it.eventId!!)
        }
        val uploadMediaWorkerParams = UploadContentWorker.Params(sessionId, localEchoIds, attachment, isRoomEncrypted, compressBeforeSending)
        val uploadWorkData = WorkerParamsFactory.toData(uploadMediaWorkerParams)

        return workManagerProvider.matrixOneTimeWorkRequestBuilder<UploadContentWorker>()
                .setConstraints(WorkManagerProvider.workConstraints)
                .startChain(true)
                .setInputData(uploadWorkData)
                .setBackoffCriteria(BackoffPolicy.LINEAR, WorkManagerProvider.BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .build()
    }

    private fun createMultipleEventDispatcherWork(isRoomEncrypted: Boolean): OneTimeWorkRequest {
        // the list of events will be replaced by the result of the media upload work
        val params = MultipleEventSendingDispatcherWorker.Params(sessionId, emptyList(), isRoomEncrypted)
        val workData = WorkerParamsFactory.toData(params)

        return workManagerProvider.matrixOneTimeWorkRequestBuilder<MultipleEventSendingDispatcherWorker>()
                // No constraint
                // .setConstraints(WorkManagerProvider.workConstraints)
                .startChain(false)
                .setInputData(workData)
                .setBackoffCriteria(BackoffPolicy.LINEAR, WorkManagerProvider.BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .build()
    }
}

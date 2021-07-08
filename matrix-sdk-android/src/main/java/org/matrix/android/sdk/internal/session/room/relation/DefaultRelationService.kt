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
package org.matrix.android.sdk.internal.session.room.relation

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.zhuinden.monarchy.Monarchy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.relation.RelationService
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.crypto.CryptoSessionInfoProvider
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.EventAnnotationsSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.internal.util.awaitCallback
import org.matrix.android.sdk.internal.util.fetchCopyMap
import timber.log.Timber

internal class DefaultRelationService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val eventEditor: EventEditor,
        private val eventSenderProcessor: EventSenderProcessor,
        private val eventFactory: LocalEchoEventFactory,
        private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
        private val findReactionEventForUndoTask: FindReactionEventForUndoTask,
        private val fetchEditHistoryTask: FetchEditHistoryTask,
        private val timelineEventMapper: TimelineEventMapper,
        @SessionDatabase private val monarchy: Monarchy,
        private val taskExecutor: TaskExecutor)
    : RelationService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultRelationService
    }

    override suspend fun sendReaction(targetEventId: String, reaction: String) {
        if (monarchy
                        .fetchCopyMap(
                                { realm ->
                                    TimelineEventEntity.where(realm, roomId, targetEventId).findFirst()
                                },
                                { entity, _ ->
                                    timelineEventMapper.map(entity)
                                })
                        ?.annotations
                        ?.reactionsSummary
                        .orEmpty()
                        .none { it.addedByMe && it.key == reaction }) {
            val event = eventFactory.createReactionEvent(roomId, targetEventId, reaction)
                    .also { saveLocalEcho(it) }
            // TODO: This method should suspend!
            eventSenderProcessor.postEvent(event, false /* reaction are not encrypted*/)
        } else {
            Timber.w("Reaction already added")
        }
    }

    override suspend fun undoReaction(targetEventId: String, reaction: String) {
        val params = FindReactionEventForUndoTask.Params(
                roomId,
                targetEventId,
                reaction
        )
        val data = try {
            // TODO We should avoid using MatrixCallback internally
            awaitCallback<FindReactionEventForUndoTask.Result> {
                findReactionEventForUndoTask
                        .configureWith(params) {
                            this.retryCount = Int.MAX_VALUE
                            this.callback = it
                        }
                        .executeBy(taskExecutor)
            }
        } catch (e: Throwable) {
            return
        }

        if (data.redactEventId == null) {
            Timber.w("Cannot find reaction to undo (not yet synced?)")
            // TODO?
        }
        data.redactEventId?.let { toRedact ->
            val redactEvent = eventFactory.createRedactEvent(roomId, toRedact, null)
                    .also { saveLocalEcho(it) }
            eventSenderProcessor.postRedaction(redactEvent, null)
        }
    }

    override suspend fun editTextMessage(targetEvent: TimelineEvent,
                                         msgType: String,
                                         newBodyText: CharSequence,
                                         newBodyAutoMarkdown: Boolean,
                                         compatibilityBodyText: String) {
        // TODO: This method should suspend!
        eventEditor.editTextMessage(targetEvent, msgType, newBodyText, newBodyAutoMarkdown, compatibilityBodyText)
    }

    override suspend fun editReply(replyToEdit: TimelineEvent,
                                   originalTimelineEvent: TimelineEvent,
                                   newBodyText: String,
                                   compatibilityBodyText: String) {
        // TODO: This method should suspend!
        eventEditor.editReply(replyToEdit, originalTimelineEvent, newBodyText, compatibilityBodyText)
    }

    override suspend fun fetchEditHistory(eventId: String): List<Event> {
        return fetchEditHistoryTask.execute(FetchEditHistoryTask.Params(roomId, eventId))
    }

    override suspend fun replyToMessage(eventReplied: TimelineEvent, replyText: CharSequence, autoMarkdown: Boolean) {
        val event = eventFactory.createReplyTextEvent(roomId, eventReplied, replyText, autoMarkdown)
                ?.also { saveLocalEcho(it) }
                ?: return

        // TODO: This method should suspend!
        eventSenderProcessor.postEvent(event, cryptoSessionInfoProvider.isRoomEncrypted(roomId))
    }

    override fun getEventAnnotationsSummary(eventId: String): EventAnnotationsSummary? {
        return monarchy.fetchCopyMap(
                { EventAnnotationsSummaryEntity.where(it, roomId, eventId).findFirst() },
                { entity, _ ->
                    entity.asDomain()
                }
        )
    }

    override fun getEventAnnotationsSummaryLive(eventId: String): LiveData<Optional<EventAnnotationsSummary>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { EventAnnotationsSummaryEntity.where(it, roomId, eventId) },
                { it.asDomain() }
        )
        return Transformations.map(liveData) { results ->
            results.firstOrNull().toOptional()
        }
    }

    /**
     * Saves the event in database as a local echo.
     * SendState is set to UNSENT and it's added to a the sendingTimelineEvents list of the room.
     * The sendingTimelineEvents is checked on new sync and will remove the local echo if an event with
     * the same transaction id is received (in unsigned data)
     */
    private fun saveLocalEcho(event: Event) {
        eventFactory.createLocalEcho(event)
    }
}

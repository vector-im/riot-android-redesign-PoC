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

package org.matrix.android.sdk.internal.session.room

import androidx.lifecycle.LiveData
import org.matrix.android.sdk.api.session.accountdata.AccountDataService
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.alias.AliasService
import org.matrix.android.sdk.api.session.room.call.RoomCallService
import org.matrix.android.sdk.api.session.room.members.MembershipService
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.relation.RelationService
import org.matrix.android.sdk.api.session.room.notification.RoomPushRuleService
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.reporting.ReportingService
import org.matrix.android.sdk.api.session.room.send.DraftService
import org.matrix.android.sdk.api.session.room.send.SendService
import org.matrix.android.sdk.api.session.room.state.StateService
import org.matrix.android.sdk.api.session.room.tags.TagsService
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.matrix.android.sdk.api.session.room.typing.TypingService
import org.matrix.android.sdk.api.session.room.uploads.UploadsService
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.api.session.space.Space
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.internal.session.permalinks.ViaParameterFinder
import org.matrix.android.sdk.internal.session.room.accountdata.RoomAccountDataService
import org.matrix.android.sdk.internal.session.room.state.SendStateTask
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.internal.session.search.SearchTask
import org.matrix.android.sdk.internal.session.space.DefaultSpace
import org.matrix.android.sdk.internal.util.awaitCallback
import java.security.InvalidParameterException

internal class DefaultRoom(override val roomId: String,
                           private val roomSummaryDataSource: RoomSummaryDataSource,
                           private val timelineService: TimelineService,
                           private val sendService: SendService,
                           private val draftService: DraftService,
                           private val stateService: StateService,
                           private val uploadsService: UploadsService,
                           private val reportingService: ReportingService,
                           private val roomCallService: RoomCallService,
                           private val readService: ReadService,
                           private val typingService: TypingService,
                           private val aliasService: AliasService,
                           private val tagsService: TagsService,
                           private val cryptoService: CryptoService,
                           private val relationService: RelationService,
                           private val roomMembersService: MembershipService,
                           private val roomPushRuleService: RoomPushRuleService,
                           private val roomAccountDataService: RoomAccountDataService,
                           private val sendStateTask: SendStateTask,
                           private val viaParameterFinder: ViaParameterFinder,
                           private val searchTask: SearchTask) :
        Room,
        TimelineService by timelineService,
        SendService by sendService,
        DraftService by draftService,
        StateService by stateService,
        UploadsService by uploadsService,
        ReportingService by reportingService,
        RoomCallService by roomCallService,
        ReadService by readService,
        TypingService by typingService,
        AliasService by aliasService,
        TagsService by tagsService,
        RelationService by relationService,
        MembershipService by roomMembersService,
        RoomPushRuleService by roomPushRuleService,
        AccountDataService by roomAccountDataService {

    override fun getRoomSummaryLive(): LiveData<Optional<RoomSummary>> {
        return roomSummaryDataSource.getRoomSummaryLive(roomId)
    }

    override fun roomSummary(): RoomSummary? {
        return roomSummaryDataSource.getRoomSummary(roomId)
    }

    override fun isEncrypted(): Boolean {
        return cryptoService.isRoomEncrypted(roomId)
    }

    override fun encryptionAlgorithm(): String? {
        return cryptoService.getEncryptionAlgorithm(roomId)
    }

    override fun shouldEncryptForInvitedMembers(): Boolean {
        return cryptoService.shouldEncryptForInvitedMembers(roomId)
    }

    override suspend fun prepareToEncrypt() {
        awaitCallback<Unit> {
            cryptoService.prepareToEncrypt(roomId, it)
        }
    }

    override suspend fun enableEncryption(algorithm: String) {
        when {
            isEncrypted()                          -> {
                throw IllegalStateException("Encryption is already enabled for this room")
            }
            algorithm != MXCRYPTO_ALGORITHM_MEGOLM -> {
                throw InvalidParameterException("Only MXCRYPTO_ALGORITHM_MEGOLM algorithm is supported")
            }
            else                                   -> {
                val params = SendStateTask.Params(
                        roomId = roomId,
                        stateKey = null,
                        eventType = EventType.STATE_ROOM_ENCRYPTION,
                        body = mapOf(
                                "algorithm" to algorithm
                        ))

                sendStateTask.execute(params)
            }
        }
    }

    override suspend fun search(searchTerm: String,
                                nextBatch: String?,
                                orderByRecent: Boolean,
                                limit: Int,
                                beforeLimit: Int,
                                afterLimit: Int,
                                includeProfile: Boolean): SearchResult {
        return searchTask.execute(
                SearchTask.Params(
                        searchTerm = searchTerm,
                        roomId = roomId,
                        nextBatch = nextBatch,
                        orderByRecent = orderByRecent,
                        limit = limit,
                        beforeLimit = beforeLimit,
                        afterLimit = afterLimit,
                        includeProfile = includeProfile
                )
        )
    }

    override fun asSpace(): Space? {
        if (roomSummary()?.roomType != RoomType.SPACE) return null
        return DefaultSpace(this, roomSummaryDataSource, viaParameterFinder)
    }
}

/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.space

import android.net.Uri
import androidx.lifecycle.LiveData
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomDirectoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomPreset
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.space.CreateSpaceParams
import org.matrix.android.sdk.api.session.space.JoinSpaceResult
import org.matrix.android.sdk.api.session.space.Space
import org.matrix.android.sdk.api.session.space.SpaceService
import org.matrix.android.sdk.api.session.space.SpaceSummaryQueryParams
import org.matrix.android.sdk.api.session.space.model.SpaceChildContent
import org.matrix.android.sdk.api.session.space.model.SpaceParentContent
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.RoomGetter
import org.matrix.android.sdk.internal.session.room.SpaceGetter
import org.matrix.android.sdk.internal.session.room.create.CreateRoomTask
import org.matrix.android.sdk.internal.session.room.membership.leaving.LeaveRoomTask
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.internal.session.space.peeking.PeekSpaceTask
import org.matrix.android.sdk.internal.session.space.peeking.SpacePeekResult
import javax.inject.Inject

internal class DefaultSpaceService @Inject constructor(
        @UserId private val userId: String,
        private val createRoomTask: CreateRoomTask,
        private val joinSpaceTask: JoinSpaceTask,
        private val spaceGetter: SpaceGetter,
        private val roomGetter: RoomGetter,
        private val roomSummaryDataSource: RoomSummaryDataSource,
        private val stateEventDataSource: StateEventDataSource,
        private val peekSpaceTask: PeekSpaceTask,
        private val resolveSpaceInfoTask: ResolveSpaceInfoTask,
        private val leaveRoomTask: LeaveRoomTask
) : SpaceService {

    override suspend fun createSpace(params: CreateSpaceParams): String {
        return createRoomTask.executeRetry(params, 3)
    }

    override suspend fun createSpace(name: String, topic: String?, avatarUri: Uri?, isPublic: Boolean): String {
        return createSpace(CreateSpaceParams().apply {
            this.name = name
            this.topic = topic
            this.avatarUri = avatarUri
            if (isPublic) {
                this.powerLevelContentOverride = (powerLevelContentOverride ?: PowerLevelsContent()).copy(
                        invite = 0
                )
                this.preset = CreateRoomPreset.PRESET_PUBLIC_CHAT
                this.historyVisibility = RoomHistoryVisibility.WORLD_READABLE
                this.guestAccess = GuestAccess.CanJoin
            } else {
                this.preset = CreateRoomPreset.PRESET_PRIVATE_CHAT
                visibility = RoomDirectoryVisibility.PRIVATE
            }
        })
    }

    override fun getSpace(spaceId: String): Space? {
        return spaceGetter.get(spaceId)
    }

    override fun getSpaceSummariesLive(queryParams: SpaceSummaryQueryParams): LiveData<List<RoomSummary>> {
        return roomSummaryDataSource.getSpaceSummariesLive(queryParams)
    }

    override fun getSpaceSummaries(spaceSummaryQueryParams: SpaceSummaryQueryParams): List<RoomSummary> {
        return roomSummaryDataSource.getSpaceSummaries(spaceSummaryQueryParams)
    }

    override fun getRootSpaceSummaries(): List<RoomSummary> {
        return roomSummaryDataSource.getRootSpaceSummaries()
    }

    override suspend fun peekSpace(spaceId: String): SpacePeekResult {
        return peekSpaceTask.execute(PeekSpaceTask.Params(spaceId))
    }

    override suspend fun querySpaceChildren(spaceId: String,
                                            suggestedOnly: Boolean?,
                                            autoJoinedOnly: Boolean?): Pair<RoomSummary, List<SpaceChildInfo>> {
        return resolveSpaceInfoTask.execute(ResolveSpaceInfoTask.Params.withId(spaceId, suggestedOnly, autoJoinedOnly)).let { response ->
            val spaceDesc = response.rooms?.firstOrNull { it.roomId == spaceId }
            Pair(
                    first = RoomSummary(
                            roomId = spaceDesc?.roomId ?: spaceId,
                            roomType = spaceDesc?.roomType,
                            name = spaceDesc?.name ?: "",
                            displayName = spaceDesc?.name ?: "",
                            topic = spaceDesc?.topic ?: "",
                            joinedMembersCount = spaceDesc?.numJoinedMembers,
                            avatarUrl = spaceDesc?.avatarUrl ?: "",
                            encryptionEventTs = null,
                            typingUsers = emptyList(),
                            isEncrypted = false,
                            flattenParentIds = emptyList()
                    ),
                    second = response.rooms
                            ?.filter { it.roomId != spaceId }
                            ?.flatMap { childSummary ->
                                response.events
                                        ?.filter { it.stateKey == childSummary.roomId && it.type == EventType.STATE_SPACE_CHILD }
                                        ?.mapNotNull { childStateEv ->
                                            // create a child entry for everytime this room is the child of a space
                                            // beware that a room could appear then twice in this list
                                            childStateEv.content.toModel<SpaceChildContent>()?.let { childStateEvContent ->
                                                SpaceChildInfo(
                                                        childRoomId = childSummary.roomId,
                                                        isKnown = true,
                                                        roomType = childSummary.roomType,
                                                        name = childSummary.name,
                                                        topic = childSummary.topic,
                                                        avatarUrl = childSummary.avatarUrl,
                                                        order = childStateEvContent.order,
                                                        autoJoin = childStateEvContent.autoJoin ?: false,
                                                        viaServers = childStateEvContent.via.orEmpty(),
                                                        activeMemberCount = childSummary.numJoinedMembers,
                                                        parentRoomId = childStateEv.roomId,
                                                        suggested = childStateEvContent.suggested,
                                                        canonicalAlias = childSummary.canonicalAlias,
                                                        aliases = childSummary.aliases
                                                )
                                            }
                                        }.orEmpty()
                            }
                            .orEmpty()
            )
        }
    }

    override suspend fun joinSpace(spaceIdOrAlias: String,
                                   reason: String?,
                                   viaServers: List<String>): JoinSpaceResult {
        return joinSpaceTask.execute(JoinSpaceTask.Params(spaceIdOrAlias, reason, viaServers))
    }

    override suspend fun rejectInvite(spaceId: String, reason: String?) {
        leaveRoomTask.execute(LeaveRoomTask.Params(spaceId, reason))
    }

//    override fun getSpaceParentsOfRoom(roomId: String): List<SpaceSummary> {
//        return spaceSummaryDataSource.getParentsOfRoom(roomId)
//    }

    override suspend fun setSpaceParent(childRoomId: String, parentSpaceId: String, canonical: Boolean, viaServers: List<String>) {
        // Should we perform some validation here?,
        // and if client want to bypass, it could use sendStateEvent directly?
        if (canonical) {
            // check that we can send m.child in the parent room
            if (roomSummaryDataSource.getRoomSummary(parentSpaceId)?.membership != Membership.JOIN) {
                throw UnsupportedOperationException("Cannot add canonical child if not member of parent")
            }
            val powerLevelsEvent = stateEventDataSource.getStateEvent(
                    roomId = parentSpaceId,
                    eventType = EventType.STATE_ROOM_POWER_LEVELS,
                    stateKey = QueryStringValue.NoCondition
            )
            val powerLevelsContent = powerLevelsEvent?.content?.toModel<PowerLevelsContent>()
                    ?: throw UnsupportedOperationException("Cannot add canonical child, missing powerlevel")
            val powerLevelsHelper = PowerLevelsHelper(powerLevelsContent)
            if (!powerLevelsHelper.isUserAllowedToSend(userId, true, EventType.STATE_SPACE_CHILD)) {
                throw UnsupportedOperationException("Cannot add canonical child, not enough power level")
            }
        }

        val room = roomGetter.getRoom(childRoomId)
                ?: throw IllegalArgumentException("Unknown Room $childRoomId")

        room.sendStateEvent(
                eventType = EventType.STATE_SPACE_PARENT,
                stateKey = parentSpaceId,
                body = SpaceParentContent(
                        via = viaServers,
                        canonical = canonical
                ).toContent()
        )
    }
}

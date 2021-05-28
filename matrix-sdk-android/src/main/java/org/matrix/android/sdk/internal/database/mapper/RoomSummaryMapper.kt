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

package org.matrix.android.sdk.internal.database.mapper

import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.SpaceParentInfo
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.session.typing.DefaultTypingUsersTracker
import javax.inject.Inject

internal class RoomSummaryMapper @Inject constructor(private val timelineEventMapper: TimelineEventMapper,
                                                     private val typingUsersTracker: DefaultTypingUsersTracker) {

    fun map(roomSummaryEntity: RoomSummaryEntity): RoomSummary {
        val tags = roomSummaryEntity.tags().map {
            RoomTag(it.tagName, it.tagOrder)
        }

        val latestEvent = roomSummaryEntity.latestPreviewableEvent?.let {
            timelineEventMapper.map(it, buildReadReceipts = false)
        }
        // typings are updated through the sync where room summary entity gets updated no matter what, so it's ok get there
        val typingUsers = typingUsersTracker.getTypingUsers(roomSummaryEntity.roomId)

        return RoomSummary(
                roomId = roomSummaryEntity.roomId,
                displayName = roomSummaryEntity.displayName ?: "",
                name = roomSummaryEntity.name ?: "",
                topic = roomSummaryEntity.topic ?: "",
                avatarUrl = roomSummaryEntity.avatarUrl ?: "",
                joinRules = roomSummaryEntity.joinRules,
                isDirect = roomSummaryEntity.isDirect,
                directUserId = roomSummaryEntity.directUserId,
                latestPreviewableEvent = latestEvent,
                joinedMembersCount = roomSummaryEntity.joinedMembersCount,
                invitedMembersCount = roomSummaryEntity.invitedMembersCount,
                otherMemberIds = roomSummaryEntity.otherMemberIds.toList(),
                highlightCount = roomSummaryEntity.highlightCount,
                notificationCount = roomSummaryEntity.notificationCount,
                hasUnreadMessages = roomSummaryEntity.hasUnreadMessages,
                tags = tags,
                typingUsers = typingUsers,
                membership = roomSummaryEntity.membership,
                versioningState = roomSummaryEntity.versioningState,
                readMarkerId = roomSummaryEntity.readMarkerId,
                userDrafts = roomSummaryEntity.userDrafts?.userDrafts?.map { DraftMapper.map(it) }.orEmpty(),
                canonicalAlias = roomSummaryEntity.canonicalAlias,
                aliases = roomSummaryEntity.aliases.toList(),
                isEncrypted = roomSummaryEntity.isEncrypted,
                encryptionEventTs = roomSummaryEntity.encryptionEventTs,
                breadcrumbsIndex = roomSummaryEntity.breadcrumbsIndex,
                roomEncryptionTrustLevel = roomSummaryEntity.roomEncryptionTrustLevel,
                inviterId = roomSummaryEntity.inviterId,
                hasFailedSending = roomSummaryEntity.hasFailedSending,
                roomType = roomSummaryEntity.roomType,
                spaceParents = roomSummaryEntity.parents.map { relationInfoEntity ->
                    SpaceParentInfo(
                            parentId = relationInfoEntity.parentRoomId,
                            roomSummary = relationInfoEntity.parentSummaryEntity?.let { map(it) },
                            canonical = relationInfoEntity.canonical ?: false,
                            viaServers = relationInfoEntity.viaServers.toList()
                    )
                },
                spaceChildren = roomSummaryEntity.children.map {
                    SpaceChildInfo(
                            childRoomId = it.childRoomId ?: "",
                            isKnown = it.childSummaryEntity != null,
                            roomType = it.childSummaryEntity?.roomType,
                            name = it.childSummaryEntity?.name,
                            topic = it.childSummaryEntity?.topic,
                            avatarUrl = it.childSummaryEntity?.avatarUrl,
                            activeMemberCount = it.childSummaryEntity?.joinedMembersCount,
                            order = it.order,
                            autoJoin = it.autoJoin ?: false,
                            viaServers = it.viaServers.toList(),
                            parentRoomId = roomSummaryEntity.roomId,
                            suggested = it.suggested,
                            canonicalAlias = it.childSummaryEntity?.canonicalAlias,
                            aliases = it.childSummaryEntity?.aliases?.toList()
                    )
                },
                flattenParentIds = roomSummaryEntity.flattenParentIds?.split("|") ?: emptyList()
        )
    }
}

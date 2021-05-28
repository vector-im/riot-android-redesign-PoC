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

package org.matrix.android.sdk.internal.session.room.summary

import io.realm.Realm
import io.realm.kotlin.createObject
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomAliasesContent
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomNameContent
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.VersioningState
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.crypto.EventDecryptor
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.internal.crypto.crosssigning.DefaultCrossSigningService
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventEntityFields
import org.matrix.android.sdk.internal.database.model.GroupSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.SpaceChildSummaryEntity
import org.matrix.android.sdk.internal.database.model.SpaceParentSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.query.findAllInRoomWithSendStates
import org.matrix.android.sdk.internal.database.query.getOrCreate
import org.matrix.android.sdk.internal.database.query.getOrNull
import org.matrix.android.sdk.internal.database.query.isEventRead
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.database.query.whereType
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.extensions.clearWith
import org.matrix.android.sdk.internal.query.process
import org.matrix.android.sdk.internal.session.room.RoomAvatarResolver
import org.matrix.android.sdk.internal.session.room.accountdata.RoomAccountDataDataSource
import org.matrix.android.sdk.internal.session.room.membership.RoomDisplayNameResolver
import org.matrix.android.sdk.internal.session.room.membership.RoomMemberHelper
import org.matrix.android.sdk.internal.session.room.relationship.RoomChildRelationInfo
import org.matrix.android.sdk.internal.session.sync.model.RoomSyncSummary
import org.matrix.android.sdk.internal.session.sync.model.RoomSyncUnreadNotifications
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.measureTimeMillis

internal class RoomSummaryUpdater @Inject constructor(
        @UserId private val userId: String,
        private val roomDisplayNameResolver: RoomDisplayNameResolver,
        private val roomAvatarResolver: RoomAvatarResolver,
        private val eventDecryptor: EventDecryptor,
        private val crossSigningService: DefaultCrossSigningService,
        private val roomAccountDataDataSource: RoomAccountDataDataSource) {

    fun update(realm: Realm,
               roomId: String,
               membership: Membership? = null,
               roomSummary: RoomSyncSummary? = null,
               unreadNotifications: RoomSyncUnreadNotifications? = null,
               updateMembers: Boolean = false,
               inviterId: String? = null) {
        val roomSummaryEntity = RoomSummaryEntity.getOrCreate(realm, roomId)
        if (roomSummary != null) {
            if (roomSummary.heroes.isNotEmpty()) {
                roomSummaryEntity.heroes.clear()
                roomSummaryEntity.heroes.addAll(roomSummary.heroes)
            }
            if (roomSummary.invitedMembersCount != null) {
                roomSummaryEntity.invitedMembersCount = roomSummary.invitedMembersCount
            }
            if (roomSummary.joinedMembersCount != null) {
                roomSummaryEntity.joinedMembersCount = roomSummary.joinedMembersCount
            }
        }
        roomSummaryEntity.highlightCount = unreadNotifications?.highlightCount ?: 0
        roomSummaryEntity.notificationCount = unreadNotifications?.notificationCount ?: 0

        if (membership != null) {
            roomSummaryEntity.membership = membership
        }

        // Hard to filter from the app now we use PagedList...
        roomSummaryEntity.isHiddenFromUser = roomSummaryEntity.versioningState == VersioningState.UPGRADED_ROOM_JOINED
                || roomAccountDataDataSource.getAccountDataEvent(roomId, RoomAccountDataTypes.EVENT_TYPE_VIRTUAL_ROOM) != null

        val lastNameEvent = CurrentStateEventEntity.getOrNull(realm, roomId, type = EventType.STATE_ROOM_NAME, stateKey = "")?.root
        val lastTopicEvent = CurrentStateEventEntity.getOrNull(realm, roomId, type = EventType.STATE_ROOM_TOPIC, stateKey = "")?.root
        val lastCanonicalAliasEvent = CurrentStateEventEntity.getOrNull(realm, roomId, type = EventType.STATE_ROOM_CANONICAL_ALIAS, stateKey = "")?.root
        val lastAliasesEvent = CurrentStateEventEntity.getOrNull(realm, roomId, type = EventType.STATE_ROOM_ALIASES, stateKey = "")?.root
        val roomCreateEvent = CurrentStateEventEntity.getOrNull(realm, roomId, type = EventType.STATE_ROOM_CREATE, stateKey = "")?.root
        val joinRulesEvent = CurrentStateEventEntity.getOrNull(realm, roomId, type = EventType.STATE_ROOM_JOIN_RULES, stateKey = "")?.root

        val roomType = ContentMapper.map(roomCreateEvent?.content).toModel<RoomCreateContent>()?.type
        roomSummaryEntity.roomType = roomType
        Timber.v("## Space: Updating summary room [$roomId] roomType: [$roomType]")

        // Don't use current state for this one as we are only interested in having MXCRYPTO_ALGORITHM_MEGOLM event in the room
        val encryptionEvent = EventEntity.whereType(realm, roomId = roomId, type = EventType.STATE_ROOM_ENCRYPTION)
                .contains(EventEntityFields.CONTENT, "\"algorithm\":\"$MXCRYPTO_ALGORITHM_MEGOLM\"")
                .isNotNull(EventEntityFields.STATE_KEY)
                .findFirst()

        val latestPreviewableEvent = RoomSummaryEventsHelper.getLatestPreviewableEvent(realm, roomId)

        val lastActivityFromEvent = latestPreviewableEvent?.root?.originServerTs
        if (lastActivityFromEvent != null) {
            roomSummaryEntity.lastActivityTime = lastActivityFromEvent
        }

        roomSummaryEntity.hasUnreadMessages = roomSummaryEntity.notificationCount > 0
                // avoid this call if we are sure there are unread events
                || !isEventRead(realm.configuration, userId, roomId, latestPreviewableEvent?.eventId)

        roomSummaryEntity.displayName = roomDisplayNameResolver.resolve(realm, roomId)
        roomSummaryEntity.avatarUrl = roomAvatarResolver.resolve(realm, roomId)
        roomSummaryEntity.name = ContentMapper.map(lastNameEvent?.content).toModel<RoomNameContent>()?.name
        roomSummaryEntity.topic = ContentMapper.map(lastTopicEvent?.content).toModel<RoomTopicContent>()?.topic
        roomSummaryEntity.joinRules = ContentMapper.map(joinRulesEvent?.content).toModel<RoomJoinRulesContent>()?.joinRules
        roomSummaryEntity.latestPreviewableEvent = latestPreviewableEvent
        roomSummaryEntity.canonicalAlias = ContentMapper.map(lastCanonicalAliasEvent?.content).toModel<RoomCanonicalAliasContent>()
                ?.canonicalAlias

        val roomAliases = ContentMapper.map(lastAliasesEvent?.content).toModel<RoomAliasesContent>()?.aliases
                .orEmpty()
        roomSummaryEntity.updateAliases(roomAliases)
        roomSummaryEntity.isEncrypted = encryptionEvent != null
        roomSummaryEntity.encryptionEventTs = encryptionEvent?.originServerTs

        if (roomSummaryEntity.membership == Membership.INVITE && inviterId != null) {
            roomSummaryEntity.inviterId = inviterId
        } else if (roomSummaryEntity.membership != Membership.INVITE) {
            roomSummaryEntity.inviterId = null
        }
        roomSummaryEntity.updateHasFailedSending()

        val root = latestPreviewableEvent?.root
        if (root?.type == EventType.ENCRYPTED && root.decryptionResultJson == null) {
            Timber.v("Should decrypt ${latestPreviewableEvent.eventId}")
            // mmm i want to decrypt now or is it ok to do it async?
            tryOrNull {
                eventDecryptor.decryptEvent(root.asDomain(), "")
            }
                    ?.let { root.setDecryptionResult(it) }
        }

        if (updateMembers) {
            val otherRoomMembers = RoomMemberHelper(realm, roomId)
                    .queryActiveRoomMembersEvent()
                    .notEqualTo(RoomMemberSummaryEntityFields.USER_ID, userId)
                    .findAll()
                    .map { it.userId }

            roomSummaryEntity.otherMemberIds.clear()
            roomSummaryEntity.otherMemberIds.addAll(otherRoomMembers)
            if (roomSummaryEntity.isEncrypted && otherRoomMembers.isNotEmpty()) {
                // mmm maybe we could only refresh shield instead of checking trust also?
                crossSigningService.onUsersDeviceUpdate(otherRoomMembers)
            }
        }
    }

    private fun RoomSummaryEntity.updateHasFailedSending() {
        hasFailedSending = TimelineEventEntity.findAllInRoomWithSendStates(realm, roomId, SendState.HAS_FAILED_STATES).isNotEmpty()
    }

    fun updateSendingInformation(realm: Realm, roomId: String) {
        val roomSummaryEntity = RoomSummaryEntity.getOrCreate(realm, roomId)
        roomSummaryEntity.updateHasFailedSending()
        roomSummaryEntity.latestPreviewableEvent = RoomSummaryEventsHelper.getLatestPreviewableEvent(realm, roomId)
    }

    /**
     * Should be called at the end of the room sync, to check and validate all parent/child relations
     */
    fun validateSpaceRelationship(realm: Realm) {
        measureTimeMillis {
            val lookupMap = realm.where(RoomSummaryEntity::class.java)
                    .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                    .equalTo(RoomSummaryEntityFields.IS_DIRECT, false)
                    // we order by roomID to be consistent when breaking parent/child cycles
                    .sort(RoomSummaryEntityFields.ROOM_ID)
                    .findAll().map {
                        it.flattenParentIds = null
                        it to emptyList<RoomSummaryEntity>().toMutableSet()
                    }
                    .toMap()

            lookupMap.keys.forEach { lookedUp ->
                if (lookedUp.roomType == RoomType.SPACE) {
                    // get childrens

                    lookedUp.children.clearWith { it.deleteFromRealm() }

                    RoomChildRelationInfo(realm, lookedUp.roomId).getDirectChildrenDescriptions().forEach { child ->

                        lookedUp.children.add(
                                realm.createObject<SpaceChildSummaryEntity>().apply {
                                    this.childRoomId = child.roomId
                                    this.childSummaryEntity = RoomSummaryEntity.where(realm, child.roomId).findFirst()
                                    this.order = child.order
                                    this.autoJoin = child.autoJoin
                                    this.viaServers.addAll(child.viaServers)
                                }
                        )

                        RoomSummaryEntity.where(realm, child.roomId)
                                .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                                .findFirst()
                                ?.let { childSum ->
                                    lookupMap.entries.firstOrNull { it.key.roomId == lookedUp.roomId }?.let { entry ->
                                        if (entry.value.indexOfFirst { it.roomId == childSum.roomId } == -1) {
                                            // add looked up as a parent
                                            entry.value.add(childSum)
                                        }
                                    }
                                }
                    }
                } else {
                    lookedUp.parents.clearWith { it.deleteFromRealm() }
                    // can we check parent relations here??
                    RoomChildRelationInfo(realm, lookedUp.roomId).getParentDescriptions()
                            .map { parentInfo ->

                                lookedUp.parents.add(
                                        realm.createObject<SpaceParentSummaryEntity>().apply {
                                            this.parentRoomId = parentInfo.roomId
                                            this.parentSummaryEntity = RoomSummaryEntity.where(realm, parentInfo.roomId).findFirst()
                                            this.canonical = parentInfo.canonical
                                            this.viaServers.addAll(parentInfo.viaServers)
                                        }
                                )

                                RoomSummaryEntity.where(realm, parentInfo.roomId)
                                        .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                                        .findFirst()
                                        ?.let { parentSum ->
                                            if (lookupMap[parentSum]?.indexOfFirst { it.roomId == lookedUp.roomId } == -1) {
                                                // add lookedup as a parent
                                                lookupMap[parentSum]?.add(lookedUp)
                                            }
                                        }
                            }
                }
            }

            // Simple algorithm to break cycles
            // Need more work to decide how to break, probably need to be as consistent as possible
            // and also find best way to root the tree

            val graph = Graph()
            lookupMap
                    // focus only on joined spaces, as room are just leaf
                    .filter { it.key.roomType == RoomType.SPACE && it.key.membership == Membership.JOIN }
                    .forEach { (sum, children) ->
                        graph.getOrCreateNode(sum.roomId)
                        children.forEach {
                            graph.addEdge(it.roomId, sum.roomId)
                        }
                    }

            val backEdges = graph.findBackwardEdges()
            Timber.v("## SPACES: Cycle detected = ${backEdges.isNotEmpty()}")

            // break cycles
            backEdges.forEach { edge ->
                lookupMap.entries.find { it.key.roomId == edge.source.name }?.let {
                    it.value.removeAll { it.roomId == edge.destination.name }
                }
            }

            val acyclicGraph = graph.withoutEdges(backEdges)
//            Timber.v("## SPACES: acyclicGraph $acyclicGraph")
            val flattenSpaceParents = acyclicGraph.flattenDestination().map {
                it.key.name to it.value.map { it.name }
            }.toMap()
//            Timber.v("## SPACES: flattenSpaceParents ${flattenSpaceParents.map { it.key.name to it.value.map { it.name } }.joinToString("\n") {
//                it.first + ": [" + it.second.joinToString(",") + "]"
//            }}")

//            Timber.v("## SPACES: lookup map ${lookupMap.map { it.key.name to it.value.map { it.name } }.toMap()}")

            lookupMap.entries
                    .filter { it.key.roomType == RoomType.SPACE && it.key.membership == Membership.JOIN }
                    .forEach { entry ->
                        val parent = RoomSummaryEntity.where(realm, entry.key.roomId).findFirst()
                        if (parent != null) {
//                            Timber.v("## SPACES: check hierarchy of ${parent.name} id ${parent.roomId}")
//                            Timber.v("## SPACES: flat known parents of ${parent.name} are ${flattenSpaceParents[parent.roomId]}")
                            val flattenParentsIds = (flattenSpaceParents[parent.roomId] ?: emptyList()) + listOf(parent.roomId)
//                            Timber.v("## SPACES: flatten known parents of children of ${parent.name} are ${flattenParentsIds}")
                            entry.value.forEach { child ->
                                RoomSummaryEntity.where(realm, child.roomId).findFirst()?.let { childSum ->

//                                    Timber.w("## SPACES: ${childSum.name} is ${childSum.roomId} fc: ${childSum.flattenParentIds}")
//                                    var allParents = childSum.flattenParentIds ?: ""
                                    if (childSum.flattenParentIds == null) childSum.flattenParentIds = ""
                                    flattenParentsIds.forEach {
                                        if (childSum.flattenParentIds?.contains(it) != true) {
                                            childSum.flattenParentIds += "|$it"
                                        }
                                    }
//                                    childSum.flattenParentIds = "$allParents|"

//                                    Timber.v("## SPACES: flatten of ${childSum.name} is ${childSum.flattenParentIds}")
                                }
                            }
                        }
                    }

            // we need also to filter DMs...
            // it's more annoying as based on if the other members belong the space or not
            RoomSummaryEntity.where(realm)
                    .equalTo(RoomSummaryEntityFields.IS_DIRECT, true)
                    .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                    .findAll()
                    .forEach { dmRoom ->
                        val relatedSpaces = lookupMap.keys
                                .filter { it.roomType == RoomType.SPACE }
                                .filter {
                                    dmRoom.otherMemberIds.toList().intersect(it.otherMemberIds.toList()).isNotEmpty()
                                }
                                .map { it.roomId }
                                .distinct()
                        val flattenRelated = mutableListOf<String>().apply {
                            addAll(relatedSpaces)
                            relatedSpaces.map { flattenSpaceParents[it] }.forEach {
                                if (it != null) addAll(it)
                            }
                        }.distinct()
                        if (flattenRelated.isNotEmpty()) {
                            // we keep real m.child/m.parent relations and add the one for common memberships
                            dmRoom.flattenParentIds += "|${flattenRelated.joinToString("|")}|"
                        }
//                        Timber.v("## SPACES: flatten of ${dmRoom.otherMemberIds.joinToString(",")} is ${dmRoom.flattenParentIds}")
                    }

            // Maybe a good place to count the number of notifications for spaces?

            realm.where(RoomSummaryEntity::class.java)
                    .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                    .equalTo(RoomSummaryEntityFields.ROOM_TYPE, RoomType.SPACE)
                    .findAll().forEach { space ->
                        // get all children
                        var highlightCount = 0
                        var notificationCount = 0
                        realm.where(RoomSummaryEntity::class.java)
                                .process(RoomSummaryEntityFields.MEMBERSHIP_STR, listOf(Membership.JOIN))
                                .notEqualTo(RoomSummaryEntityFields.ROOM_TYPE, RoomType.SPACE)
                                // also we do not count DM in here, because home space will already show them
                                .equalTo(RoomSummaryEntityFields.IS_DIRECT, false)
                                .contains(RoomSummaryEntityFields.FLATTEN_PARENT_IDS, space.roomId)
                                .findAll().forEach {
                                    highlightCount += it.highlightCount
                                    notificationCount += it.notificationCount
                                }

                        space.highlightCount = highlightCount
                        space.notificationCount = notificationCount
                    }
            // xxx invites??

            // LEGACY GROUPS
            // lets mark rooms that belongs to groups
            val existingGroups = GroupSummaryEntity.where(realm).findAll()

            // For rooms
            realm.where(RoomSummaryEntity::class.java)
                    .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                    .equalTo(RoomSummaryEntityFields.IS_DIRECT, false)
                    .findAll().forEach { room ->
                        val belongsTo = existingGroups.filter { it.roomIds.contains(room.roomId) }
                        room.groupIds = if (belongsTo.isEmpty()) {
                            null
                        } else {
                            "|${belongsTo.joinToString("|")}|"
                        }
                    }

            // For DMS
            realm.where(RoomSummaryEntity::class.java)
                    .process(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.activeMemberships())
                    .equalTo(RoomSummaryEntityFields.IS_DIRECT, true)
                    .findAll().forEach { room ->
                        val belongsTo = existingGroups.filter {
                            it.userIds.intersect(room.otherMemberIds).isNotEmpty()
                        }
                        room.groupIds = if (belongsTo.isEmpty()) {
                            null
                        } else {
                            "|${belongsTo.joinToString("|")}|"
                        }
                    }
        }.also {
            Timber.v("## SPACES: Finish checking room hierarchy in $it ms")
        }
    }

//    private fun isValidCanonical() : Boolean {
//
//    }
}

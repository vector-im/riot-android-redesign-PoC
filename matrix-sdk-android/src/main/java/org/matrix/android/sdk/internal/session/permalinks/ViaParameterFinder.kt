/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.permalinks

import org.matrix.android.sdk.api.MatrixPatterns.getDomain
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.RoomGetter
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Provider

internal class ViaParameterFinder @Inject constructor(
        @UserId private val userId: String,
        private val roomGetterProvider: Provider<RoomGetter>,
        private val stateEventDataSource: StateEventDataSource
) {

    fun computeViaParams(roomId: String, max: Int): List<String> {
        return computeViaParams(userId, roomId, max)
    }

    /**
     * Compute the via parameters.
     * Take up to 3 homeserver domains, taking the most representative one regarding room members and including the
     * current user one.
     */
    fun computeViaParams(userId: String, roomId: String): String {
        return asUrlViaParameters(computeViaParams(userId, roomId, 3))
    }

    fun asUrlViaParameters(viaList: List<String>): String {
        return viaList.joinToString(prefix = "?via=", separator = "&via=") { URLEncoder.encode(it, "utf-8") }
    }

    fun computeViaParams(userId: String, roomId: String, max: Int): List<String> {
        val userHomeserver = userId.getDomain()
        return getUserIdsOfJoinedMembers(roomId)
                .map { it.getDomain() }
                .groupBy { it }
                .mapValues { it.value.size }
                .toMutableMap()
                // Ensure the user homeserver will be included
                .apply { this[userHomeserver] = Int.MAX_VALUE }
                .let { map -> map.keys.sortedByDescending { map[it] } }
                .take(max)
    }

    /**
     * Get a set of userIds of joined members of a room
     */
    private fun getUserIdsOfJoinedMembers(roomId: String): Set<String> {
        return roomGetterProvider.get().getRoom(roomId)
                ?.getRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.JOIN) })
                ?.map { it.userId }
                .orEmpty()
                .toSet()
    }

    fun computeViaParamsForRestricted(roomId: String, max: Int): List<String> {
        val userThatCanInvite = roomGetterProvider.get().getRoom(roomId)
                ?.getRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.JOIN) })
                ?.map { it.userId }
                ?.filter { userCanInvite(userId, roomId) }
                .orEmpty()
                .toSet()

        return userThatCanInvite.map { it.getDomain() }
                .groupBy { it }
                .mapValues { it.value.size }
                .toMutableMap()
                .let { map -> map.keys.sortedByDescending { map[it] } }
                .take(max)
    }

    fun userCanInvite(userId: String, roomId: String): Boolean {
        val powerLevelsHelper = stateEventDataSource.getStateEvent(roomId, EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.NoCondition)
                ?.content?.toModel<PowerLevelsContent>()
                ?.let { PowerLevelsHelper(it) }

        return powerLevelsHelper?.isUserAbleToInvite(userId) ?: false
    }
}

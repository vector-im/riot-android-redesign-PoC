/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.home.room.list

import im.vector.app.features.home.RoomListDisplayMode
import io.reactivex.functions.Predicate
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag

class RoomListDisplayModeFilter(private val displayMode: RoomListDisplayMode) : Predicate<RoomSummary> {

    override fun test(roomSummary: RoomSummary): Boolean {
        if (roomSummary.membership.isLeft()) {
            return false
        }
        return when (displayMode) {
            RoomListDisplayMode.NOTIFICATIONS ->
                roomSummary.notificationCount > 0 || roomSummary.membership == Membership.INVITE || roomSummary.userDrafts.isNotEmpty()
            RoomListDisplayMode.PEOPLE        -> roomSummary.isDirect && roomSummary.membership == Membership.JOIN
            RoomListDisplayMode.ROOMS         -> !roomSummary.isDirect && roomSummary.membership == Membership.JOIN
            RoomListDisplayMode.FILTERED      -> roomSummary.membership == Membership.JOIN
            RoomListDisplayMode.ALL           -> roomSummary.membership.isActive()
            RoomListDisplayMode.FAVORITES     -> roomSummary.membership == Membership.JOIN && roomSummary.tags.any { it.name == RoomTag.ROOM_TAG_FAVOURITE }
            RoomListDisplayMode.LOW_PRIORITY  -> roomSummary.membership == Membership.JOIN && roomSummary.tags.any { it.name == RoomTag.ROOM_TAG_LOW_PRIORITY }
            RoomListDisplayMode.INVITES       -> roomSummary.membership == Membership.INVITE
        }
    }
}

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

import androidx.annotation.StringRes
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.features.home.RoomListDisplayMode
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class RoomListViewState(
        val displayMode: RoomListDisplayMode,
        val asyncRooms: Async<List<RoomSummary>> = Uninitialized,
        val roomFilter: String = "",
        val asyncFilteredRooms: Async<RoomSummaries> = Uninitialized,
        val roomMembershipChanges: Map<String, ChangeMembershipState> = emptyMap(),
        val isInviteExpanded: Boolean = true,
        val isFavouriteRoomsExpanded: Boolean = true,
        val isDirectRoomsExpanded: Boolean = true,
        val isGroupRoomsExpanded: Boolean = true,
        val isLowPriorityRoomsExpanded: Boolean = true,
        val isServerNoticeRoomsExpanded: Boolean = true,
        val isChatRoomsExpanded: Boolean = true,
        val favouriteRoomsMode: CategoryMode = CategoryMode.Grid,
        val directRoomsMode: CategoryMode = CategoryMode.List,
        val groupRoomsMode: CategoryMode = CategoryMode.List,
        val lowPriorityRoomsMode: CategoryMode = CategoryMode.List,
        val serverNoticeRoomsMode: CategoryMode = CategoryMode.List,
        val chatsRoomsMode: CategoryMode = CategoryMode.List
) : MvRxState {

    constructor(args: RoomListParams) : this(displayMode = args.displayMode)

    fun isCategoryExpanded(roomCategory: RoomCategory): Boolean {
        return when (roomCategory) {
            RoomCategory.INVITE        -> isInviteExpanded
            RoomCategory.FAVOURITE     -> isFavouriteRoomsExpanded
            RoomCategory.DIRECT        -> isDirectRoomsExpanded
            RoomCategory.GROUP         -> isGroupRoomsExpanded
            RoomCategory.LOW_PRIORITY  -> isLowPriorityRoomsExpanded
            RoomCategory.SERVER_NOTICE -> isServerNoticeRoomsExpanded
//            else                       -> true
            RoomCategory.CHATS         -> isChatRoomsExpanded
        }
    }

    enum class CategoryMode {
        List,
        Grid
    }

    fun getCategoryMode(roomCategory: RoomCategory): CategoryMode {
        return when (roomCategory) {
            RoomCategory.INVITE        -> CategoryMode.List
            RoomCategory.FAVOURITE     -> CategoryMode.Grid
            RoomCategory.DIRECT        -> directRoomsMode
            RoomCategory.GROUP         -> groupRoomsMode
            RoomCategory.LOW_PRIORITY  -> lowPriorityRoomsMode
            RoomCategory.SERVER_NOTICE -> serverNoticeRoomsMode
            RoomCategory.CHATS         -> chatsRoomsMode
//            else                       -> CategoryMode.List
        }
    }

    fun toggle(roomCategory: RoomCategory): RoomListViewState {
        return when (roomCategory) {
            RoomCategory.INVITE        -> copy(isInviteExpanded = !isInviteExpanded)
            RoomCategory.FAVOURITE     -> copy(isFavouriteRoomsExpanded = !isFavouriteRoomsExpanded)
            RoomCategory.DIRECT        -> copy(isDirectRoomsExpanded = !isDirectRoomsExpanded)
            RoomCategory.GROUP         -> copy(isGroupRoomsExpanded = !isGroupRoomsExpanded)
            RoomCategory.LOW_PRIORITY  -> copy(isLowPriorityRoomsExpanded = !isLowPriorityRoomsExpanded)
            RoomCategory.SERVER_NOTICE -> copy(isServerNoticeRoomsExpanded = !isServerNoticeRoomsExpanded)
            RoomCategory.CHATS         -> copy(isChatRoomsExpanded = !isChatRoomsExpanded)
        }
    }

    fun setMode(roomCategory: RoomCategory, newCategoryMode: CategoryMode): RoomListViewState {
        return when (roomCategory) {
            RoomCategory.INVITE        -> this
            RoomCategory.FAVOURITE     -> copy(favouriteRoomsMode = newCategoryMode)
            RoomCategory.DIRECT        -> copy(directRoomsMode = newCategoryMode)
            RoomCategory.GROUP         -> copy(groupRoomsMode = newCategoryMode)
            RoomCategory.LOW_PRIORITY  -> copy(lowPriorityRoomsMode = newCategoryMode)
            RoomCategory.SERVER_NOTICE -> copy(serverNoticeRoomsMode = newCategoryMode)
            RoomCategory.CHATS         -> copy(chatsRoomsMode = newCategoryMode)
        }
    }

    val hasUnread: Boolean
        get() = asyncFilteredRooms.invoke()
                ?.flatMap { it.value }
                ?.filter { it.membership == Membership.JOIN }
                ?.any { it.hasUnreadMessages }
                ?: false
}

typealias RoomSummaries = LinkedHashMap<RoomCategory, List<RoomSummary>>

enum class RoomCategory(@StringRes val titleRes: Int) {
    INVITE(R.string.invitations_header),
    CHATS(R.string.room_list_tabs_all),
    FAVOURITE(R.string.bottom_action_favourites),
    DIRECT(R.string.bottom_action_people_x),
    GROUP(R.string.bottom_action_rooms),
    LOW_PRIORITY(R.string.low_priority_header),
    SERVER_NOTICE(R.string.system_alerts_header)
}

fun RoomSummaries?.isNullOrEmpty(): Boolean {
    return this == null || this.values.flatten().isEmpty()
}

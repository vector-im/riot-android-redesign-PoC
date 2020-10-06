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

import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.utils.DataSource
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.settings.VectorPreferences
import io.reactivex.schedulers.Schedulers
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.NoOpMatrixCallback
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.rx.rx
import timber.log.Timber
import javax.inject.Inject

class RoomListViewModel @Inject constructor(initialState: RoomListViewState,
                                            private val session: Session,
                                            private val roomSummariesSource: DataSource<List<RoomSummary>>,
                                            private val vectorPreferences: VectorPreferences)
    : VectorViewModel<RoomListViewState, RoomListAction, RoomListViewEvents>(initialState) {

    interface Factory {
        fun create(initialState: RoomListViewState): RoomListViewModel
    }

    companion object : MvRxViewModelFactory<RoomListViewModel, RoomListViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: RoomListViewState): RoomListViewModel? {
            val fragment: RoomListFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.roomListViewModelFactory.create(state)
        }
    }

    private val displayMode = initialState.displayMode
    private val roomListDisplayModeFilter = RoomListDisplayModeFilter(displayMode)

    init {
        observeRoomSummaries()
        observeMembershipChanges()
    }

    override fun handle(action: RoomListAction) {
        when (action) {
            is RoomListAction.SelectRoom                  -> handleSelectRoom(action)
            is RoomListAction.ToggleCategory              -> handleToggleCategory(action)
            is RoomListAction.ChangeCategoryMode          -> handleChangeCategoryMode(action)
            is RoomListAction.AcceptInvitation            -> handleAcceptInvitation(action)
            is RoomListAction.RejectInvitation            -> handleRejectInvitation(action)
            is RoomListAction.FilterWith                  -> handleFilter(action)
            is RoomListAction.MarkAllRoomsRead            -> handleMarkAllRoomsRead()
            is RoomListAction.LeaveRoom                   -> handleLeaveRoom(action)
            is RoomListAction.ChangeRoomNotificationState -> handleChangeNotificationMode(action)
            is RoomListAction.ToggleFavorite              -> handleToggleFavorite(action)
        }.exhaustive
    }

    // PRIVATE METHODS *****************************************************************************

    private fun handleSelectRoom(action: RoomListAction.SelectRoom) = withState {
        _viewEvents.post(RoomListViewEvents.SelectRoom(action.roomSummary))
    }

    private fun handleToggleCategory(action: RoomListAction.ToggleCategory) = setState {
        this.toggle(action.category)
    }

    private fun handleChangeCategoryMode(action: RoomListAction.ChangeCategoryMode) = setState {
        this.setMode(action.category, action.newCategoryMode)
    }

    private fun handleFilter(action: RoomListAction.FilterWith) {
        setState {
            copy(
                    roomFilter = action.filter
            )
        }
    }

    private fun observeRoomSummaries() {
        roomSummariesSource
                .observe()
                .observeOn(Schedulers.computation())
                .execute { asyncRooms ->
                    copy(asyncRooms = asyncRooms)
                }

        roomSummariesSource
                .observe()
                .observeOn(Schedulers.computation())
                .map { buildRoomSummaries(it) }
                .execute { async ->
                    copy(asyncFilteredRooms = async)
                }
    }

    private fun handleAcceptInvitation(action: RoomListAction.AcceptInvitation) = withState { state ->
        val roomId = action.roomSummary.roomId
        val roomMembershipChange = state.roomMembershipChanges[roomId]
        if (roomMembershipChange?.isInProgress().orFalse()) {
            // Request already sent, should not happen
            Timber.w("Try to join an already joining room. Should not happen")
            return@withState
        }

        session.getRoom(roomId)?.join(callback = object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                // We do not update the joiningRoomsIds here, because, the room is not joined yet regarding the sync data.
                // Instead, we wait for the room to be joined
            }

            override fun onFailure(failure: Throwable) {
                // Notify the user
                _viewEvents.post(RoomListViewEvents.Failure(failure))
            }
        })
    }

    private fun handleRejectInvitation(action: RoomListAction.RejectInvitation) = withState { state ->
        val roomId = action.roomSummary.roomId
        val roomMembershipChange = state.roomMembershipChanges[roomId]
        if (roomMembershipChange?.isInProgress().orFalse()) {
            // Request already sent, should not happen
            Timber.w("Try to left an already leaving or joining room. Should not happen")
            return@withState
        }

        session.getRoom(roomId)?.leave(null, object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                // We do not update the rejectingRoomsIds here, because, the room is not rejected yet regarding the sync data.
                // Instead, we wait for the room to be rejected
                // Known bug: if the user is invited again (after rejecting the first invitation), the loading will be displayed instead of the buttons.
                // If we update the state, the button will be displayed again, so it's not ideal...
            }

            override fun onFailure(failure: Throwable) {
                // Notify the user
                _viewEvents.post(RoomListViewEvents.Failure(failure))
            }
        })
    }

    private fun handleMarkAllRoomsRead() = withState { state ->
        state.asyncFilteredRooms.invoke()
                ?.flatMap { it.value }
                ?.filter { it.membership == Membership.JOIN }
                ?.map { it.roomId }
                ?.toList()
                ?.let { session.markAllAsRead(it, NoOpMatrixCallback()) }
    }

    private fun handleChangeNotificationMode(action: RoomListAction.ChangeRoomNotificationState) {
        session.getRoom(action.roomId)?.setRoomNotificationState(action.notificationState, object : MatrixCallback<Unit> {
            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomListViewEvents.Failure(failure))
            }
        })
    }

    private fun handleToggleFavorite(action: RoomListAction.ToggleFavorite) {
        session.getRoom(action.roomId)?.let {
            val callback = object : MatrixCallback<Unit> {
                override fun onFailure(failure: Throwable) {
                    _viewEvents.post(RoomListViewEvents.Failure(failure))
                }
            }
            if (it.roomSummary()?.isFavorite == false) {
                // Set favorite tag. We do not handle the order for the moment
                it.addTag(RoomTag.ROOM_TAG_FAVOURITE, 0.5, callback)
            } else {
                it.deleteTag(RoomTag.ROOM_TAG_FAVOURITE, callback)
            }
        }
    }

    private fun handleLeaveRoom(action: RoomListAction.LeaveRoom) {
        _viewEvents.post(RoomListViewEvents.Loading(null))
        session.getRoom(action.roomId)?.leave(null, object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomListViewEvents.Done)
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomListViewEvents.Failure(failure))
            }
        })
    }

    private fun observeMembershipChanges() {
        session.rx()
                .liveRoomChangeMembershipState()
                .subscribe {
                    Timber.v("ChangeMembership states: $it")
                    setState { copy(roomMembershipChanges = it) }
                }
                .disposeOnClear()
    }

    private fun buildRoomSummaries(rooms: List<RoomSummary>): RoomSummaries {
        val invites = ArrayList<RoomSummary>()
        val others = ArrayList<RoomSummary>(rooms.size)
        val favorite = ArrayList<RoomSummary>()
        val directChats = ArrayList<RoomSummary>(rooms.size)
        val groupRooms = ArrayList<RoomSummary>(rooms.size)
        val lowPriorities = ArrayList<RoomSummary>()
        val serverNotices = ArrayList<RoomSummary>()

        if (vectorPreferences.labUseTabNavigation()) {
            rooms
                    .filter { roomListDisplayModeFilter.test(it) }
                    .forEach { room ->
                        if (room.membership == Membership.INVITE) {
                            invites.add(room)
                        } else {
                            val tags = room.tags.map { it.name }
                            if (vectorPreferences.labPinFavInTabNavigation() && tags.contains(RoomTag.ROOM_TAG_FAVOURITE)) {
                                favorite.add(room)
                            } else others.add(room)
                        }
                    }
            return RoomSummaries().apply {
                if (vectorPreferences.labPinFavInTabNavigation()) {
                    put(RoomCategory.FAVOURITE, favorite)
                }
                put(RoomCategory.INVITE, invites)
                put(RoomCategory.CHATS, others)
            }
        } else {
            rooms
                    .filter { roomListDisplayModeFilter.test(it) }
                    .forEach { room ->
                        val tags = room.tags.map { it.name }

                        if (displayMode == RoomListDisplayMode.ALL) {
                            if (room.membership == Membership.INVITE) {
                                invites.add(room)
                            } else {
                                if (vectorPreferences.labPinFavInTabNavigation() && tags.contains(RoomTag.ROOM_TAG_FAVOURITE)) {
                                    favorite.add(room)
                                } else others.add(room)
                            }
                        } else {
                            when {
                                room.membership == Membership.INVITE          -> invites.add(room)
                                tags.contains(RoomTag.ROOM_TAG_SERVER_NOTICE) -> serverNotices.add(room)
                                tags.contains(RoomTag.ROOM_TAG_FAVOURITE)     -> favorite.add(room)
                                tags.contains(RoomTag.ROOM_TAG_LOW_PRIORITY)  -> lowPriorities.add(room)
                                room.isDirect                                 -> directChats.add(room)
                                else                                          -> groupRooms.add(room)
                            }
                        }
                    }
            return RoomSummaries().apply {
                put(RoomCategory.INVITE, invites)

                if (displayMode == RoomListDisplayMode.ALL) {
                    if (vectorPreferences.labPinFavInTabNavigation())  {
                        put(RoomCategory.FAVOURITE, favorite)
                    }
                    put(RoomCategory.CHATS, others)
                } else {
                    put(RoomCategory.FAVOURITE, favorite)
                    put(RoomCategory.DIRECT, directChats)
                    put(RoomCategory.GROUP, groupRooms)
                    put(RoomCategory.LOW_PRIORITY, lowPriorities)
                    put(RoomCategory.SERVER_NOTICE, serverNotices)
                }
            }
        }
    }
}

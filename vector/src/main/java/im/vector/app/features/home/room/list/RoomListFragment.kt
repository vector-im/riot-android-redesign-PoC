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

import android.os.Bundle
import android.os.Parcelable
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Incomplete
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.epoxy.LayoutManagerStateRestorer
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.home.room.list.actions.RoomListActionsArgs
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsBottomSheet
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedAction
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedActionViewModel
import im.vector.app.features.notifications.NotificationDrawerManager
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.fragment_room_list.*
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import javax.inject.Inject

@Parcelize
data class RoomListParams(
        val displayMode: RoomListDisplayMode
) : Parcelable

class RoomListFragment @Inject constructor(
        private val roomController: RoomSummaryController,
        val roomListViewModelFactory: RoomListViewModel.Factory,
        private val notificationDrawerManager: NotificationDrawerManager,
        private val sharedViewPool: RecyclerView.RecycledViewPool,
        private val dimensionConverter: DimensionConverter
) : VectorBaseFragment(), RoomSummaryController.Listener {

    private var modelBuildListener: OnModelBuildFinishedListener? = null
    private lateinit var sharedActionViewModel: RoomListQuickActionsSharedActionViewModel
    private val roomListParams: RoomListParams by args()
    private val roomListViewModel: RoomListViewModel by fragmentViewModel()
    private lateinit var stateRestorer: LayoutManagerStateRestorer

    override fun getLayoutResId() = R.layout.fragment_room_list

    private var hasUnreadRooms = false

    override fun getMenuRes() = R.menu.room_list

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_home_mark_all_as_read -> {
                roomListViewModel.handle(RoomListAction.MarkAllRoomsRead)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.menu_home_mark_all_as_read).isVisible = hasUnreadRooms
        super.onPrepareOptionsMenu(menu)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        sharedActionViewModel = activityViewModelProvider.get(RoomListQuickActionsSharedActionViewModel::class.java)
        roomListViewModel.observeViewEvents {
            when (it) {
                is RoomListViewEvents.Loading -> showLoading(it.message)
                is RoomListViewEvents.Failure -> showFailure(it.throwable)
                is RoomListViewEvents.SelectRoom -> handleSelectRoom(it)
                is RoomListViewEvents.Done -> Unit
            }.exhaustive
        }
        sharedActionViewModel
                .observe()
                .subscribe { handleQuickActions(it) }
                .disposeOnDestroyView()
    }

    override fun showFailure(throwable: Throwable) {
        showErrorInSnackbar(throwable)
    }

    override fun onDestroyView() {
        roomController.removeModelBuildListener(modelBuildListener)
        modelBuildListener = null
        roomListView.cleanup()
        roomController.listener = null
        stateRestorer.clear()
        super.onDestroyView()
    }

    private fun handleSelectRoom(event: RoomListViewEvents.SelectRoom) {
        navigator.openRoom(requireActivity(), event.roomSummary.roomId)
    }

//    private fun setupCreateRoomButton() {
//        when (roomListParams.displayMode) {
//            RoomListDisplayMode.NOTIFICATIONS -> createChatFabMenu.isVisible = true
//            RoomListDisplayMode.PEOPLE -> createChatRoomButton.isVisible = true
//            RoomListDisplayMode.ROOMS -> createGroupRoomButton.isVisible = true
//            else                              -> Unit // No button in this mode
//        }
//
//        createChatRoomButton.debouncedClicks {
//            createDirectChat()
//        }
//        createGroupRoomButton.debouncedClicks {
//            openRoomDirectory()
//        }
//
//        // Hide FAB when list is scrolling
//        roomListView.addOnScrollListener(
//                object : RecyclerView.OnScrollListener() {
//                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
//                        createChatFabMenu.removeCallbacks(showFabRunnable)
//
//                        when (newState) {
//                            RecyclerView.SCROLL_STATE_IDLE     -> {
//                                createChatFabMenu.postDelayed(showFabRunnable, 250)
//                            }
//                            RecyclerView.SCROLL_STATE_DRAGGING,
//                            RecyclerView.SCROLL_STATE_SETTLING -> {
//                                when (roomListParams.displayMode) {
//                                    RoomListDisplayMode.NOTIFICATIONS -> createChatFabMenu.hide()
//                                    RoomListDisplayMode.PEOPLE        -> createChatRoomButton.hide()
//                                    RoomListDisplayMode.ROOMS         -> createGroupRoomButton.hide()
//                                    else                              -> Unit
//                                }
//                            }
//                        }
//                    }
//                })
//    }

    fun filterRoomsWith(filter: String) {
        // Scroll the list to top
        roomListView.scrollToPosition(0)

        roomListViewModel.handle(RoomListAction.FilterWith(filter))
    }

    private fun setupRecyclerView() {
        val numOfColumns = getNumberOfColumns()
        val layoutManager = GridLayoutManager(context, numOfColumns)
        stateRestorer = LayoutManagerStateRestorer(layoutManager).register()
        roomListView.layoutManager = layoutManager
        roomListView.itemAnimator = RoomListAnimator()
        roomListView.setRecycledViewPool(sharedViewPool)
        layoutManager.recycleChildrenOnDetach = true
        roomController.listener = this
        modelBuildListener = OnModelBuildFinishedListener { it.dispatchTo(stateRestorer) }
        roomController.addModelBuildListener(modelBuildListener)
        roomListView.adapter = roomController.adapter
        stateView.contentView = roomListView

        roomController.spanCount = numOfColumns
        layoutManager.spanSizeLookup = roomController.spanSizeLookup
    }

    private fun getNumberOfColumns(): Int {
        val displayMetrics = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
        return dimensionConverter.pxToDp(displayMetrics.widthPixels) / ITEM_SIZE_DP
    }

//    private val showFabRunnable = Runnable {
//        if (isAdded) {
//            when (roomListParams.displayMode) {
//                RoomListDisplayMode.NOTIFICATIONS -> createChatFabMenu.show()
//                RoomListDisplayMode.PEOPLE -> createChatRoomButton.show()
//                RoomListDisplayMode.ROOMS -> createGroupRoomButton.show()
//                else                              -> Unit
//            }
//        }
//    }

    private fun handleQuickActions(quickAction: RoomListQuickActionsSharedAction) {
        when (quickAction) {
            is RoomListQuickActionsSharedAction.NotificationsAllNoisy -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES_NOISY))
            }
            is RoomListQuickActionsSharedAction.NotificationsAll -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES))
            }
            is RoomListQuickActionsSharedAction.NotificationsMentionsOnly -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MENTIONS_ONLY))
            }
            is RoomListQuickActionsSharedAction.NotificationsMute -> {
                roomListViewModel.handle(RoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MUTE))
            }
            is RoomListQuickActionsSharedAction.Settings -> {
                navigator.openRoomProfile(requireActivity(), quickAction.roomId)
            }
            is RoomListQuickActionsSharedAction.Favorite -> {
                roomListViewModel.handle(RoomListAction.ToggleFavorite(quickAction.roomId))
            }
            is RoomListQuickActionsSharedAction.Leave -> {
                AlertDialog.Builder(requireContext())
                        .setTitle(R.string.room_participants_leave_prompt_title)
                        .setMessage(R.string.room_participants_leave_prompt_msg)
                        .setPositiveButton(R.string.leave) { _, _ ->
                            roomListViewModel.handle(RoomListAction.LeaveRoom(quickAction.roomId))
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                Unit
            }
        }.exhaustive
    }

    override fun invalidate() = withState(roomListViewModel) { state ->
        when (state.asyncFilteredRooms) {
            is Incomplete -> renderLoading()
            is Success -> renderSuccess(state)
            is Fail -> renderFailure(state.asyncFilteredRooms.error)
        }
        roomController.update(state)
        // Mark all as read menu
        when (roomListParams.displayMode) {
            RoomListDisplayMode.ALL,
            RoomListDisplayMode.FAVORITES,
            RoomListDisplayMode.LOW_PRIORITY,
            RoomListDisplayMode.INVITES,
            RoomListDisplayMode.NOTIFICATIONS,
            RoomListDisplayMode.PEOPLE,
            RoomListDisplayMode.ROOMS -> {
                val newValue = state.hasUnread
                if (hasUnreadRooms != newValue) {
                    hasUnreadRooms = newValue
                    invalidateOptionsMenu()
                }
            }
            else                      -> Unit
        }
    }

    private fun renderSuccess(state: RoomListViewState) {
        val allRooms = state.asyncRooms()
        val filteredRooms = state.asyncFilteredRooms()
        if (filteredRooms.isNullOrEmpty()) {
            renderEmptyState(allRooms)
        } else {
            stateView.state = StateView.State.Content
        }
    }

    private fun renderEmptyState(allRooms: List<RoomSummary>?) {
        val hasNoRoom = allRooms
                ?.filter {
                    it.membership == Membership.JOIN || it.membership == Membership.INVITE
                }
                .isNullOrEmpty()
        val emptyState = when (roomListParams.displayMode) {
            RoomListDisplayMode.NOTIFICATIONS -> {
                if (hasNoRoom) {
                    StateView.State.Empty(
                            getString(R.string.room_list_catchup_welcome_title),
                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_home_bottom_catchup),
                            getString(R.string.room_list_catchup_welcome_body)
                    )
                } else {
                    StateView.State.Empty(
                            getString(R.string.room_list_catchup_empty_title),
                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_noun_party_popper),
                            getString(R.string.room_list_catchup_empty_body))
                }
            }
            RoomListDisplayMode.PEOPLE        ->
                StateView.State.Empty(
                        getString(R.string.room_list_people_empty_title),
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_home_bottom_chat),
                        getString(R.string.room_list_people_empty_body)
                )
            RoomListDisplayMode.ROOMS ->
                StateView.State.Empty(
                        getString(R.string.room_list_rooms_empty_title),
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_home_bottom_group),
                        getString(R.string.room_list_rooms_empty_body)
                )
            else                              ->
                // Always display the content in this mode, because if the footer
                StateView.State.Content
        }
        stateView.state = emptyState
    }

    private fun renderLoading() {
        stateView.state = StateView.State.Loading
    }

    private fun renderFailure(error: Throwable) {
        val message = when (error) {
            is Failure.NetworkConnection -> getString(R.string.network_error_please_check_and_retry)
            else                         -> getString(R.string.unknown_error)
        }
        stateView.state = StateView.State.Error(message)
    }

    // RoomSummaryController.Callback **************************************************************

    override fun onRoomClicked(room: RoomSummary) {
        roomListViewModel.handle(RoomListAction.SelectRoom(room))
    }

    override fun onRoomLongClicked(room: RoomSummary): Boolean {
        roomController.onRoomLongClicked()
        RoomListQuickActionsBottomSheet
                .newInstance(room.roomId, RoomListActionsArgs.Mode.FULL)
                .show(childFragmentManager, "ROOM_LIST_QUICK_ACTIONS")
        return true
    }

    override fun onAcceptRoomInvitation(room: RoomSummary) {
        notificationDrawerManager.clearMemberShipNotificationForRoom(room.roomId)
        roomListViewModel.handle(RoomListAction.AcceptInvitation(room))
    }

    override fun onRejectRoomInvitation(room: RoomSummary) {
        notificationDrawerManager.clearMemberShipNotificationForRoom(room.roomId)
        roomListViewModel.handle(RoomListAction.RejectInvitation(room))
    }

    override fun onToggleRoomCategory(roomCategory: RoomCategory) {
        roomListViewModel.handle(RoomListAction.ToggleCategory(roomCategory))
    }

    override fun onChangeModeRoomCategory(roomCategory: RoomCategory, newMode: RoomListViewState.CategoryMode) {
        roomListViewModel.handle(RoomListAction.ChangeCategoryMode(roomCategory, newMode))
    }

    override fun createRoom(initialName: String) {
        navigator.openCreateRoom(requireActivity(), initialName)
    }

    override fun createDirectChat() {
        TODO("Not yet implemented")
    }

    override fun openRoomDirectory(initialFilter: String) {
        TODO("Not yet implemented")
    }

    companion object {
        const val ITEM_SIZE_DP = 80
    }
}

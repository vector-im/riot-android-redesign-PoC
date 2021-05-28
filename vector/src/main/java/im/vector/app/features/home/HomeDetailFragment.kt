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

package im.vector.app.features.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.badge.BadgeDrawable
import im.vector.app.R
import im.vector.app.RoomGroupingMethod
import im.vector.app.core.extensions.commitTransaction
import im.vector.app.core.extensions.toMvRxBundle
import im.vector.app.core.platform.ToolbarConfigurable
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.ui.views.CurrentCallsView
import im.vector.app.core.ui.views.KeysBackupBanner
import im.vector.app.core.ui.views.KnownCallsViewHolder
import im.vector.app.databinding.FragmentHomeDetailBinding
import im.vector.app.features.call.SharedKnownCallsViewModel
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.home.room.list.RoomListFragment
import im.vector.app.features.home.room.list.RoomListParams
import im.vector.app.features.home.room.list.UnreadCounterBadgeView
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.popup.VerificationVectorAlert
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity.Companion.EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY_MANAGE_SESSIONS
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.workers.signout.BannerState
import im.vector.app.features.workers.signout.ServerBackupStatusViewModel
import im.vector.app.features.workers.signout.ServerBackupStatusViewState
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceInfo
import javax.inject.Inject

class HomeDetailFragment @Inject constructor(
        val homeDetailViewModelFactory: HomeDetailViewModel.Factory,
        private val serverBackupStatusViewModelFactory: ServerBackupStatusViewModel.Factory,
        private val avatarRenderer: AvatarRenderer,
        private val alertManager: PopupAlertManager,
        private val callManager: WebRtcCallManager,
        private val vectorPreferences: VectorPreferences
) : VectorBaseFragment<FragmentHomeDetailBinding>(),
        KeysBackupBanner.Delegate,
        CurrentCallsView.Callback,
        ServerBackupStatusViewModel.Factory {

    private val viewModel: HomeDetailViewModel by fragmentViewModel()
    private val unknownDeviceDetectorSharedViewModel: UnknownDeviceDetectorSharedViewModel by activityViewModel()
    private val unreadMessagesSharedViewModel: UnreadMessagesSharedViewModel by activityViewModel()
    private val serverBackupStatusViewModel: ServerBackupStatusViewModel by activityViewModel()

    private lateinit var sharedActionViewModel: HomeSharedActionViewModel
    private lateinit var sharedCallActionViewModel: SharedKnownCallsViewModel

    private var hasUnreadRooms = false
        set(value) {
            if (value != field) {
                field = value
                invalidateOptionsMenu()
            }
        }

    override fun getMenuRes() = R.menu.room_list

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_home_mark_all_as_read -> {
                viewModel.handle(HomeDetailAction.MarkAllRoomsRead)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.menu_home_mark_all_as_read).isVisible = hasUnreadRooms
        super.onPrepareOptionsMenu(menu)
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeDetailBinding {
        return FragmentHomeDetailBinding.inflate(inflater, container, false)
    }

    private val activeCallViewHolder = KnownCallsViewHolder()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedActionViewModel = activityViewModelProvider.get(HomeSharedActionViewModel::class.java)
        sharedCallActionViewModel = activityViewModelProvider.get(SharedKnownCallsViewModel::class.java)

        setupBottomNavigationView()
        setupToolbar()
        setupKeysBackupBanner()
        setupActiveCallView()

        withState(viewModel) {
            // Update the navigation view if needed (for when we restore the tabs)
            views.bottomNavigationView.selectedItemId = it.displayMode.toMenuId()
        }

        viewModel.selectSubscribe(this, HomeDetailViewState::roomGroupingMethod) { roomGroupingMethod ->
            when (roomGroupingMethod) {
                is RoomGroupingMethod.ByLegacyGroup -> {
                    onGroupChange(roomGroupingMethod.groupSummary)
                }
                is RoomGroupingMethod.BySpace -> {
                    onSpaceChange(roomGroupingMethod.spaceSummary)
                }
            }
        }

        viewModel.selectSubscribe(this, HomeDetailViewState::displayMode) { displayMode ->
            switchDisplayMode(displayMode)
        }

        unknownDeviceDetectorSharedViewModel.subscribe { state ->
            state.unknownSessions.invoke()?.let { unknownDevices ->
//                Timber.v("## Detector Triggerred in fragment - ${unknownDevices.firstOrNull()}")
                if (unknownDevices.firstOrNull()?.currentSessionTrust == true) {
                    val uid = "review_login"
                    alertManager.cancelAlert(uid)
                    val olderUnverified = unknownDevices.filter { !it.isNew }
                    val newest = unknownDevices.firstOrNull { it.isNew }?.deviceInfo
                    if (newest != null) {
                        promptForNewUnknownDevices(uid, state, newest)
                    } else if (olderUnverified.isNotEmpty()) {
                        // In this case we prompt to go to settings to review logins
                        promptToReviewChanges(uid, state, olderUnverified.map { it.deviceInfo })
                    }
                }
            }
        }

        unreadMessagesSharedViewModel.subscribe { state ->
            views.drawerUnreadCounterBadgeView.render(
                    UnreadCounterBadgeView.State(
                            count = state.otherSpacesUnread.totalCount,
                            highlighted = state.otherSpacesUnread.isHighlight
                    )
            )
        }

        sharedCallActionViewModel
                .liveKnownCalls
                .observe(viewLifecycleOwner, {
                    activeCallViewHolder.updateCall(callManager.getCurrentCall(), callManager.getCalls())
                    invalidateOptionsMenu()
                })
    }

    override fun onResume() {
        super.onResume()
        // update notification tab if needed
        checkNotificationTabStatus()
    }

    private fun checkNotificationTabStatus() {
        val wasVisible = views.bottomNavigationView.menu.findItem(R.id.bottom_action_notification).isVisible
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_notification).isVisible = vectorPreferences.labAddNotificationTab()
        if (wasVisible && !vectorPreferences.labAddNotificationTab()) {
            // As we hide it check if it's not the current item!
            withState(viewModel) {
                if (it.displayMode.toMenuId() == R.id.bottom_action_notification) {
                    viewModel.handle(HomeDetailAction.SwitchDisplayMode(RoomListDisplayMode.PEOPLE))
                }
            }
        }
    }

    private fun promptForNewUnknownDevices(uid: String, state: UnknownDevicesState, newest: DeviceInfo) {
        val user = state.myMatrixItem
        alertManager.postVectorAlert(
                VerificationVectorAlert(
                        uid = uid,
                        title = getString(R.string.new_session),
                        description = getString(R.string.verify_this_session, newest.displayName ?: newest.deviceId ?: ""),
                        iconId = R.drawable.ic_shield_warning
                ).apply {
                    viewBinder = VerificationVectorAlert.ViewBinder(user, avatarRenderer)
                    colorInt = ContextCompat.getColor(requireActivity(), R.color.riotx_accent)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)
                                ?.navigator
                                ?.requestSessionVerification(requireContext(), newest.deviceId ?: "")
                        unknownDeviceDetectorSharedViewModel.handle(
                                UnknownDeviceDetectorSharedViewModel.Action.IgnoreDevice(newest.deviceId?.let { listOf(it) }.orEmpty())
                        )
                    }
                    dismissedAction = Runnable {
                        unknownDeviceDetectorSharedViewModel.handle(
                                UnknownDeviceDetectorSharedViewModel.Action.IgnoreDevice(newest.deviceId?.let { listOf(it) }.orEmpty())
                        )
                    }
                }
        )
    }

    private fun promptToReviewChanges(uid: String, state: UnknownDevicesState, oldUnverified: List<DeviceInfo>) {
        val user = state.myMatrixItem
        alertManager.postVectorAlert(
                VerificationVectorAlert(
                        uid = uid,
                        title = getString(R.string.review_logins),
                        description = getString(R.string.verify_other_sessions),
                        iconId = R.drawable.ic_shield_warning
                ).apply {
                    viewBinder = VerificationVectorAlert.ViewBinder(user, avatarRenderer)
                    colorInt = ContextCompat.getColor(requireActivity(), R.color.riotx_accent)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)?.let {
                            // mark as ignored to avoid showing it again
                            unknownDeviceDetectorSharedViewModel.handle(
                                    UnknownDeviceDetectorSharedViewModel.Action.IgnoreDevice(oldUnverified.mapNotNull { it.deviceId })
                            )
                            it.navigator.openSettings(it, EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY_MANAGE_SESSIONS)
                        }
                    }
                    dismissedAction = Runnable {
                        unknownDeviceDetectorSharedViewModel.handle(
                                UnknownDeviceDetectorSharedViewModel.Action.IgnoreDevice(oldUnverified.mapNotNull { it.deviceId })
                        )
                    }
                }
        )
    }

    private fun onGroupChange(groupSummary: GroupSummary?) {
        if (groupSummary == null) {
            views.groupToolbarSpaceTitleView.isVisible = false
        } else {
            views.groupToolbarSpaceTitleView.isVisible = true
            views.groupToolbarSpaceTitleView.text = groupSummary.displayName
        }
    }

    private fun onSpaceChange(spaceSummary: RoomSummary?) {
        if (spaceSummary == null) {
            views.groupToolbarSpaceTitleView.isVisible = false
        } else {
            views.groupToolbarSpaceTitleView.isVisible = true
            views.groupToolbarSpaceTitleView.text = spaceSummary.displayName
        }
    }

    private fun setupKeysBackupBanner() {
        serverBackupStatusViewModel
                .subscribe(this) {
                    when (val banState = it.bannerState.invoke()) {
                        is BannerState.Setup -> views.homeKeysBackupBanner.render(KeysBackupBanner.State.Setup(banState.numberOfKeys), false)
                        BannerState.BackingUp -> views.homeKeysBackupBanner.render(KeysBackupBanner.State.BackingUp, false)
                        null,
                        BannerState.Hidden -> views.homeKeysBackupBanner.render(KeysBackupBanner.State.Hidden, false)
                    }
                }
        views.homeKeysBackupBanner.delegate = this
    }

    private fun setupActiveCallView() {
        activeCallViewHolder.bind(
                views.activeCallPiP,
                views.activeCallView,
                views.activeCallPiPWrap,
                this
        )
    }

    private fun setupToolbar() {
        val parentActivity = vectorBaseActivity
        if (parentActivity is ToolbarConfigurable) {
            parentActivity.configure(views.groupToolbar)
        }
        views.groupToolbar.title = ""
        views.groupToolbarAvatarImageView.debouncedClicks {
            sharedActionViewModel.post(HomeActivitySharedAction.OpenDrawer)
        }

        views.homeToolbarContent.debouncedClicks {
            withState(viewModel) {
                when (it.roomGroupingMethod) {
                    is RoomGroupingMethod.ByLegacyGroup -> {
                        // nothing do far
                    }
                    is RoomGroupingMethod.BySpace -> {
                        it.roomGroupingMethod.spaceSummary?.let {
                            sharedActionViewModel.post(HomeActivitySharedAction.ShowSpaceSettings(it.roomId))
                        }
                    }
                }
            }
        }
    }

    private fun setupBottomNavigationView() {
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_notification).isVisible = vectorPreferences.labAddNotificationTab()
        views.bottomNavigationView.setOnNavigationItemSelectedListener {
            val displayMode = when (it.itemId) {
                R.id.bottom_action_people -> RoomListDisplayMode.PEOPLE
                R.id.bottom_action_rooms -> RoomListDisplayMode.ROOMS
                else                      -> RoomListDisplayMode.NOTIFICATIONS
            }
            viewModel.handle(HomeDetailAction.SwitchDisplayMode(displayMode))
            true
        }

//        val menuView = bottomNavigationView.getChildAt(0) as BottomNavigationMenuView

//        bottomNavigationView.getOrCreateBadge()
//        menuView.forEachIndexed { index, view ->
//            val itemView = view as BottomNavigationItemView
//            val badgeLayout = LayoutInflater.from(requireContext()).inflate(R.layout.vector_home_badge_unread_layout, menuView, false)
//            val unreadCounterBadgeView: UnreadCounterBadgeView = badgeLayout.findViewById(R.id.actionUnreadCounterBadgeView)
//            itemView.addView(badgeLayout)
//            unreadCounterBadgeViews.add(index, unreadCounterBadgeView)
//        }
    }

    private fun switchDisplayMode(displayMode: RoomListDisplayMode) {
        views.groupToolbarTitleView.setText(displayMode.titleRes)
        updateSelectedFragment(displayMode)
    }

    private fun updateSelectedFragment(displayMode: RoomListDisplayMode) {
        val fragmentTag = "FRAGMENT_TAG_${displayMode.name}"
        val fragmentToShow = childFragmentManager.findFragmentByTag(fragmentTag)
        childFragmentManager.commitTransaction {
            childFragmentManager.fragments
                    .filter { it != fragmentToShow }
                    .forEach {
                        detach(it)
                    }
            if (fragmentToShow == null) {
                val params = RoomListParams(displayMode)
                add(R.id.roomListContainer, RoomListFragment::class.java, params.toMvRxBundle(), fragmentTag)
            } else {
                attach(fragmentToShow)
            }
        }
    }

    /* ==========================================================================================
     * KeysBackupBanner Listener
     * ========================================================================================== */

    override fun setupKeysBackup() {
        navigator.openKeysBackupSetup(requireActivity(), false)
    }

    override fun recoverKeysBackup() {
        navigator.openKeysBackupManager(requireActivity())
    }

    override fun invalidate() = withState(viewModel) {
//        Timber.v(it.toString())
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_people).render(it.notificationCountPeople, it.notificationHighlightPeople)
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_rooms).render(it.notificationCountRooms, it.notificationHighlightRooms)
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_notification).render(it.notificationCountCatchup, it.notificationHighlightCatchup)
        views.syncStateView.render(it.syncState)

        hasUnreadRooms = it.hasUnreadMessages
    }

    private fun BadgeDrawable.render(count: Int, highlight: Boolean) {
        isVisible = count > 0
        number = count
        maxCharacterCount = 3
        badgeTextColor = ContextCompat.getColor(requireContext(), R.color.white)
        backgroundColor = if (highlight) {
            ContextCompat.getColor(requireContext(), R.color.riotx_notice)
        } else {
            ThemeUtils.getColor(requireContext(), R.attr.riotx_unread_room_badge)
        }
    }

    private fun RoomListDisplayMode.toMenuId() = when (this) {
        RoomListDisplayMode.PEOPLE -> R.id.bottom_action_people
        RoomListDisplayMode.ROOMS -> R.id.bottom_action_rooms
        else                       -> R.id.bottom_action_notification
    }

    override fun onTapToReturnToCall() {
        callManager.getCurrentCall()?.let { call ->
            VectorCallActivity.newIntent(
                    context = requireContext(),
                    callId = call.callId,
                    signalingRoomId = call.signalingRoomId,
                    otherUserId = call.mxCall.opponentUserId,
                    isIncomingCall = !call.mxCall.isOutgoing,
                    isVideoCall = call.mxCall.isVideoCall,
                    mode = null
            ).let {
                startActivity(it)
            }
        }
    }

    override fun create(initialState: ServerBackupStatusViewState): ServerBackupStatusViewModel {
        return serverBackupStatusViewModelFactory.create(initialState)
    }
}

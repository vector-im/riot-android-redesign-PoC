/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.home.room.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.airbnb.mvrx.viewModel
import im.vector.app.R
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.replaceFragment
import im.vector.app.core.platform.ToolbarConfigurable
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityRoomDetailBinding
import im.vector.app.features.home.room.breadcrumbs.BreadcrumbsFragment
import im.vector.app.features.room.RequireActiveMembershipAction
import im.vector.app.features.room.RequireActiveMembershipViewEvents
import im.vector.app.features.room.RequireActiveMembershipViewModel
import im.vector.app.features.room.RequireActiveMembershipViewState
import im.vector.app.features.widgets.permissions.RoomWidgetPermissionViewModel
import im.vector.app.features.widgets.permissions.RoomWidgetPermissionViewState

import javax.inject.Inject

class RoomDetailActivity :
        VectorBaseActivity<ActivityRoomDetailBinding>(),
        ToolbarConfigurable,
        RequireActiveMembershipViewModel.Factory,
        RoomWidgetPermissionViewModel.Factory {

    override fun getBinding(): ActivityRoomDetailBinding {
        return ActivityRoomDetailBinding.inflate(layoutInflater)
    }

    override fun getCoordinatorLayout() = views.coordinatorLayout

    private lateinit var sharedActionViewModel: RoomDetailSharedActionViewModel
    private val requireActiveMembershipViewModel: RequireActiveMembershipViewModel by viewModel()

    @Inject
    lateinit var requireActiveMembershipViewModelFactory: RequireActiveMembershipViewModel.Factory

    override fun create(initialState: RequireActiveMembershipViewState): RequireActiveMembershipViewModel {
        // Due to shortcut, we cannot use MvRx args. Pass the first roomId here
        return requireActiveMembershipViewModelFactory.create(initialState.copy(roomId = currentRoomId ?: ""))
    }

    @Inject
    lateinit var permissionsViewModelFactory: RoomWidgetPermissionViewModel.Factory
    override fun create(initialState: RoomWidgetPermissionViewState): RoomWidgetPermissionViewModel {
        return permissionsViewModelFactory.create(initialState)
    }

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    // Simple filter
    var currentRoomId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingView = views.waitingView.waitingView
        val roomDetailArgs: RoomDetailArgs? = if (intent?.action == ACTION_ROOM_DETAILS_FROM_SHORTCUT) {
            RoomDetailArgs(roomId = intent?.extras?.getString(EXTRA_ROOM_ID)!!)
        } else {
            intent?.extras?.getParcelable(EXTRA_ROOM_DETAIL_ARGS)
        }
        if (roomDetailArgs == null) return
        currentRoomId = roomDetailArgs.roomId

        if (isFirstCreation()) {
            replaceFragment(R.id.roomDetailContainer, RoomDetailFragment::class.java, roomDetailArgs)
            replaceFragment(R.id.roomDetailDrawerContainer, BreadcrumbsFragment::class.java)
        }

        sharedActionViewModel = viewModelProvider.get(RoomDetailSharedActionViewModel::class.java)

        sharedActionViewModel
                .observe()
                .subscribe { sharedAction ->
                    when (sharedAction) {
                        is RoomDetailSharedAction.SwitchToRoom -> switchToRoom(sharedAction)
                    }
                }
                .disposeOnDestroy()

        requireActiveMembershipViewModel.observeViewEvents {
            when (it) {
                is RequireActiveMembershipViewEvents.RoomLeft -> handleRoomLeft(it)
            }
        }
        views.drawerLayout.addDrawerListener(drawerListener)
    }

    private fun handleRoomLeft(roomLeft: RequireActiveMembershipViewEvents.RoomLeft) {
        if (roomLeft.leftMessage != null) {
            Toast.makeText(this, roomLeft.leftMessage, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun switchToRoom(switchToRoom: RoomDetailSharedAction.SwitchToRoom) {
        views.drawerLayout.closeDrawer(GravityCompat.START)
        // Do not replace the Fragment if it's the same roomId
        if (currentRoomId != switchToRoom.roomId) {
            currentRoomId = switchToRoom.roomId
            requireActiveMembershipViewModel.handle(RequireActiveMembershipAction.ChangeRoom(switchToRoom.roomId))
            replaceFragment(R.id.roomDetailContainer, RoomDetailFragment::class.java, RoomDetailArgs(switchToRoom.roomId))
        }
    }

    override fun onDestroy() {
        views.drawerLayout.removeDrawerListener(drawerListener)
        super.onDestroy()
    }

    override fun configure(toolbar: MaterialToolbar) {
        configureToolbar(toolbar)
    }

    private val drawerListener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerStateChanged(newState: Int) {
            hideKeyboard()

            if (!views.drawerLayout.isDrawerOpen(GravityCompat.START) && newState == DrawerLayout.STATE_DRAGGING) {
                // User is starting to open the drawer, scroll the list to top
                scrollBreadcrumbsToTop()
            }
        }
    }

    private fun scrollBreadcrumbsToTop() {
        supportFragmentManager.fragments.filterIsInstance<BreadcrumbsFragment>()
                .forEach { it.scrollToTop() }
    }

    override fun onBackPressed() {
        if (views.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            views.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    companion object {

        const val EXTRA_ROOM_DETAIL_ARGS = "EXTRA_ROOM_DETAIL_ARGS"
        const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        const val ACTION_ROOM_DETAILS_FROM_SHORTCUT = "ROOM_DETAILS_FROM_SHORTCUT"

        fun newIntent(context: Context, roomDetailArgs: RoomDetailArgs): Intent {
            return Intent(context, RoomDetailActivity::class.java).apply {
                putExtra(EXTRA_ROOM_DETAIL_ARGS, roomDetailArgs)
            }
        }

        // Shortcuts can't have intents with parcelables
        fun shortcutIntent(context: Context, roomId: String): Intent {
            return Intent(context, RoomDetailActivity::class.java).apply {
                action = ACTION_ROOM_DETAILS_FROM_SHORTCUT
                putExtra(EXTRA_ROOM_ID, roomId)
            }
        }
    }
}

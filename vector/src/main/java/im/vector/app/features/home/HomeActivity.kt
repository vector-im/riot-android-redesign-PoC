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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.viewModel
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.replaceFragment
import im.vector.app.core.platform.ToolbarConfigurable
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.features.disclaimer.showDisclaimerDialog
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.popup.DefaultVectorAlert
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.popup.VerificationVectorAlert
import im.vector.app.features.rageshake.VectorUncaughtExceptionHandler
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.workers.signout.ServerBackupStatusViewModel
import im.vector.app.features.workers.signout.ServerBackupStatusViewState
import im.vector.app.push.fcm.FcmHelper
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.activity_home.*
import kotlinx.android.synthetic.main.merge_overlay_waiting_view.*
import org.matrix.android.sdk.api.session.InitialSyncProgressService
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class HomeActivityArgs(
        val clearNotification: Boolean,
        val accountCreation: Boolean
) : Parcelable

class HomeActivity : VectorBaseActivity(), ToolbarConfigurable, UnknownDeviceDetectorSharedViewModel.Factory, ServerBackupStatusViewModel.Factory {

    private lateinit var sharedActionViewModel: HomeSharedActionViewModel

    private val homeActivityViewModel: HomeActivityViewModel by viewModel()
    @Inject lateinit var viewModelFactory: HomeActivityViewModel.Factory

    private val serverBackupStatusViewModel: ServerBackupStatusViewModel by viewModel()
    @Inject lateinit var serverBackupviewModelFactory: ServerBackupStatusViewModel.Factory

    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var vectorUncaughtExceptionHandler: VectorUncaughtExceptionHandler
    @Inject lateinit var pushManager: PushersManager
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var popupAlertManager: PopupAlertManager
    @Inject lateinit var shortcutsHandler: ShortcutsHandler
    @Inject lateinit var unknownDeviceViewModelFactory: UnknownDeviceDetectorSharedViewModel.Factory
    @Inject lateinit var analyticsEngine: AnalyticsEngine

    private val drawerListener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerStateChanged(newState: Int) {
            hideKeyboard()
        }
    }

    override fun getLayoutRes() = R.layout.activity_home

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    override fun create(initialState: UnknownDevicesState): UnknownDeviceDetectorSharedViewModel {
        return unknownDeviceViewModelFactory.create(initialState)
    }

    override fun create(initialState: ServerBackupStatusViewState): ServerBackupStatusViewModel {
        return serverBackupviewModelFactory.create(initialState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FcmHelper.ensureFcmTokenIsRetrieved(this, pushManager, vectorPreferences.areNotificationEnabledForDevice())
        sharedActionViewModel = viewModelProvider.get(HomeSharedActionViewModel::class.java)
        drawerLayout.addDrawerListener(drawerListener)
        if (isFirstCreation()) {
            replaceFragment(R.id.homeDetailFragmentContainer, LoadingFragment::class.java)
            replaceFragment(R.id.homeDrawerFragmentContainer, HomeDrawerFragment::class.java)
        }

        sharedActionViewModel
                .observe()
                .subscribe { sharedAction ->
                    when (sharedAction) {
                        is HomeActivitySharedAction.OpenDrawer  -> drawerLayout.openDrawer(GravityCompat.START)
                        is HomeActivitySharedAction.CloseDrawer -> drawerLayout.closeDrawer(GravityCompat.START)
                        is HomeActivitySharedAction.OpenGroup   -> {
                            drawerLayout.closeDrawer(GravityCompat.START)
                            replaceFragment(R.id.homeDetailFragmentContainer, HomeDetailFragment::class.java, allowStateLoss = true)
                        }
                    }.exhaustive
                }
                .disposeOnDestroy()

        val args = intent.getParcelableExtra<HomeActivityArgs>(MvRx.KEY_ARG)

        if (args?.clearNotification == true) {
            notificationDrawerManager.clearAllEvents()
        }

        homeActivityViewModel.observeViewEvents {
            when (it) {
                is HomeActivityViewEvents.AskPasswordToInitCrossSigning -> handleAskPasswordToInitCrossSigning(it)
                is HomeActivityViewEvents.OnNewSession                  -> handleOnNewSession(it)
                HomeActivityViewEvents.PromptToEnableSessionPush        -> handlePromptToEnablePush()
                is HomeActivityViewEvents.OnCrossSignedInvalidated      -> handleCrossSigningInvalidated(it)
            }.exhaustive
        }
        homeActivityViewModel.subscribe(this) { renderState(it) }

        shortcutsHandler.observeRoomsAndBuildShortcuts()
                .disposeOnDestroy()
    }

    private fun renderState(state: HomeActivityViewState) {
        when (val status = state.initialSyncProgressServiceStatus) {
            is InitialSyncProgressService.Status.Idle        -> {
                waiting_view.isVisible = false
            }
            is InitialSyncProgressService.Status.Progressing -> {
                Timber.v("${getString(status.statusText)} ${status.percentProgress}")
                waiting_view.setOnClickListener {
                    // block interactions
                }
                waiting_view_status_horizontal_progress.apply {
                    isIndeterminate = false
                    max = 100
                    progress = status.percentProgress
                    isVisible = true
                }
                waiting_view_status_text.apply {
                    text = getString(status.statusText)
                    isVisible = true
                }
                waiting_view.isVisible = true
            }
        }.exhaustive
    }

    private fun handleAskPasswordToInitCrossSigning(events: HomeActivityViewEvents.AskPasswordToInitCrossSigning) {
        // We need to ask
        promptSecurityEvent(
                events.userItem,
                R.string.upgrade_security,
                R.string.security_prompt_text
        ) {
            it.navigator.upgradeSessionSecurity(it, true)
        }
    }

    private fun handleCrossSigningInvalidated(event: HomeActivityViewEvents.OnCrossSignedInvalidated) {
        // We need to ask
        promptSecurityEvent(
                event.userItem,
                R.string.crosssigning_verify_this_session,
                R.string.confirm_your_identity
        ) {
            it.navigator.waitSessionVerification(it)
        }
    }

    private fun handleOnNewSession(event: HomeActivityViewEvents.OnNewSession) {
        // We need to ask
        promptSecurityEvent(
                event.userItem,
                R.string.crosssigning_verify_this_session,
                R.string.confirm_your_identity
        ) {
            if (event.waitForIncomingRequest) {
                it.navigator.waitSessionVerification(it)
            } else {
                it.navigator.requestSelfSessionVerification(it)
            }
        }
    }

    private fun handlePromptToEnablePush() {
        popupAlertManager.postVectorAlert(
                DefaultVectorAlert(
                        uid = "enablePush",
                        title = getString(R.string.alert_push_are_disabled_title),
                        description = getString(R.string.alert_push_are_disabled_description),
                        iconId = R.drawable.ic_room_actions_notifications_mutes,
                        shouldBeDisplayedIn = {
                            it is HomeActivity
                        }
                ).apply {
                    colorInt = ThemeUtils.getColor(this@HomeActivity, R.attr.vctr_notice_secondary)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity)?.let {
                            // action(it)
                            homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                            it.navigator.openSettings(it, VectorSettingsActivity.EXTRA_DIRECT_ACCESS_NOTIFICATIONS)
                        }
                    }
                    dismissedAction = Runnable {
                        homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                    }
                    addButton(getString(R.string.dismiss), Runnable {
                        homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                    }, true)
                    addButton(getString(R.string.settings), Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity)?.let {
                            // action(it)
                            homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                            it.navigator.openSettings(it, VectorSettingsActivity.EXTRA_DIRECT_ACCESS_NOTIFICATIONS)
                        }
                    }, true)
                }
        )
    }

    private fun promptSecurityEvent(userItem: MatrixItem.UserItem?, titleRes: Int, descRes: Int, action: ((VectorBaseActivity) -> Unit)) {
        popupAlertManager.postVectorAlert(
                VerificationVectorAlert(
                        uid = "upgradeSecurity",
                        title = getString(titleRes),
                        description = getString(descRes),
                        iconId = R.drawable.ic_shield_warning,
                        matrixItem = userItem
                ).apply {
                    colorInt = ContextCompat.getColor(this@HomeActivity, R.color.riotx_positive_accent)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity)?.let {
                            action(it)
                        }
                    }
                    dismissedAction = Runnable {}
                }
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getParcelableExtra<HomeActivityArgs>(MvRx.KEY_ARG)?.clearNotification == true) {
            notificationDrawerManager.clearAllEvents()
        }
    }

    override fun onDestroy() {
        drawerLayout.removeDrawerListener(drawerListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        if (vectorUncaughtExceptionHandler.didAppCrash(this)) {
            vectorUncaughtExceptionHandler.clearAppCrashStatus(this)

            AlertDialog.Builder(this)
                    .setMessage(R.string.send_bug_report_app_crashed)
                    .setCancelable(false)
                    .setPositiveButton(R.string.yes) { _, _ -> bugReporter.openBugReportScreen(this) }
                    .setNegativeButton(R.string.no) { _, _ -> bugReporter.deleteCrashFile(this) }
                    .show()
        } else {
            showDisclaimerDialog(this)
        }

        // Force remote backup state update to update the banner if needed
        serverBackupStatusViewModel.refreshRemoteStateIfNeeded()
    }

    override fun configure(toolbar: Toolbar) {
        configureToolbar(toolbar, false)
    }

    override fun getMenuRes() = R.menu.home

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_home_suggestion -> {
                if (analyticsEngine.isEnabled()) {
                    analyticsEngine.report(
                            AnalyticsEngine.AnalyticEvent.GetFeedBacks(
                                    "5f987e2370c8d359e50687eb",
                                    getString(R.string.action_close),
                                    this
                            )
                    )
                } else {
                    bugReporter.openBugReportScreen(this, true)
                }
                return true
            }
            R.id.menu_home_report_bug -> {
                bugReporter.openBugReportScreen(this, false)
                return true
            }
            R.id.menu_home_filter     -> {
                navigator.openRoomsFiltering(this)
                return true
            }
            R.id.menu_home_setting    -> {
                navigator.openSettings(this)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        fun newIntent(context: Context, clearNotification: Boolean = false, accountCreation: Boolean = false): Intent {
            val args = HomeActivityArgs(
                    clearNotification = clearNotification,
                    accountCreation = accountCreation
            )

            return Intent(context, HomeActivity::class.java)
                    .apply {
                        putExtra(MvRx.KEY_ARG, args)
                    }
        }
    }
}

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

package im.vector.app.features

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.startSyncing
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.deleteAllFiles
import im.vector.app.databinding.ActivityMainBinding
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.ShortcutsHandler
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.pin.PinCodeStore
import im.vector.app.features.pin.PinLocker
import im.vector.app.features.pin.UnlockedActivity
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.signout.hard.SignedOutActivity
import im.vector.app.features.signout.soft.SoftLogoutActivity
import im.vector.app.features.signout.soft.SoftLogoutActivity2
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.ui.UiStateRepository
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.failure.GlobalError
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class MainActivityArgs(
        val clearCache: Boolean = false,
        val clearCredentials: Boolean = false,
        val isUserLoggedOut: Boolean = false,
        val isAccountDeactivated: Boolean = false,
        val isSoftLogout: Boolean = false
) : Parcelable

/**
 * This is the entry point of Element Android
 * This Activity, when started with argument, is also doing some cleanup when user signs out,
 * clears cache, is logged out, or is soft logged out
 */
class MainActivity : VectorBaseActivity<ActivityMainBinding>(), UnlockedActivity {

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"

        // Special action to clear cache and/or clear credentials
        fun restartApp(activity: Activity, args: MainActivityArgs) {
            val intent = Intent(activity, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

            intent.putExtra(EXTRA_ARGS, args)
            activity.startActivity(intent)
        }
    }

    override fun getBinding() = ActivityMainBinding.inflate(layoutInflater)

    override fun getOtherThemes() = ActivityOtherThemes.Launcher

    private lateinit var args: MainActivityArgs

    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var sessionHolder: ActiveSessionHolder
    @Inject lateinit var errorFormatter: ErrorFormatter
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var uiStateRepository: UiStateRepository
    @Inject lateinit var shortcutsHandler: ShortcutsHandler
    @Inject lateinit var pinCodeStore: PinCodeStore
    @Inject lateinit var pinLocker: PinLocker
    @Inject lateinit var popupAlertManager: PopupAlertManager

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        args = parseArgs()
        if (args.clearCredentials || args.isUserLoggedOut || args.clearCache) {
            clearNotifications()
        }
        // Handle some wanted cleanup
        if (args.clearCache || args.clearCredentials) {
            doCleanUp()
        } else {
            startNextActivityAndFinish()
        }
    }

    private fun clearNotifications() {
        // Dismiss all notifications
        notificationDrawerManager.clearAllEvents()
        notificationDrawerManager.persistInfo()

        // Also clear the dynamic shortcuts
        shortcutsHandler.clearShortcuts()

        // Also clear the alerts
        popupAlertManager.cancelAll()
    }

    private fun parseArgs(): MainActivityArgs {
        val argsFromIntent: MainActivityArgs? = intent.getParcelableExtra(EXTRA_ARGS)
        Timber.w("Starting MainActivity with $argsFromIntent")

        return MainActivityArgs(
                clearCache = argsFromIntent?.clearCache ?: false,
                clearCredentials = argsFromIntent?.clearCredentials ?: false,
                isUserLoggedOut = argsFromIntent?.isUserLoggedOut ?: false,
                isAccountDeactivated = argsFromIntent?.isAccountDeactivated ?: false,
                isSoftLogout = argsFromIntent?.isSoftLogout ?: false
        )
    }

    private fun doCleanUp() {
        val session = sessionHolder.getSafeActiveSession()
        if (session == null) {
            startNextActivityAndFinish()
            return
        }
        when {
            args.isAccountDeactivated -> {
                lifecycleScope.launch {
                    // Just do the local cleanup
                    Timber.w("Account deactivated, start app")
                    sessionHolder.clearActiveSession()
                    doLocalCleanup(clearPreferences = true)
                    startNextActivityAndFinish()
                }
            }
            args.clearCredentials     -> {
                lifecycleScope.launch {
                    try {
                        session.signOut(!args.isUserLoggedOut)
                    } catch (failure: Throwable) {
                        displayError(failure)
                        return@launch
                    }
                    Timber.w("SIGN_OUT: success, start app")
                    sessionHolder.clearActiveSession()
                    doLocalCleanup(clearPreferences = true)
                    startNextActivityAndFinish()
                }
            }
            args.clearCache           -> {
                lifecycleScope.launch {
                    session.clearCache()
                    doLocalCleanup(clearPreferences = false)
                    session.startSyncing(applicationContext)
                    startNextActivityAndFinish()
                }
            }
        }
    }

    override fun handleInvalidToken(globalError: GlobalError.InvalidToken) {
        // No op here
        Timber.w("Ignoring invalid token global error")
    }

    private suspend fun doLocalCleanup(clearPreferences: Boolean) {
        // On UI Thread
        Glide.get(this@MainActivity).clearMemory()

        if (clearPreferences) {
            vectorPreferences.clearPreferences()
            uiStateRepository.reset()
            pinLocker.unlock()
            pinCodeStore.deleteEncodedPin()
        }
        withContext(Dispatchers.IO) {
            // On BG thread
            Glide.get(this@MainActivity).clearDiskCache()

            // Also clear cache (Logs, etc...)
            deleteAllFiles(this@MainActivity.cacheDir)
        }
    }

    private fun displayError(failure: Throwable) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title_error)
                    .setMessage(errorFormatter.toHumanReadable(failure))
                    .setPositiveButton(R.string.global_retry) { _, _ -> doCleanUp() }
                    .setNegativeButton(R.string.cancel) { _, _ -> startNextActivityAndFinish(ignoreClearCredentials = true) }
                    .setCancelable(false)
                    .show()
        }
    }

    private fun startNextActivityAndFinish(ignoreClearCredentials: Boolean = false) {
        val intent = when {
            args.clearCredentials
                    && !ignoreClearCredentials
                    && (!args.isUserLoggedOut || args.isAccountDeactivated) -> {
                // User has explicitly asked to log out or deactivated his account
                navigator.openLogin(this, null)
                null
            }
            args.isSoftLogout                                               ->
                // The homeserver has invalidated the token, with a soft logout
                getSoftLogoutActivityIntent()
            args.isUserLoggedOut                                            ->
                // the homeserver has invalidated the token (password changed, device deleted, other security reasons)
                SignedOutActivity.newIntent(this)
            sessionHolder.hasActiveSession()                                ->
                // We have a session.
                // Check it can be opened
                if (sessionHolder.getActiveSession().isOpenable) {
                    HomeActivity.newIntent(this)
                } else {
                    // The token is still invalid
                    getSoftLogoutActivityIntent()
                }
            else                                                            -> {
                // First start, or no active session
                navigator.openLogin(this, null)
                null
            }
        }
        intent?.let { startActivity(it) }
        finish()
    }

    private fun getSoftLogoutActivityIntent(): Intent {
        return if (resources.getBoolean(R.bool.useLoginV2)) {
            SoftLogoutActivity2.newIntent(this)
        } else {
            SoftLogoutActivity.newIntent(this)
        }
    }
}

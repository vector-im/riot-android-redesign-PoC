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

package im.vector.app.features.link

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.utils.toast
import im.vector.app.databinding.ActivityProgressBinding
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.permalink.PermalinkHandler
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Dummy activity used to dispatch the vector URL links.
 */
class LinkHandlerActivity : VectorBaseActivity<ActivityProgressBinding>() {

    @Inject lateinit var sessionHolder: ActiveSessionHolder
    @Inject lateinit var errorFormatter: ErrorFormatter
    @Inject lateinit var permalinkHandler: PermalinkHandler

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    override fun getBinding() = ActivityProgressBinding.inflate(layoutInflater)

    override fun initUiAndData() {
        val uri = intent.data

        if (uri == null) {
            // Should not happen
            Timber.w("Uri is null")
            finish()
            return
        }

        if (uri.getQueryParameter(LoginConfig.CONFIG_HS_PARAMETER) != null) {
            handleConfigUrl(uri)
        } else if (SUPPORTED_HOSTS.contains(uri.host)) {
            handleSupportedHostUrl(uri)
        } else {
            // Other links are not yet handled, but should not come here (manifest configuration error?)
            toast(R.string.universal_link_malformed)
            finish()
        }
    }

    private fun handleConfigUrl(uri: Uri) {
        if (sessionHolder.hasActiveSession()) {
            displayAlreadyLoginPopup(uri)
        } else {
            // user is not yet logged in, this is the nominal case
            startLoginActivity(uri)
        }
    }

    private fun handleSupportedHostUrl(uri: Uri) {
        if (!sessionHolder.hasActiveSession()) {
            startLoginActivity(uri)
            finish()
        } else {
            convertUriToPermalink(uri)?.let { permalink ->
                startPermalinkHandler(permalink)
            } ?: run {
                // Host is correct but we do not recognize path
                Timber.w("Unable to handle this uri: $uri")
                finish()
            }
        }
    }

    /**
     * Convert a URL of element web instance to a matrix.to url
     * Examples:
     * - https://riot.im/develop/#/room/#element-android:matrix.org ->  https://matrix.to/#/#element-android:matrix.org
     * - https://app.element.io/#/room/#element-android:matrix.org  ->  https://matrix.to/#/#element-android:matrix.org
     */
    private fun convertUriToPermalink(uri: Uri): String? {
        val uriString = uri.toString()
        val path = SUPPORTED_PATHS.find { it in uriString } ?: return null
        return PermalinkService.MATRIX_TO_URL_BASE + uriString.substringAfter(path)
    }

    private fun startPermalinkHandler(permalink: String) {
        permalinkHandler.launch(this, permalink, buildTask = true)
                .delay(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { isHandled ->
                    if (!isHandled) {
                        toast(R.string.universal_link_malformed)
                    }
                    finish()
                }
                .disposeOnDestroy()
    }

    /**
     * Start the login screen with identity server and home server pre-filled
     */
    private fun startLoginActivity(uri: Uri) {
        navigator.openLogin(
                context = this,
                loginConfig = LoginConfig.parse(uri),
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        )
        finish()
    }

    /**
     * Propose to disconnect from a previous HS, when clicking on an auto config link
     */
    private fun displayAlreadyLoginPopup(uri: Uri) {
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_warning)
                .setMessage(R.string.error_user_already_logged_in)
                .setCancelable(false)
                .setPositiveButton(R.string.logout) { _, _ -> safeSignout(uri) }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
    }

    private fun safeSignout(uri: Uri) {
        val session = sessionHolder.getSafeActiveSession()
        if (session == null) {
            // Should not happen
            startLoginActivity(uri)
        } else {
            lifecycleScope.launch {
                try {
                    session.signOut(true)
                    Timber.d("## displayAlreadyLoginPopup(): logout succeeded")
                    sessionHolder.clearActiveSession()
                    startLoginActivity(uri)
                } catch (failure: Throwable) {
                    displayError(failure)
                }
            }
        }
    }

    private fun displayError(failure: Throwable) {
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_error)
                .setMessage(errorFormatter.toHumanReadable(failure))
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .show()
    }

    companion object {
        private val SUPPORTED_HOSTS = listOf(
                // Regular Element Web instance
                "app.element.io",
                // Other known instances of Element Web
                "develop.element.io",
                "staging.element.io",
                // Previous Web instance, kept for compatibility reason
                "riot.im"
        )
        private val SUPPORTED_PATHS = listOf(
                "/#/room/",
                "/#/user/",
                "/#/group/"
        )
    }
}

/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.login2

import android.content.ComponentName
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.viewbinding.ViewBinding
import com.airbnb.mvrx.withState
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.features.login.hasSso
import im.vector.app.features.login.ssoIdentityProviders

abstract class AbstractSSOLoginFragment2<VB: ViewBinding> : AbstractLoginFragment2<VB>() {

    // For sso
    private var customTabsServiceConnection: CustomTabsServiceConnection? = null
    private var customTabsClient: CustomTabsClient? = null
    private var customTabsSession: CustomTabsSession? = null

    override fun onStart() {
        super.onStart()
        val hasSSO = withState(loginViewModel) { it.loginMode.hasSso() }
        if (hasSSO) {
            val packageName = CustomTabsClient.getPackageName(requireContext(), null)

            // packageName can be null if there are 0 or several CustomTabs compatible browsers installed on the device
            if (packageName != null) {
                customTabsServiceConnection = object : CustomTabsServiceConnection() {
                    override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                        customTabsClient = client
                                .also { it.warmup(0L) }
                        prefetchIfNeeded()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                    }
                }
                        .also {
                            CustomTabsClient.bindCustomTabsService(
                                    requireContext(),
                                    // Despite the API, packageName cannot be null
                                    packageName,
                                    it
                            )
                        }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val hasSSO = withState(loginViewModel) { it.loginMode.hasSso() }
        if (hasSSO) {
            customTabsServiceConnection?.let { requireContext().unbindService(it) }
            customTabsServiceConnection = null
        }
    }

    private fun prefetchUrl(url: String) {
        if (customTabsSession == null) {
            customTabsSession = customTabsClient?.newSession(null)
        }

        customTabsSession?.mayLaunchUrl(Uri.parse(url), null, null)
    }

    protected fun openInCustomTab(ssoUrl: String) {
        openUrlInChromeCustomTab(requireContext(), customTabsSession, ssoUrl)
    }

    private fun prefetchIfNeeded() {
        withState(loginViewModel) { state ->
            if (state.loginMode.hasSso() && state.loginMode.ssoIdentityProviders().isNullOrEmpty()) {
                // in this case we can prefetch (not other cases for privacy concerns)
                loginViewModel.getSsoUrl(
                        redirectUrl = LoginActivity2.VECTOR_REDIRECT_URL,
                        deviceId = state.deviceId,
                        providerId = null
                )
                        ?.let { prefetchUrl(it) }
            }
        }
    }
}

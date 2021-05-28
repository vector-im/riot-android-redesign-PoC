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

package im.vector.app.features.signout.soft

import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Incomplete
import com.airbnb.mvrx.Success
import im.vector.app.R
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.login.LoginMode
import im.vector.app.features.signout.soft.epoxy.loginCenterButtonItem
import im.vector.app.features.signout.soft.epoxy.loginErrorWithRetryItem
import im.vector.app.features.signout.soft.epoxy.loginHeaderItem
import im.vector.app.features.signout.soft.epoxy.loginPasswordFormItem
import im.vector.app.features.signout.soft.epoxy.loginRedButtonItem
import im.vector.app.features.signout.soft.epoxy.loginTextItem
import im.vector.app.features.signout.soft.epoxy.loginTitleItem
import im.vector.app.features.signout.soft.epoxy.loginTitleSmallItem
import javax.inject.Inject

class SoftLogoutController @Inject constructor(
        private val stringProvider: StringProvider,
        private val errorFormatter: ErrorFormatter
) : EpoxyController() {

    var listener: Listener? = null

    private var viewState: SoftLogoutViewState? = null

    init {
        // We are requesting a model build directly as the first build of epoxy is on the main thread.
        // It avoids to build the whole list of breadcrumbs on the main thread.
        requestModelBuild()
    }

    fun update(viewState: SoftLogoutViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    override fun buildModels() {
        val safeViewState = viewState ?: return

        buildHeader(safeViewState)
        buildForm(safeViewState)
        buildClearDataSection()
    }

    private fun buildHeader(state: SoftLogoutViewState) {
        val host = this
        loginHeaderItem {
            id("header")
        }
        loginTitleItem {
            id("title")
            text(host.stringProvider.getString(R.string.soft_logout_title))
        }
        loginTitleSmallItem {
            id("signTitle")
            text(host.stringProvider.getString(R.string.soft_logout_signin_title))
        }
        loginTextItem {
            id("signText1")
            text(host.stringProvider.getString(R.string.soft_logout_signin_notice,
                    state.homeServerUrl.toReducedUrl(),
                    state.userDisplayName,
                    state.userId))
        }
        if (state.hasUnsavedKeys) {
            loginTextItem {
                id("signText2")
                text(host.stringProvider.getString(R.string.soft_logout_signin_e2e_warning_notice))
            }
        }
    }

    private fun buildForm(state: SoftLogoutViewState) {
        val host = this
        when (state.asyncHomeServerLoginFlowRequest) {
            is Incomplete -> {
                loadingItem {
                    id("loading")
                }
            }
            is Fail       -> {
                loginErrorWithRetryItem {
                    id("errorRetry")
                    text(host.errorFormatter.toHumanReadable(state.asyncHomeServerLoginFlowRequest.error))
                    listener { host.listener?.retry() }
                }
            }
            is Success    -> {
                when (state.asyncHomeServerLoginFlowRequest.invoke()) {
                    LoginMode.Password          -> {
                        loginPasswordFormItem {
                            id("passwordForm")
                            stringProvider(host.stringProvider)
                            passwordShown(state.passwordShown)
                            submitEnabled(state.submitEnabled)
                            onPasswordEdited { host.listener?.passwordEdited(it) }
                            errorText((state.asyncLoginAction as? Fail)?.error?.let { host.errorFormatter.toHumanReadable(it) })
                            passwordRevealClickListener { host.listener?.revealPasswordClicked() }
                            forgetPasswordClickListener { host.listener?.forgetPasswordClicked() }
                            submitClickListener { password -> host.listener?.signinSubmit(password) }
                        }
                    }
                    is LoginMode.Sso            -> {
                        loginCenterButtonItem {
                            id("sso")
                            text(host.stringProvider.getString(R.string.login_signin_sso))
                            listener { host.listener?.signinFallbackSubmit() }
                        }
                    }
                    is LoginMode.SsoAndPassword -> {
                    }
                    LoginMode.Unsupported       -> {
                        loginCenterButtonItem {
                            id("fallback")
                            text(host.stringProvider.getString(R.string.login_signin))
                            listener { host.listener?.signinFallbackSubmit() }
                        }
                    }
                    LoginMode.Unknown           -> Unit // Should not happen
                }
            }
        }
    }

    private fun buildClearDataSection() {
        val host = this
        loginTitleSmallItem {
            id("clearDataTitle")
            text(host.stringProvider.getString(R.string.soft_logout_clear_data_title))
        }
        loginTextItem {
            id("clearDataText")
            text(host.stringProvider.getString(R.string.soft_logout_clear_data_notice))
        }
        loginRedButtonItem {
            id("clearDataSubmit")
            text(host.stringProvider.getString(R.string.soft_logout_clear_data_submit))
            listener { host.listener?.clearData() }
        }
    }

    interface Listener {
        fun retry()
        fun passwordEdited(password: String)
        fun signinSubmit(password: String)
        fun signinFallbackSubmit()
        fun clearData()
        fun forgetPasswordClicked()
        fun revealPasswordClicked()
    }
}

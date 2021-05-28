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

package im.vector.app.features.login2

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.autofill.HintConstants
import androidx.core.view.isVisible
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.databinding.FragmentLoginSignupUsername2Binding
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.SocialLoginButtonsView
import io.reactivex.rxkotlin.subscribeBy
import javax.inject.Inject

/**
 * In this screen:
 * - the user is asked for an identifier to sign up to a homeserver.
 * - SSO option are displayed if available
 */
class LoginFragmentSignupUsername2 @Inject constructor() : AbstractSSOLoginFragment2<FragmentLoginSignupUsername2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSignupUsername2Binding {
        return FragmentLoginSignupUsername2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubmitButton()
        setupAutoFill()
        setupSocialLoginButtons()
    }

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.loginField.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_USERNAME)
        }
    }

    private fun setupSocialLoginButtons() {
        views.loginSocialLoginButtons.mode = SocialLoginButtonsView.Mode.MODE_SIGN_UP
    }

    private fun submit() {
        cleanupUi()

        val login = views.loginField.text.toString().trim()

        // This can be called by the IME action, so deal with empty cases
        var error = 0
        if (login.isEmpty()) {
            views.loginFieldTil.error = getString(R.string.error_empty_field_choose_user_name)
            error++
        }

        if (error == 0) {
            loginViewModel.handle(LoginAction2.SetUserName(login))
        }
    }

    private fun cleanupUi() {
        views.loginSubmit.hideKeyboard()
        views.loginFieldTil.error = null
    }

    private fun setupUi(state: LoginViewState2) {
        views.loginSubtitle.text = getString(R.string.login_signup_to, state.homeServerUrlFromUser.toReducedUrl())

        if (state.loginMode is LoginMode.SsoAndPassword) {
            views.loginSocialLoginContainer.isVisible = true
            views.loginSocialLoginButtons.ssoIdentityProviders = state.loginMode.ssoIdentityProviders?.sorted()
            views.loginSocialLoginButtons.listener = object : SocialLoginButtonsView.InteractionListener {
                override fun onProviderSelected(id: String?) {
                    loginViewModel.getSsoUrl(
                            redirectUrl = LoginActivity2.VECTOR_REDIRECT_URL,
                            deviceId = state.deviceId,
                            providerId = id
                    )
                            ?.let { openInCustomTab(it) }
                }
            }
        } else {
            views.loginSocialLoginContainer.isVisible = false
            views.loginSocialLoginButtons.ssoIdentityProviders = null
        }
    }

    private fun setupSubmitButton() {
        views.loginSubmit.setOnClickListener { submit() }
        views.loginField.textChanges()
                .map { it.trim() }
                .subscribeBy { text ->
                    val isNotEmpty = text.isNotEmpty()
                    views.loginFieldTil.error = null
                    views.loginSubmit.isEnabled = isNotEmpty
                }
                .disposeOnDestroyView()
    }

    override fun resetViewModel() {
        // loginViewModel.handle(LoginAction2.ResetSignup)
    }

    override fun onError(throwable: Throwable) {
        views.loginFieldTil.error = errorFormatter.toHumanReadable(throwable)
    }

    @SuppressLint("SetTextI18n")
    override fun updateWithState(state: LoginViewState2) {
        setupUi(state)
    }
}

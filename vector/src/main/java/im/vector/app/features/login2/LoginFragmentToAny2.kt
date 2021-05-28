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

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.autofill.HintConstants
import androidx.core.view.isVisible
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.showPassword
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.databinding.FragmentLoginSigninToAny2Binding
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.SocialLoginButtonsView
import io.reactivex.Observable
import io.reactivex.rxkotlin.subscribeBy
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.failure.isInvalidPassword
import javax.inject.Inject

/**
 * In this screen:
 * User want to sign in and has selected a server to do so
 * - the user is asked for login (or email) and password to sign in to a homeserver.
 * - He also can reset his password
 * - It also possible to use SSO if server support it in this screen
 */
class LoginFragmentToAny2 @Inject constructor() : AbstractSSOLoginFragment2<FragmentLoginSigninToAny2Binding>() {

    private var passwordShown = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSigninToAny2Binding {
        return FragmentLoginSigninToAny2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubmitButton()
        setupForgottenPasswordButton()
        setupPasswordReveal()
        setupAutoFill()
        setupSocialLoginButtons()

        views.passwordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    private fun setupForgottenPasswordButton() {
        views.forgetPasswordButton.setOnClickListener { forgetPasswordClicked() }
    }

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.loginField.setAutofillHints(HintConstants.AUTOFILL_HINT_USERNAME)
            views.passwordField.setAutofillHints(HintConstants.AUTOFILL_HINT_PASSWORD)
        }
    }

    private fun setupSocialLoginButtons() {
        views.loginSocialLoginButtons.mode = SocialLoginButtonsView.Mode.MODE_SIGN_IN
    }

    private fun submit() {
        cleanupUi()

        val login = views.loginField.text.toString()
        val password = views.passwordField.text.toString()

        // This can be called by the IME action, so deal with empty cases
        var error = 0
        if (login.isEmpty()) {
            views.loginFieldTil.error = getString(R.string.error_empty_field_enter_user_name)
            error++
        }
        if (password.isEmpty()) {
            views.passwordFieldTil.error = getString(R.string.error_empty_field_your_password)
            error++
        }

        if (error == 0) {
            loginViewModel.handle(LoginAction2.LoginWith(login, password))
        }
    }

    private fun cleanupUi() {
        views.loginSubmit.hideKeyboard()
        views.loginFieldTil.error = null
        views.passwordFieldTil.error = null
    }

    private fun setupUi(state: LoginViewState2) {
        views.loginTitle.text = getString(R.string.login_connect_to, state.homeServerUrlFromUser.toReducedUrl())

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
        Observable
                .combineLatest(
                        views.loginField.textChanges().map { it.trim().isNotEmpty() },
                        views.passwordField.textChanges().map { it.isNotEmpty() },
                        { isLoginNotEmpty, isPasswordNotEmpty ->
                            isLoginNotEmpty && isPasswordNotEmpty
                        }
                )
                .subscribeBy {
                    views.loginFieldTil.error = null
                    views.passwordFieldTil.error = null
                    views.loginSubmit.isEnabled = it
                }
                .disposeOnDestroyView()
    }

    private fun forgetPasswordClicked() {
        loginViewModel.handle(LoginAction2.PostViewEvent(LoginViewEvents2.OpenResetPasswordScreen))
    }

    private fun setupPasswordReveal() {
        passwordShown = false

        views.passwordReveal.setOnClickListener {
            passwordShown = !passwordShown

            renderPasswordField()
        }

        renderPasswordField()
    }

    private fun renderPasswordField() {
        views.passwordField.showPassword(passwordShown)
        views.passwordReveal.render(passwordShown)
    }

    override fun resetViewModel() {
        // loginViewModel.handle(LoginAction2.ResetSignin)
    }

    override fun onError(throwable: Throwable) {
        // Show M_WEAK_PASSWORD error in the password field
        if (throwable is Failure.ServerError
                && throwable.error.code == MatrixError.M_WEAK_PASSWORD) {
            views.passwordFieldTil.error = errorFormatter.toHumanReadable(throwable)
        } else {
            if (throwable is Failure.ServerError
                    && throwable.error.code == MatrixError.M_FORBIDDEN
                    && throwable.error.message.isEmpty()) {
                // Login with email, but email unknown
                views.loginFieldTil.error = getString(R.string.login_login_with_email_error)
            } else {
                // Trick to display the error without text.
                views.loginFieldTil.error = " "
                if (throwable.isInvalidPassword() && spaceInPassword()) {
                    views.passwordFieldTil.error = getString(R.string.auth_invalid_login_param_space_in_password)
                } else {
                    views.passwordFieldTil.error = errorFormatter.toHumanReadable(throwable)
                }
            }
        }
    }

    override fun updateWithState(state: LoginViewState2) {
        setupUi(state)

        if (state.isLoading) {
            // Ensure password is hidden
            passwordShown = false
            renderPasswordField()
        }
    }

    /**
     * Detect if password ends or starts with spaces
     */
    private fun spaceInPassword() = views.passwordField.text.toString().let { it.trim() != it }
}

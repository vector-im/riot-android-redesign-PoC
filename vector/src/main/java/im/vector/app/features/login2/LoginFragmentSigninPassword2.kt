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
import com.airbnb.mvrx.Fail
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.showPassword
import im.vector.app.databinding.FragmentLoginSigninPassword2Binding
import im.vector.app.features.home.AvatarRenderer
import io.reactivex.rxkotlin.subscribeBy
import org.matrix.android.sdk.api.auth.login.LoginProfileInfo
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.isInvalidPassword
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

/**
 * In this screen:
 * - the user is asked for password to sign in to a homeserver.
 * - He also can reset his password
 */
class LoginFragmentSigninPassword2 @Inject constructor(
        private val avatarRenderer: AvatarRenderer
) : AbstractSSOLoginFragment2<FragmentLoginSigninPassword2Binding>() {

    private var passwordShown = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSigninPassword2Binding {
        return FragmentLoginSigninPassword2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubmitButton()
        setupForgottenPasswordButton()
        setupPasswordReveal()
        setupAutoFill()

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
            views.passwordField.setAutofillHints(HintConstants.AUTOFILL_HINT_PASSWORD)
        }
    }

    private fun submit() {
        cleanupUi()

        val password = views.passwordField.text.toString()

        // This can be called by the IME action, so deal with empty cases
        var error = 0
        if (password.isEmpty()) {
            views.passwordFieldTil.error = getString(R.string.error_empty_field_your_password)
            error++
        }

        if (error == 0) {
            loginViewModel.handle(LoginAction2.SetUserPassword(password))
        }
    }

    private fun cleanupUi() {
        views.loginSubmit.hideKeyboard()
        views.passwordFieldTil.error = null
    }

    private fun setupUi(state: LoginViewState2) {
        // Name and avatar
        views.loginWelcomeBack.text = getString(
                R.string.login_welcome_back,
                state.loginProfileInfo()?.displayName?.takeIf { it.isNotBlank() } ?: state.userIdentifier()
        )

        avatarRenderer.render(
                profileInfo = state.loginProfileInfo() ?: LoginProfileInfo(state.userIdentifier(), null, null),
                imageView = views.loginUserIcon
        )

        views.loginWelcomeBackWarning.isVisible = ((state.loginProfileInfo as? Fail)
                ?.error as? Failure.ServerError)
                ?.httpCode == HttpsURLConnection.HTTP_NOT_FOUND /* 404 */
    }

    private fun setupSubmitButton() {
        views.loginSubmit.setOnClickListener { submit() }
        views.passwordField
                .textChanges()
                .map { it.isNotEmpty() }
                .subscribeBy {
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
        if (throwable.isInvalidPassword() && spaceInPassword()) {
            views.passwordFieldTil.error = getString(R.string.auth_invalid_login_param_space_in_password)
        } else {
            views.passwordFieldTil.error = errorFormatter.toHumanReadable(throwable)
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

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
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.showPassword
import im.vector.app.databinding.FragmentLoginSignupPassword2Binding
import io.reactivex.rxkotlin.subscribeBy
import javax.inject.Inject

/**
 * In this screen:
 * - the user is asked to choose a password to sign up to a homeserver.
 */
class LoginFragmentSignupPassword2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginSignupPassword2Binding>() {

    private var passwordShown = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSignupPassword2Binding {
        return FragmentLoginSignupPassword2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubmitButton()
        setupAutoFill()
        setupPasswordReveal()

        views.passwordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.passwordField.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_PASSWORD)
        }
    }

    private fun submit() {
        cleanupUi()

        val password = views.passwordField.text.toString()

        // This can be called by the IME action, so deal with empty cases
        var error = 0
        if (password.isEmpty()) {
            views.passwordFieldTil.error = getString(R.string.error_empty_field_choose_password)
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

    private fun setupSubmitButton() {
        views.loginSubmit.setOnClickListener { submit() }
        views.passwordField.textChanges()
                .subscribeBy { password ->
                    views.passwordFieldTil.error = null
                    views.loginSubmit.isEnabled = password.isNotEmpty()
                }
                .disposeOnDestroyView()
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
        views.passwordReveal.render(passwordShown)
        views.passwordField.showPassword(passwordShown)
    }

    override fun resetViewModel() {
        // loginViewModel.handle(LoginAction2.ResetSignup)
    }

    override fun onError(throwable: Throwable) {
        views.passwordFieldTil.error = errorFormatter.toHumanReadable(throwable)
    }

    override fun updateWithState(state: LoginViewState2) {
        views.loginMatrixIdentifier.text = state.userIdentifier()

        if (state.isLoading) {
            // Ensure password is hidden
            passwordShown = false
            renderPasswordField()
        }
    }
}

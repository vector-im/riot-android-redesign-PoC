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
import androidx.appcompat.app.AlertDialog
import androidx.autofill.HintConstants
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.isEmail
import im.vector.app.core.extensions.showPassword
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.core.utils.autoResetTextInputLayoutErrors
import im.vector.app.databinding.FragmentLoginResetPassword2Binding
import io.reactivex.Observable
import io.reactivex.rxkotlin.subscribeBy

import javax.inject.Inject

/**
 * In this screen, the user is asked for email and new password to reset his password
 */
class LoginResetPasswordFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginResetPassword2Binding>() {

    private var passwordShown = false

    // Show warning only once
    private var showWarning = true

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginResetPassword2Binding {
        return FragmentLoginResetPassword2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubmitButton()
        setupPasswordReveal()
        setupAutoFill()

        autoResetTextInputLayoutErrors(listOf(views.resetPasswordEmailTil, views.passwordFieldTil))

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
            views.resetPasswordEmail.setAutofillHints(HintConstants.AUTOFILL_HINT_EMAIL_ADDRESS)
            views.passwordField.setAutofillHints(HintConstants.AUTOFILL_HINT_NEW_PASSWORD)
        }
    }

    private fun setupUi(state: LoginViewState2) {
        views.resetPasswordTitle.text = getString(R.string.login_reset_password_on, state.homeServerUrlFromUser.toReducedUrl())
    }

    private fun setupSubmitButton() {
        views.resetPasswordSubmit.setOnClickListener { submit() }

        Observable
                .combineLatest(
                        views.resetPasswordEmail.textChanges().map { it.isEmail() },
                        views.passwordField.textChanges().map { it.isNotEmpty() },
                        { isEmail, isPasswordNotEmpty ->
                            isEmail && isPasswordNotEmpty
                        }
                )
                .subscribeBy {
                    views.resetPasswordSubmit.isEnabled = it
                }
                .disposeOnDestroyView()
    }

    private fun submit() {
        cleanupUi()

        var error = 0

        val email = views.resetPasswordEmail.text.toString()
        val password = views.passwordField.text.toString()

        if (email.isEmpty()) {
            views.resetPasswordEmailTil.error = getString(R.string.auth_reset_password_missing_email)
            error++
        }

        if (password.isEmpty()) {
            views.passwordFieldTil.error = getString(R.string.login_please_choose_a_new_password)
            error++
        }

        if (error > 0) {
            return
        }

        if (showWarning) {
            // Display a warning as Riot-Web does first
            AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.login_reset_password_warning_title)
                    .setMessage(R.string.login_reset_password_warning_content)
                    .setPositiveButton(R.string.login_reset_password_warning_submit) { _, _ ->
                        showWarning = false
                        doSubmit()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
        } else {
            doSubmit()
        }
    }

    private fun doSubmit() {
        val email = views.resetPasswordEmail.text.toString()
        val password = views.passwordField.text.toString()

        loginViewModel.handle(LoginAction2.ResetPassword(email, password))
    }

    private fun cleanupUi() {
        views.resetPasswordSubmit.hideKeyboard()
        views.resetPasswordEmailTil.error = null
        views.passwordFieldTil.error = null
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
        loginViewModel.handle(LoginAction2.ResetResetPassword)
    }

    override fun onError(throwable: Throwable) {
        views.resetPasswordEmailTil.error = errorFormatter.toHumanReadable(throwable)
    }

    override fun updateWithState(state: LoginViewState2) {
        setupUi(state)

        if (state.isLoading) {
            // Ensure new password is hidden
            passwordShown = false
            renderPasswordField()
        }
    }
}

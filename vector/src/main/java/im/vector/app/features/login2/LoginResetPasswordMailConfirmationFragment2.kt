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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import im.vector.app.R
import im.vector.app.databinding.FragmentLoginResetPasswordMailConfirmation2Binding
import org.matrix.android.sdk.api.failure.is401
import javax.inject.Inject

/**
 * In this screen, the user is asked to check his email and to click on a button once it's done
 */
class LoginResetPasswordMailConfirmationFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginResetPasswordMailConfirmation2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginResetPasswordMailConfirmation2Binding {
        return FragmentLoginResetPasswordMailConfirmation2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.resetPasswordMailConfirmationSubmit.setOnClickListener { submit() }
    }

    private fun setupUi(state: LoginViewState2) {
        views.resetPasswordMailConfirmationNotice.text = getString(R.string.login_reset_password_mail_confirmation_notice, state.resetPasswordEmail)
    }

    private fun submit() {
        loginViewModel.handle(LoginAction2.ResetPasswordMailConfirmed)
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetResetPassword)
    }

    override fun onError(throwable: Throwable) {
        // Link in email not yet clicked ?
        val message = if (throwable.is401()) {
            getString(R.string.auth_reset_password_error_unauthorized)
        } else {
            errorFormatter.toHumanReadable(throwable)
        }

        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.dialog_title_error)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    override fun updateWithState(state: LoginViewState2) {
        setupUi(state)
    }
}

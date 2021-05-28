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
import androidx.autofill.HintConstants
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.databinding.FragmentLoginSigninUsername2Binding
import io.reactivex.rxkotlin.subscribeBy
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import javax.inject.Inject

/**
 * In this screen:
 * - the user is asked for its matrix ID, and have the possibility to open the screen to select a server
 */
class LoginFragmentSigninUsername2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginSigninUsername2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSigninUsername2Binding {
        return FragmentLoginSigninUsername2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubmitButton()
        setupAutoFill()
        views.loginChooseAServer.setOnClickListener {
            loginViewModel.handle(LoginAction2.ChooseAServerForSignin)
        }
    }

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.loginField.setAutofillHints(HintConstants.AUTOFILL_HINT_USERNAME)
        }
    }

    private fun submit() {
        cleanupUi()

        val login = views.loginField.text.toString()

        // This can be called by the IME action, so deal with empty cases
        var error = 0
        if (login.isEmpty()) {
            views.loginFieldTil.error = getString(R.string.error_empty_field_enter_user_name)
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

    private fun setupSubmitButton() {
        views.loginSubmit.setOnClickListener { submit() }
        views.loginField.textChanges()
                .map { it.trim().isNotEmpty() }
                .subscribeBy {
                    views.loginFieldTil.error = null
                    views.loginSubmit.isEnabled = it
                }
                .disposeOnDestroyView()
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetSignin)
    }

    override fun onError(throwable: Throwable) {
        if (throwable is Failure.ServerError
                && throwable.error.code == MatrixError.M_FORBIDDEN
                && throwable.error.message.isEmpty()) {
            // Login with email, but email unknown
            views.loginFieldTil.error = getString(R.string.login_login_with_email_error)
        } else {
            views.loginFieldTil.error = errorFormatter.toHumanReadable(throwable)
        }
    }
}

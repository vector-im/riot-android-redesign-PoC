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
import com.airbnb.mvrx.args
import im.vector.app.R
import im.vector.app.databinding.FragmentLoginWaitForEmail2Binding
import im.vector.app.features.login.LoginWaitForEmailFragmentArgument
import org.matrix.android.sdk.api.failure.is401
import javax.inject.Inject

/**
 * In this screen, the user is asked to check his emails
 */
class LoginWaitForEmailFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginWaitForEmail2Binding>() {

    private val params: LoginWaitForEmailFragmentArgument by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginWaitForEmail2Binding {
        return FragmentLoginWaitForEmail2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUi()
    }

    override fun onResume() {
        super.onResume()

        loginViewModel.handle(LoginAction2.CheckIfEmailHasBeenValidated(0))
    }

    override fun onPause() {
        super.onPause()

        loginViewModel.handle(LoginAction2.StopEmailValidationCheck)
    }

    private fun setupUi() {
        views.loginWaitForEmailNotice.text = getString(R.string.login_wait_for_email_notice_2, params.email)
    }

    override fun onError(throwable: Throwable) {
        if (throwable.is401()) {
            // Try again, with a delay
            loginViewModel.handle(LoginAction2.CheckIfEmailHasBeenValidated(10_000))
        } else {
            super.onError(throwable)
        }
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetSignup)
    }
}

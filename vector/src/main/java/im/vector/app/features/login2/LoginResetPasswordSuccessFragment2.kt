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
import im.vector.app.databinding.FragmentLoginResetPasswordSuccess2Binding

import javax.inject.Inject

/**
 * In this screen, we confirm to the user that his password has been reset
 */
class LoginResetPasswordSuccessFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginResetPasswordSuccess2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginResetPasswordSuccess2Binding {
        return FragmentLoginResetPasswordSuccess2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.resetPasswordSuccessSubmit.setOnClickListener { submit() }
    }

    private fun submit() {
        loginViewModel.handle(LoginAction2.PostViewEvent(LoginViewEvents2.OnResetPasswordMailConfirmationSuccessDone))
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetResetPassword)
    }
}

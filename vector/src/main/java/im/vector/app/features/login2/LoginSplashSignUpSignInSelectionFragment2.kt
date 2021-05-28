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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import im.vector.app.BuildConfig
import im.vector.app.databinding.FragmentLoginSplash2Binding
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject

/**
 * In this screen, the user is asked to sign up or to sign in to the homeserver
 * This is the new splash screen
 */
class LoginSplashSignUpSignInSelectionFragment2 @Inject constructor(
        private val vectorPreferences: VectorPreferences
) : AbstractLoginFragment2<FragmentLoginSplash2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSplash2Binding {
        return FragmentLoginSplash2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
    }

    private fun setupViews() {
        views.loginSignupSigninSignUp.setOnClickListener { signUp() }
        views.loginSignupSigninSignIn.setOnClickListener { signIn() }

        if (BuildConfig.DEBUG || vectorPreferences.developerMode()) {
            views.loginSplashVersion.isVisible = true
            @SuppressLint("SetTextI18n")
            views.loginSplashVersion.text = "Version : ${BuildConfig.VERSION_NAME}\n" +
                    "Branch: ${BuildConfig.GIT_BRANCH_NAME}\n" +
                    "Build: ${BuildConfig.BUILD_NUMBER}"
        }
    }

    private fun signUp() {
        loginViewModel.handle(LoginAction2.UpdateSignMode(SignMode2.SignUp))
    }

    private fun signIn() {
        loginViewModel.handle(LoginAction2.UpdateSignMode(SignMode2.SignIn))
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetSignMode)
    }
}

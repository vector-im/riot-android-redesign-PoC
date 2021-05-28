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
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.databinding.FragmentLoginSsoOnly2Binding
import javax.inject.Inject

/**
 * In this screen, the user is asked to sign up or to sign in to the homeserver
 */
class LoginSsoOnlyFragment2 @Inject constructor() : AbstractSSOLoginFragment2<FragmentLoginSsoOnly2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginSsoOnly2Binding {
        return FragmentLoginSsoOnly2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
    }

    private fun setupViews() {
        views.loginSignupSigninSubmit.setOnClickListener { submit() }
    }

    private fun setupUi(state: LoginViewState2) {
        views.loginSignupSigninTitle.text = getString(R.string.login_connect_to, state.homeServerUrlFromUser.toReducedUrl())
    }

    private fun submit() = withState(loginViewModel) { state ->
        loginViewModel.getSsoUrl(
                redirectUrl = LoginActivity2.VECTOR_REDIRECT_URL,
                deviceId = state.deviceId,
                providerId = null
        )
                ?.let { openInCustomTab(it) }
    }

    override fun resetViewModel() {
        // No op
    }

    override fun updateWithState(state: LoginViewState2) {
        setupUi(state)
    }
}

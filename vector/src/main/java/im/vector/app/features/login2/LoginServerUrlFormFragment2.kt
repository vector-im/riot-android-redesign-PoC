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
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.core.view.isInvisible
import com.google.android.material.textfield.TextInputLayout
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.utils.ensureProtocol
import im.vector.app.databinding.FragmentLoginServerUrlForm2Binding
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

/**
 * In this screen, the user is prompted to enter a homeserver url
 */
class LoginServerUrlFormFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginServerUrlForm2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginServerUrlForm2Binding {
        return FragmentLoginServerUrlForm2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupHomeServerField()
    }

    private fun setupViews() {
        views.loginServerUrlFormClearHistory.setOnClickListener { clearHistory() }
        views.loginServerUrlFormSubmit.setOnClickListener { submit() }
    }

    private fun setupHomeServerField() {
        views.loginServerUrlFormHomeServerUrl.textChanges()
                .subscribe {
                    views.loginServerUrlFormHomeServerUrlTil.error = null
                    views.loginServerUrlFormSubmit.isEnabled = it.isNotBlank()
                }
                .disposeOnDestroyView()

        views.loginServerUrlFormHomeServerUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                views.loginServerUrlFormHomeServerUrl.dismissDropDown()
                submit()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    private fun setupUi(state: LoginViewState2) {
        val completions = state.knownCustomHomeServersUrls + if (BuildConfig.DEBUG) listOf("http://10.0.2.2:8080") else emptyList()
        views.loginServerUrlFormHomeServerUrl.setAdapter(ArrayAdapter(
                requireContext(),
                R.layout.item_completion_homeserver,
                completions
        ))
        views.loginServerUrlFormHomeServerUrlTil.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
                .takeIf { completions.isNotEmpty() }
                ?: TextInputLayout.END_ICON_NONE

        views.loginServerUrlFormClearHistory.isInvisible = state.knownCustomHomeServersUrls.isEmpty()
    }

    private fun clearHistory() {
        loginViewModel.handle(LoginAction2.ClearHomeServerHistory)
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetHomeServerUrl)
    }

    @SuppressLint("SetTextI18n")
    private fun submit() {
        cleanupUi()

        // Static check of homeserver url, empty, malformed, etc.
        val serverUrl = views.loginServerUrlFormHomeServerUrl.text.toString().trim().ensureProtocol()

        when {
            serverUrl.isBlank() -> {
                views.loginServerUrlFormHomeServerUrlTil.error = getString(R.string.login_error_invalid_home_server)
            }
            else                -> {
                views.loginServerUrlFormHomeServerUrl.setText(serverUrl, false /* to avoid completion dialog flicker*/)
                loginViewModel.handle(LoginAction2.UpdateHomeServer(serverUrl))
            }
        }
    }

    private fun cleanupUi() {
        views.loginServerUrlFormSubmit.hideKeyboard()
        views.loginServerUrlFormHomeServerUrlTil.error = null
    }

    override fun onError(throwable: Throwable) {
        views.loginServerUrlFormHomeServerUrlTil.error = if (throwable is Failure.NetworkConnection
                && throwable.ioException is UnknownHostException) {
            // Invalid homeserver?
            getString(R.string.login_error_homeserver_not_found)
        } else {
            if (throwable is Failure.ServerError
                    && throwable.error.code == MatrixError.M_FORBIDDEN
                    && throwable.httpCode == HttpsURLConnection.HTTP_FORBIDDEN /* 403 */) {
                getString(R.string.login_registration_disabled)
            } else {
                errorFormatter.toHumanReadable(throwable)
            }
        }
    }

    override fun updateWithState(state: LoginViewState2) {
        setupUi(state)
    }
}

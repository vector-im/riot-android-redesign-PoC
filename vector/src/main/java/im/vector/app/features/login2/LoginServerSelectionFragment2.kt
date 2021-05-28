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
import im.vector.app.R
import im.vector.app.core.extensions.setTextWithColoredPart
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.databinding.FragmentLoginServerSelection2Binding
import im.vector.app.features.login.EMS_LINK
import javax.inject.Inject

/**
 * In this screen, the user will choose between matrix.org, or other type of homeserver
 */
class LoginServerSelectionFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginServerSelection2Binding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginServerSelection2Binding {
        return FragmentLoginServerSelection2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews()
    }

    private fun initViews() {
        views.loginServerChoiceMatrixOrg.setOnClickListener { selectMatrixOrg() }
        views.loginServerChoiceOther.setOnClickListener { selectOther() }

        views.loginServerChoiceEmsLearnMore.setTextWithColoredPart(
                fullTextRes = R.string.login_server_modular_learn_more_about_ems,
                coloredTextRes = R.string.login_server_modular_learn_more,
                underline = true
        )
        views.loginServerChoiceEmsLearnMore.setOnClickListener {
            openUrlInChromeCustomTab(requireActivity(), null, EMS_LINK)
        }
    }

    private fun updateUi(state: LoginViewState2) {
        when (state.signMode) {
            SignMode2.Unknown -> Unit
            SignMode2.SignUp  -> {
                views.loginServerTitle.setText(R.string.login_please_choose_a_server)
            }
            SignMode2.SignIn  -> {
                views.loginServerTitle.setText(R.string.login_please_select_your_server)
            }
        }
    }

    private fun selectMatrixOrg() {
        views.loginServerChoiceMatrixOrg.isChecked = true
        loginViewModel.handle(LoginAction2.ChooseDefaultHomeServer)
    }

    private fun selectOther() {
        views.loginServerChoiceOther.isChecked = true
        loginViewModel.handle(LoginAction2.EnterServerUrl)
    }

    override fun onResume() {
        super.onResume()
        views.loginServerChoiceMatrixOrg.isChecked = false
        views.loginServerChoiceOther.isChecked = false
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetHomeServerUrl)
    }

    override fun updateWithState(state: LoginViewState2) {
        updateUi(state)
    }
}

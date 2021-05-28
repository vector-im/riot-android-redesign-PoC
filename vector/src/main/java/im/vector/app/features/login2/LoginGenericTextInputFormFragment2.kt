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
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.autofill.HintConstants
import androidx.core.view.isVisible
import com.airbnb.mvrx.args
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.jakewharton.rxbinding3.widget.textChanges
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.isEmail
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.databinding.FragmentLoginGenericTextInputForm2Binding
import im.vector.app.features.login.LoginGenericTextInputFormFragmentArgument
import im.vector.app.features.login.TextInputFormFragmentMode
import org.matrix.android.sdk.api.auth.registration.RegisterThreePid
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.is401
import javax.inject.Inject

/**
 * In this screen, the user is asked for a text input
 */
class LoginGenericTextInputFormFragment2 @Inject constructor() : AbstractLoginFragment2<FragmentLoginGenericTextInputForm2Binding>() {

    private val params: LoginGenericTextInputFormFragmentArgument by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginGenericTextInputForm2Binding {
        return FragmentLoginGenericTextInputForm2Binding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupUi()
        setupSubmitButton()
        setupTil()
        setupAutoFill()
    }

    private fun setupViews() {
        views.loginGenericTextInputFormOtherButton.setOnClickListener { onOtherButtonClicked() }
        views.loginGenericTextInputFormSubmit.setOnClickListener { submit() }
        views.loginGenericTextInputFormLater.setOnClickListener { submit() }
    }

    private fun setupAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            views.loginGenericTextInputFormTextInput.setAutofillHints(
                    when (params.mode) {
                        TextInputFormFragmentMode.SetEmail  -> HintConstants.AUTOFILL_HINT_EMAIL_ADDRESS
                        TextInputFormFragmentMode.SetMsisdn -> HintConstants.AUTOFILL_HINT_PHONE_NUMBER
                        TextInputFormFragmentMode.ConfirmMsisdn -> HintConstants.AUTOFILL_HINT_SMS_OTP
                    }
            )
        }
    }

    private fun setupTil() {
        views.loginGenericTextInputFormTextInput.textChanges()
                .subscribe {
                    views.loginGenericTextInputFormTil.error = null
                }
                .disposeOnDestroyView()
    }

    private fun setupUi() {
        when (params.mode) {
            TextInputFormFragmentMode.SetEmail      -> {
                views.loginGenericTextInputFormTitle.text = getString(R.string.login_set_email_title_2)
                views.loginGenericTextInputFormNotice.text = getString(R.string.login_set_email_notice_2)
                // Text will be updated with the state
                views.loginGenericTextInputFormMandatoryNotice.isVisible = params.mandatory
                views.loginGenericTextInputFormNotice2.isVisible = false
                views.loginGenericTextInputFormTil.hint =
                        getString(if (params.mandatory) R.string.login_set_email_mandatory_hint else R.string.login_set_email_optional_hint)
                views.loginGenericTextInputFormTextInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                views.loginGenericTextInputFormOtherButton.isVisible = false
                views.loginGenericTextInputFormSubmit.text = getString(R.string.login_set_email_submit)
            }
            TextInputFormFragmentMode.SetMsisdn     -> {
                views.loginGenericTextInputFormTitle.text = getString(R.string.login_set_msisdn_title_2)
                views.loginGenericTextInputFormNotice.text = getString(R.string.login_set_msisdn_notice_2)
                // Text will be updated with the state
                views.loginGenericTextInputFormMandatoryNotice.isVisible = params.mandatory
                views.loginGenericTextInputFormNotice2.setTextOrHide(getString(R.string.login_set_msisdn_notice2))
                views.loginGenericTextInputFormTil.hint =
                        getString(if (params.mandatory) R.string.login_set_msisdn_mandatory_hint else R.string.login_set_msisdn_optional_hint)
                views.loginGenericTextInputFormTextInput.inputType = InputType.TYPE_CLASS_PHONE
                views.loginGenericTextInputFormOtherButton.isVisible = false
                views.loginGenericTextInputFormSubmit.text = getString(R.string.login_set_msisdn_submit)
            }
            TextInputFormFragmentMode.ConfirmMsisdn -> {
                views.loginGenericTextInputFormTitle.text = getString(R.string.login_msisdn_confirm_title)
                views.loginGenericTextInputFormNotice.text = getString(R.string.login_msisdn_confirm_notice, params.extra)
                views.loginGenericTextInputFormMandatoryNotice.isVisible = false
                views.loginGenericTextInputFormNotice2.isVisible = false
                views.loginGenericTextInputFormTil.hint =
                        getString(R.string.login_msisdn_confirm_hint)
                views.loginGenericTextInputFormTextInput.inputType = InputType.TYPE_CLASS_NUMBER
                views.loginGenericTextInputFormOtherButton.isVisible = true
                views.loginGenericTextInputFormOtherButton.text = getString(R.string.login_msisdn_confirm_send_again)
                views.loginGenericTextInputFormSubmit.text = getString(R.string.login_msisdn_confirm_submit)
            }
        }
    }

    private fun onOtherButtonClicked() {
        when (params.mode) {
            TextInputFormFragmentMode.ConfirmMsisdn -> {
                loginViewModel.handle(LoginAction2.SendAgainThreePid)
            }
            else                                    -> {
                // Should not happen, button is not displayed
            }
        }
    }

    private fun submit() {
        cleanupUi()
        val text = views.loginGenericTextInputFormTextInput.text.toString()

        if (text.isEmpty()) {
            // Perform dummy action
            loginViewModel.handle(LoginAction2.RegisterDummy)
        } else {
            when (params.mode) {
                TextInputFormFragmentMode.SetEmail      -> {
                    loginViewModel.handle(LoginAction2.AddThreePid(RegisterThreePid.Email(text)))
                }
                TextInputFormFragmentMode.SetMsisdn     -> {
                    getCountryCodeOrShowError(text)?.let { countryCode ->
                        loginViewModel.handle(LoginAction2.AddThreePid(RegisterThreePid.Msisdn(text, countryCode)))
                    }
                }
                TextInputFormFragmentMode.ConfirmMsisdn -> {
                    loginViewModel.handle(LoginAction2.ValidateThreePid(text))
                }
            }
        }
    }

    private fun cleanupUi() {
        views.loginGenericTextInputFormSubmit.hideKeyboard()
        views.loginGenericTextInputFormSubmit.error = null
    }

    private fun getCountryCodeOrShowError(text: String): String? {
        // We expect an international format for the moment (see https://github.com/vector-im/riotX-android/issues/693)
        if (text.startsWith("+")) {
            try {
                val phoneNumber = PhoneNumberUtil.getInstance().parse(text, null)
                return PhoneNumberUtil.getInstance().getRegionCodeForCountryCode(phoneNumber.countryCode)
            } catch (e: NumberParseException) {
                views.loginGenericTextInputFormTil.error = getString(R.string.login_msisdn_error_other)
            }
        } else {
            views.loginGenericTextInputFormTil.error = getString(R.string.login_msisdn_error_not_international)
        }

        // Error
        return null
    }

    private fun setupSubmitButton() {
        views.loginGenericTextInputFormSubmit.isEnabled = false
        views.loginGenericTextInputFormTextInput.textChanges()
                .subscribe { text ->
                    views.loginGenericTextInputFormSubmit.isEnabled = isInputValid(text)
                    text?.let { updateSubmitButtons(it) }
                }
                .disposeOnDestroyView()
    }

    private fun updateSubmitButtons(text: CharSequence) {
        if (params.mandatory) {
            views.loginGenericTextInputFormSubmit.isVisible = true
            views.loginGenericTextInputFormLater.isVisible = false
        } else {
            views.loginGenericTextInputFormSubmit.isVisible = text.isNotEmpty()
            views.loginGenericTextInputFormLater.isVisible = text.isEmpty()
        }
    }

    private fun isInputValid(input: CharSequence): Boolean {
        return if (input.isEmpty() && !params.mandatory) {
            true
        } else {
            when (params.mode) {
                TextInputFormFragmentMode.SetEmail      -> input.isEmail()
                TextInputFormFragmentMode.SetMsisdn     -> input.isNotBlank()
                TextInputFormFragmentMode.ConfirmMsisdn -> input.isNotBlank()
            }
        }
    }

    override fun onError(throwable: Throwable) {
        when (params.mode) {
            TextInputFormFragmentMode.SetEmail      -> {
                if (throwable.is401()) {
                    // This is normal use case, we go to the mail waiting screen
                    loginViewModel.handle(LoginAction2.PostViewEvent(LoginViewEvents2.OnSendEmailSuccess(loginViewModel.currentThreePid ?: "")))
                } else {
                    views.loginGenericTextInputFormTil.error = errorFormatter.toHumanReadable(throwable)
                }
            }
            TextInputFormFragmentMode.SetMsisdn     -> {
                if (throwable.is401()) {
                    // This is normal use case, we go to the enter code screen
                    loginViewModel.handle(LoginAction2.PostViewEvent(LoginViewEvents2.OnSendMsisdnSuccess(loginViewModel.currentThreePid ?: "")))
                } else {
                    views.loginGenericTextInputFormTil.error = errorFormatter.toHumanReadable(throwable)
                }
            }
            TextInputFormFragmentMode.ConfirmMsisdn -> {
                when {
                    throwable is Failure.SuccessError ->
                        // The entered code is not correct
                        views.loginGenericTextInputFormTil.error = getString(R.string.login_validation_code_is_not_correct)
                    throwable.is401()                 ->
                        // It can happen if user request again the 3pid
                        Unit
                    else                              ->
                        views.loginGenericTextInputFormTil.error = errorFormatter.toHumanReadable(throwable)
                }
            }
        }
    }

    override fun resetViewModel() {
        loginViewModel.handle(LoginAction2.ResetSignup)
    }

    override fun updateWithState(state: LoginViewState2) {
        views.loginGenericTextInputFormMandatoryNotice.text = when (params.mode) {
            TextInputFormFragmentMode.SetEmail      -> getString(R.string.login_set_email_mandatory_notice_2, state.homeServerUrlFromUser.toReducedUrl())
            TextInputFormFragmentMode.SetMsisdn     -> getString(R.string.login_set_msisdn_mandatory_notice_2, state.homeServerUrlFromUser.toReducedUrl())
            TextInputFormFragmentMode.ConfirmMsisdn -> null
        }
    }
}

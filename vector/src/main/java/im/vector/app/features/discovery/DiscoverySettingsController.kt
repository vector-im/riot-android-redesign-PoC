/*
 * Copyright (c) 2020 New Vector Ltd
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
package im.vector.app.features.discovery

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Incomplete
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.epoxy.attributes.ButtonStyle
import im.vector.app.core.epoxy.attributes.ButtonType
import im.vector.app.core.epoxy.attributes.IconMode
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.getFormattedValue
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.identity.SharedState
import org.matrix.android.sdk.api.session.identity.ThreePid
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

class DiscoverySettingsController @Inject constructor(
        private val colorProvider: ColorProvider,
        private val stringProvider: StringProvider,
        private val errorFormatter: ErrorFormatter
) : TypedEpoxyController<DiscoverySettingsState>() {

    var listener: Listener? = null

    private val codes = mutableMapOf<ThreePid, String>()

    override fun buildModels(data: DiscoverySettingsState) {
        when (data.identityServer) {
            is Loading -> {
                loadingItem {
                    id("identityServerLoading")
                }
            }
            is Fail    -> {
                settingsInfoItem {
                    id("identityServerError")
                    helperText(data.identityServer.error.message)
                }
            }
            is Success -> {
                buildIdentityServerSection(data)
                val hasIdentityServer = data.identityServer().isNullOrBlank().not()
                if (hasIdentityServer && !data.termsNotSigned) {
                    buildConsentSection(data)
                    buildEmailsSection(data.emailList)
                    buildMsisdnSection(data.phoneNumbersList)
                }
            }
        }
    }

    private fun buildConsentSection(data: DiscoverySettingsState) {
        val host = this
        settingsSectionTitleItem {
            id("idConsentTitle")
            titleResId(R.string.settings_discovery_consent_title)
        }

        if (data.userConsent) {
            settingsInfoItem {
                id("idConsentInfo")
                helperTextResId(R.string.settings_discovery_consent_notice_on)
            }
            settingsButtonItem {
                id("idConsentButton")
                colorProvider(host.colorProvider)
                buttonTitleId(R.string.settings_discovery_consent_action_revoke)
                buttonStyle(ButtonStyle.DESTRUCTIVE)
                buttonClickListener { host.listener?.onTapUpdateUserConsent(false) }
            }
        } else {
            settingsInfoItem {
                id("idConsentInfo")
                helperTextResId(R.string.settings_discovery_consent_notice_off)
            }
            settingsButtonItem {
                id("idConsentButton")
                colorProvider(host.colorProvider)
                buttonTitleId(R.string.settings_discovery_consent_action_give_consent)
                buttonClickListener { host.listener?.onTapUpdateUserConsent(true) }
            }
        }
    }

    private fun buildIdentityServerSection(data: DiscoverySettingsState) {
        val identityServer = data.identityServer() ?: stringProvider.getString(R.string.none)
        val host = this

        settingsSectionTitleItem {
            id("idServerTitle")
            titleResId(R.string.identity_server)
        }

        settingsItem {
            id("idServer")
            title(identityServer)
        }

        if (data.identityServer() != null && data.termsNotSigned) {
            settingsInfoItem {
                id("idServerFooter")
                helperText(host.stringProvider.getString(R.string.settings_agree_to_terms, identityServer))
                showCompoundDrawable(true)
                itemClickListener(View.OnClickListener { host.listener?.openIdentityServerTerms() })
            }
            settingsButtonItem {
                id("seeTerms")
                colorProvider(host.colorProvider)
                buttonTitle(host.stringProvider.getString(R.string.open_terms_of, identityServer))
                buttonClickListener { host.listener?.openIdentityServerTerms() }
            }
        } else {
            settingsInfoItem {
                id("idServerFooter")
                showCompoundDrawable(false)
                if (data.identityServer() != null) {
                    helperText(host.stringProvider.getString(R.string.settings_discovery_identity_server_info, identityServer))
                } else {
                    helperTextResId(R.string.settings_discovery_identity_server_info_none)
                }
            }
        }

        settingsButtonItem {
            id("change")
            colorProvider(host.colorProvider)
            if (data.identityServer() == null) {
                buttonTitleId(R.string.add_identity_server)
            } else {
                buttonTitleId(R.string.change_identity_server)
            }
            buttonClickListener { host.listener?.onTapChangeIdentityServer() }
        }

        if (data.identityServer() != null) {
            settingsInfoItem {
                id("removeInfo")
                helperTextResId(R.string.settings_discovery_disconnect_identity_server_info)
            }
            settingsButtonItem {
                id("remove")
                colorProvider(host.colorProvider)
                buttonTitleId(R.string.disconnect_identity_server)
                buttonStyle(ButtonStyle.DESTRUCTIVE)
                buttonClickListener { host.listener?.onTapDisconnectIdentityServer() }
            }
        }
    }

    private fun buildEmailsSection(emails: Async<List<PidInfo>>) {
        val host = this
        settingsSectionTitleItem {
            id("emails")
            titleResId(R.string.settings_discovery_emails_title)
        }
        when (emails) {
            is Incomplete -> {
                loadingItem {
                    id("emailsLoading")
                }
            }
            is Fail       -> {
                settingsInfoItem {
                    id("emailsError")
                    helperText(emails.error.message)
                }
            }
            is Success    -> {
                if (emails().isEmpty()) {
                    settingsInfoItem {
                        id("emailsEmpty")
                        helperText(host.stringProvider.getString(R.string.settings_discovery_no_mails))
                    }
                } else {
                    emails().forEach { buildEmail(it) }
                }
            }
        }
    }

    private fun buildEmail(pidInfo: PidInfo) {
        buildThreePid(pidInfo)

        val host = this
        if (pidInfo.isShared is Fail) {
            buildSharedFail(pidInfo)
        } else if (pidInfo.isShared() == SharedState.BINDING_IN_PROGRESS) {
            when (pidInfo.finalRequest) {
                is Uninitialized,
                is Loading ->
                    settingsInformationItem {
                        id("info${pidInfo.threePid.value}")
                        colorProvider(host.colorProvider)
                        message(host.stringProvider.getString(R.string.settings_discovery_confirm_mail, pidInfo.threePid.value))
                    }
                is Fail    ->
                    settingsInformationItem {
                        id("info${pidInfo.threePid.value}")
                        colorProvider(host.colorProvider)
                        message(host.stringProvider.getString(R.string.settings_discovery_confirm_mail_not_clicked, pidInfo.threePid.value))
                        textColorId(R.color.riotx_destructive_accent)
                    }
                is Success -> Unit /* Cannot happen */
            }
            when (pidInfo.finalRequest) {
                is Uninitialized,
                is Fail    ->
                    buildContinueCancel(pidInfo.threePid)
                is Loading ->
                    settingsProgressItem {
                        id("progress${pidInfo.threePid.value}")
                    }
                is Success -> Unit /* Cannot happen */
            }
        }
    }

    private fun buildMsisdnSection(msisdns: Async<List<PidInfo>>) {
        val host = this
        settingsSectionTitleItem {
            id("msisdn")
            titleResId(R.string.settings_discovery_msisdn_title)
        }

        when (msisdns) {
            is Incomplete -> {
                loadingItem {
                    id("msisdnLoading")
                }
            }
            is Fail       -> {
                settingsInfoItem {
                    id("msisdnListError")
                    helperText(msisdns.error.message)
                }
            }
            is Success    -> {
                if (msisdns().isEmpty()) {
                    settingsInfoItem {
                        id("no_msisdn")
                        helperText(host.stringProvider.getString(R.string.settings_discovery_no_msisdn))
                    }
                } else {
                    msisdns().forEach { buildMsisdn(it) }
                }
            }
        }
    }

    private fun buildMsisdn(pidInfo: PidInfo) {
        val host = this
        val phoneNumber = pidInfo.threePid.getFormattedValue()

        buildThreePid(pidInfo, phoneNumber)

        if (pidInfo.isShared is Fail) {
            buildSharedFail(pidInfo)
        } else if (pidInfo.isShared() == SharedState.BINDING_IN_PROGRESS) {
            val errorText = if (pidInfo.finalRequest is Fail) {
                val error = pidInfo.finalRequest.error
                // Deal with error 500
                // Ref: https://github.com/matrix-org/sydent/issues/292
                if (error is Failure.ServerError
                        && error.httpCode == HttpsURLConnection.HTTP_INTERNAL_ERROR /* 500 */) {
                    stringProvider.getString(R.string.settings_text_message_sent_wrong_code)
                } else {
                    errorFormatter.toHumanReadable(error)
                }
            } else {
                null
            }
            settingsEditTextItem {
                id("msisdnVerification${pidInfo.threePid.value}")
                descriptionText(host.stringProvider.getString(R.string.settings_text_message_sent, phoneNumber))
                errorText(errorText)
                inProgress(pidInfo.finalRequest is Loading)
                interactionListener(object : SettingsEditTextItem.Listener {
                    override fun onValidate() {
                        val code = host.codes[pidInfo.threePid]
                        if (pidInfo.threePid is ThreePid.Msisdn && code != null) {
                            host.listener?.sendMsisdnVerificationCode(pidInfo.threePid, code)
                        }
                    }

                    override fun onTextChange(text: String) {
                        host.codes[pidInfo.threePid] = text
                    }
                })
            }
            buildContinueCancel(pidInfo.threePid)
        }
    }

    private fun buildThreePid(pidInfo: PidInfo, title: String = pidInfo.threePid.value) {
        val host = this
        settingsTextButtonSingleLineItem {
            id(pidInfo.threePid.value)
            title(title)
            colorProvider(host.colorProvider)
            stringProvider(host.stringProvider)
            when (pidInfo.isShared) {
                is Loading -> {
                    buttonIndeterminate(true)
                }
                is Fail    -> {
                    buttonType(ButtonType.NORMAL)
                    buttonStyle(ButtonStyle.DESTRUCTIVE)
                    buttonTitle(host.stringProvider.getString(R.string.global_retry))
                    iconMode(IconMode.ERROR)
                    buttonClickListener { host.listener?.onTapRetryToRetrieveBindings() }
                }
                is Success -> when (pidInfo.isShared()) {
                    SharedState.SHARED,
                    SharedState.NOT_SHARED          -> {
                        buttonType(ButtonType.SWITCH)
                        checked(pidInfo.isShared() == SharedState.SHARED)
                        switchChangeListener { _, checked ->
                            if (checked) {
                                host.listener?.onTapShare(pidInfo.threePid)
                            } else {
                                host.listener?.onTapRevoke(pidInfo.threePid)
                            }
                        }
                    }
                    SharedState.BINDING_IN_PROGRESS -> {
                        buttonType(ButtonType.NO_BUTTON)
                        when (pidInfo.finalRequest) {
                            is Incomplete -> iconMode(IconMode.INFO)
                            is Fail       -> iconMode(IconMode.ERROR)
                            else          -> iconMode(IconMode.NONE)
                        }
                    }
                }
            }
        }
    }

    private fun buildSharedFail(pidInfo: PidInfo) {
        val host = this
        settingsInformationItem {
            id("info${pidInfo.threePid.value}")
            colorProvider(host.colorProvider)
            textColorId(R.color.vector_error_color)
            message((pidInfo.isShared as? Fail)?.error?.message ?: "")
        }
    }

    private fun buildContinueCancel(threePid: ThreePid) {
        val host = this
        settingsContinueCancelItem {
            id("bottom${threePid.value}")
            continueOnClick {
                when (threePid) {
                    is ThreePid.Email  -> {
                        host.listener?.checkEmailVerification(threePid)
                    }
                    is ThreePid.Msisdn -> {
                        val code = host.codes[threePid]
                        if (code != null) {
                            host.listener?.sendMsisdnVerificationCode(threePid, code)
                        }
                    }
                }
            }
            cancelOnClick {
                host.listener?.cancelBinding(threePid)
            }
        }
    }

    interface Listener {
        fun openIdentityServerTerms()
        fun onTapRevoke(threePid: ThreePid)
        fun onTapShare(threePid: ThreePid)
        fun checkEmailVerification(threePid: ThreePid.Email)
        fun cancelBinding(threePid: ThreePid)
        fun sendMsisdnVerificationCode(threePid: ThreePid.Msisdn, code: String)
        fun onTapChangeIdentityServer()
        fun onTapDisconnectIdentityServer()
        fun onTapUpdateUserConsent(newValue: Boolean)
        fun onTapRetryToRetrieveBindings()
    }
}

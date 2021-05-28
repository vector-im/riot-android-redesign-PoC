/*
 * Copyright 2020 New Vector Ltd
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
package im.vector.app.features.settings.devices

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.ItemStyle
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.ui.views.toDrawableRes
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationActionItem
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import timber.log.Timber
import javax.inject.Inject

class DeviceVerificationInfoBottomSheetController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider)
    : TypedEpoxyController<DeviceVerificationInfoBottomSheetViewState>() {

    var callback: Callback? = null

    override fun buildModels(data: DeviceVerificationInfoBottomSheetViewState?) {
        val cryptoDeviceInfo = data?.cryptoDeviceInfo?.invoke()
        when {
            cryptoDeviceInfo != null           -> {
                // It's a E2E capable device
                handleE2ECapableDevice(data, cryptoDeviceInfo)
            }
            data?.deviceInfo?.invoke() != null -> {
                // It's a non E2E capable device
                handleNonE2EDevice(data)
            }
            else                               -> {
                loadingItem {
                    id("loading")
                }
            }
        }
    }

    private fun handleE2ECapableDevice(data: DeviceVerificationInfoBottomSheetViewState, cryptoDeviceInfo: CryptoDeviceInfo) {
        val shield = TrustUtils.shieldForTrust(
                currentDevice = data.isMine,
                trustMSK = data.accountCrossSigningIsTrusted,
                legacyMode = !data.hasAccountCrossSigning,
                deviceTrustLevel = cryptoDeviceInfo.trustLevel
        ).toDrawableRes()

        if (data.hasAccountCrossSigning) {
            // Cross Signing is enabled
            handleE2EWithCrossSigning(data, cryptoDeviceInfo, shield)
        } else {
            handleE2EInLegacy(data, cryptoDeviceInfo, shield)
        }

        // COMMON ACTIONS (Rename / signout)
        addGenericDeviceManageActions(data, cryptoDeviceInfo.deviceId)
    }

    private fun handleE2EWithCrossSigning(data: DeviceVerificationInfoBottomSheetViewState, cryptoDeviceInfo: CryptoDeviceInfo, shield: Int) {
        val isMine = data.isMine
        val currentSessionIsTrusted = data.accountCrossSigningIsTrusted
        Timber.v("handleE2EWithCrossSigning $isMine, $cryptoDeviceInfo, $shield")
        val host = this

        if (isMine) {
            if (currentSessionIsTrusted) {
                genericItem {
                    id("trust${cryptoDeviceInfo.deviceId}")
                    style(ItemStyle.BIG_TEXT)
                    titleIconResourceId(shield)
                    title(host.stringProvider.getString(R.string.encryption_information_verified))
                    description(host.stringProvider.getString(R.string.settings_active_sessions_verified_device_desc))
                }
            } else if (data.canVerifySession) {
                // You need to complete security, only if there are other session(s) available, or if 4S contains secrets
                genericItem {
                    id("trust${cryptoDeviceInfo.deviceId}")
                    style(ItemStyle.BIG_TEXT)
                    titleIconResourceId(shield)
                    title(host.stringProvider.getString(R.string.crosssigning_verify_this_session))
                    if (data.hasOtherSessions) {
                        description(host.stringProvider.getString(R.string.confirm_your_identity))
                    } else {
                        description(host.stringProvider.getString(R.string.confirm_your_identity_quad_s))
                    }
                }
            }
        } else {
            if (!currentSessionIsTrusted) {
                // we don't know if this session is trusted...
                // for now we show nothing?
            } else {
                // we rely on cross signing status
                val trust = cryptoDeviceInfo.trustLevel?.isCrossSigningVerified() == true
                if (trust) {
                    genericItem {
                        id("trust${cryptoDeviceInfo.deviceId}")
                        style(ItemStyle.BIG_TEXT)
                        titleIconResourceId(shield)
                        title(host.stringProvider.getString(R.string.encryption_information_verified))
                        description(host.stringProvider.getString(R.string.settings_active_sessions_verified_device_desc))
                    }
                } else {
                    genericItem {
                        id("trust${cryptoDeviceInfo.deviceId}")
                        titleIconResourceId(shield)
                        style(ItemStyle.BIG_TEXT)
                        title(host.stringProvider.getString(R.string.encryption_information_not_verified))
                        description(host.stringProvider.getString(R.string.settings_active_sessions_unverified_device_desc))
                    }
                }
            }
        }

        // DEVICE INFO SECTION
        genericItem {
            id("info${cryptoDeviceInfo.deviceId}")
            title(cryptoDeviceInfo.displayName() ?: "")
            description("(${cryptoDeviceInfo.deviceId})")
        }

        if (isMine && !currentSessionIsTrusted && data.canVerifySession) {
            // Add complete security
            dividerItem {
                id("completeSecurityDiv")
            }
            bottomSheetVerificationActionItem {
                id("completeSecurity")
                title(host.stringProvider.getString(R.string.crosssigning_verify_this_session))
                titleColor(host.colorProvider.getColor(R.color.riotx_accent))
                iconRes(R.drawable.ic_arrow_right)
                iconColor(host.colorProvider.getColor(R.color.riotx_accent))
                listener {
                    host.callback?.onAction(DevicesAction.CompleteSecurity)
                }
            }
        } else if (!isMine) {
            if (currentSessionIsTrusted) {
                // we can propose to verify it
                val isVerified = cryptoDeviceInfo.trustLevel?.crossSigningVerified.orFalse()
                if (!isVerified) {
                    addVerifyActions(cryptoDeviceInfo)
                }
            }
        }
    }

    private fun handleE2EInLegacy(data: DeviceVerificationInfoBottomSheetViewState, cryptoDeviceInfo: CryptoDeviceInfo, shield: Int) {
        val host = this
        // ==== Legacy
        val isMine = data.isMine

        // TRUST INFO SECTION
        if (cryptoDeviceInfo.trustLevel?.isLocallyVerified() == true) {
            genericItem {
                id("trust${cryptoDeviceInfo.deviceId}")
                style(ItemStyle.BIG_TEXT)
                titleIconResourceId(shield)
                title(host.stringProvider.getString(R.string.encryption_information_verified))
                description(host.stringProvider.getString(R.string.settings_active_sessions_verified_device_desc))
            }
        } else {
            genericItem {
                id("trust${cryptoDeviceInfo.deviceId}")
                titleIconResourceId(shield)
                style(ItemStyle.BIG_TEXT)
                title(host.stringProvider.getString(R.string.encryption_information_not_verified))
                description(host.stringProvider.getString(R.string.settings_active_sessions_unverified_device_desc))
            }
        }

        // DEVICE INFO SECTION
        genericItem {
            id("info${cryptoDeviceInfo.deviceId}")
            title(cryptoDeviceInfo.displayName() ?: "")
            description("(${cryptoDeviceInfo.deviceId})")
        }

        // ACTIONS

        if (!isMine) {
            // if it's not the current device you can trigger a verification
            dividerItem {
                id("d1")
            }
            bottomSheetVerificationActionItem {
                id("verify${cryptoDeviceInfo.deviceId}")
                title(host.stringProvider.getString(R.string.verification_verify_device))
                titleColor(host.colorProvider.getColor(R.color.riotx_accent))
                iconRes(R.drawable.ic_arrow_right)
                iconColor(host.colorProvider.getColor(R.color.riotx_accent))
                listener {
                    host.callback?.onAction(DevicesAction.VerifyMyDevice(cryptoDeviceInfo.deviceId))
                }
            }
        }
    }

    private fun addVerifyActions(cryptoDeviceInfo: CryptoDeviceInfo) {
        val host = this
        dividerItem {
            id("verifyDiv")
        }
        bottomSheetVerificationActionItem {
            id("verify_text")
            title(host.stringProvider.getString(R.string.cross_signing_verify_by_text))
            titleColor(host.colorProvider.getColor(R.color.riotx_accent))
            iconRes(R.drawable.ic_arrow_right)
            iconColor(host.colorProvider.getColor(R.color.riotx_accent))
            listener {
                host.callback?.onAction(DevicesAction.VerifyMyDeviceManually(cryptoDeviceInfo.deviceId))
            }
        }
        dividerItem {
            id("verifyDiv2")
        }
        bottomSheetVerificationActionItem {
            id("verify_emoji")
            title(host.stringProvider.getString(R.string.cross_signing_verify_by_emoji))
            titleColor(host.colorProvider.getColor(R.color.riotx_accent))
            iconRes(R.drawable.ic_arrow_right)
            iconColor(host.colorProvider.getColor(R.color.riotx_accent))
            listener {
                host.callback?.onAction(DevicesAction.VerifyMyDevice(cryptoDeviceInfo.deviceId))
            }
        }
    }

    private fun addGenericDeviceManageActions(data: DeviceVerificationInfoBottomSheetViewState, deviceId: String) {
        val host = this
        // Offer delete session if not me
        if (!data.isMine) {
            // Add the delete option
            dividerItem {
                id("manageD1")
            }
            bottomSheetVerificationActionItem {
                id("delete")
                title(host.stringProvider.getString(R.string.settings_active_sessions_signout_device))
                titleColor(host.colorProvider.getColor(R.color.riotx_destructive_accent))
                iconRes(R.drawable.ic_arrow_right)
                iconColor(host.colorProvider.getColor(R.color.riotx_destructive_accent))
                listener {
                    host.callback?.onAction(DevicesAction.Delete(deviceId))
                }
            }
        }

        // Always offer rename
        dividerItem {
            id("manageD2")
        }
        bottomSheetVerificationActionItem {
            id("rename")
            title(host.stringProvider.getString(R.string.rename))
            titleColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
            iconRes(R.drawable.ic_arrow_right)
            iconColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
            listener {
                host.callback?.onAction(DevicesAction.PromptRename(deviceId))
            }
        }
    }

    private fun handleNonE2EDevice(data: DeviceVerificationInfoBottomSheetViewState) {
        val host = this
        val info = data.deviceInfo.invoke() ?: return
        genericItem {
            id("info${info.deviceId}")
            title(info.displayName ?: "")
            description("(${info.deviceId})")
        }

        genericFooterItem {
            id("infoCrypto${info.deviceId}")
            text(host.stringProvider.getString(R.string.settings_failed_to_get_crypto_device_info))
        }

        info.deviceId?.let { addGenericDeviceManageActions(data, it) }
    }

    interface Callback {
        fun onAction(action: DevicesAction)
    }
}

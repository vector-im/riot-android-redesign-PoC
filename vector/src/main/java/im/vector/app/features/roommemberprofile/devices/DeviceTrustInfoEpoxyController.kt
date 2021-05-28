/*
 * Copyright 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package im.vector.app.features.roommemberprofile.devices

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.ItemStyle
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.ui.list.genericItemWithValue
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationActionItem
import im.vector.app.features.settings.VectorPreferences
import me.gujun.android.span.span
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import javax.inject.Inject

class DeviceTrustInfoEpoxyController @Inject constructor(private val stringProvider: StringProvider,
                                                         private val colorProvider: ColorProvider,
                                                         private val dimensionConverter: DimensionConverter,
                                                         private val vectorPreferences: VectorPreferences)
    : TypedEpoxyController<DeviceListViewState>() {

    interface InteractionListener {
        fun onVerifyManually(device: CryptoDeviceInfo)
    }

    var interactionListener: InteractionListener? = null

    override fun buildModels(data: DeviceListViewState?) {
        val host = this
        data?.selectedDevice?.let {
            val isVerified = it.trustLevel?.isVerified() == true
            genericItem {
                id("title")
                style(ItemStyle.BIG_TEXT)
                titleIconResourceId(if (isVerified) R.drawable.ic_shield_trusted else R.drawable.ic_shield_warning)
                title(
                        host.stringProvider.getString(
                                if (isVerified) R.string.verification_profile_verified else R.string.verification_profile_warning
                        )
                )
            }
            genericFooterItem {
                id("desc")
                centered(false)
                textColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
                apply {
                    if (isVerified) {
                        // TODO FORMAT
                        text(host.stringProvider.getString(R.string.verification_profile_device_verified_because,
                                data.userItem?.displayName ?: "",
                                data.userItem?.id ?: ""))
                    } else {
                        // TODO what if mine
                        text(host.stringProvider.getString(R.string.verification_profile_device_new_signing,
                                data.userItem?.displayName ?: "",
                                data.userItem?.id ?: ""))
                    }
                }
//                    text(stringProvider.getString(R.string.verification_profile_device_untrust_info))
            }

            genericItemWithValue {
                id(it.deviceId)
                titleIconResourceId(if (isVerified) R.drawable.ic_shield_trusted else R.drawable.ic_shield_warning)
                title(
                        span {
                            +(it.displayName() ?: "")
                            span {
                                text = " (${it.deviceId})"
                                textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                textSize = host.dimensionConverter.spToPx(14)
                            }
                        }
                )
            }

            if (!isVerified) {
                genericFooterItem {
                    id("warn")
                    centered(false)
                    textColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
                    text(host.stringProvider.getString(R.string.verification_profile_device_untrust_info))
                }

                bottomSheetVerificationActionItem {
                    id("verify")
                    title(host.stringProvider.getString(R.string.cross_signing_verify_by_emoji))
                    titleColor(host.colorProvider.getColor(R.color.riotx_accent))
                    iconRes(R.drawable.ic_arrow_right)
                    iconColor(host.colorProvider.getColor(R.color.riotx_accent))
                    listener {
                        host.interactionListener?.onVerifyManually(it)
                    }
                }
            }
        }
    }
}

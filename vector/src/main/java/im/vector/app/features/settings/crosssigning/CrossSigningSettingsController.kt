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
 */
package im.vector.app.features.settings.crosssigning

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericButtonItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.ui.list.genericItemWithValue
import im.vector.app.core.ui.list.genericPositiveButtonItem
import im.vector.app.core.utils.DebouncedClickListener
import im.vector.app.core.utils.DimensionConverter
import me.gujun.android.span.span
import javax.inject.Inject

class CrossSigningSettingsController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val dimensionConverter: DimensionConverter
) : TypedEpoxyController<CrossSigningSettingsViewState>() {

    interface InteractionListener {
        fun didTapInitializeCrossSigning()
    }

    var interactionListener: InteractionListener? = null

    override fun buildModels(data: CrossSigningSettingsViewState?) {
        if (data == null) return
        val host = this
        when {
            data.xSigningKeyCanSign        -> {
                genericItem {
                    id("can")
                    titleIconResourceId(R.drawable.ic_shield_trusted)
                    title(host.stringProvider.getString(R.string.encryption_information_dg_xsigning_complete))
                }
                genericButtonItem {
                    id("Reset")
                    text(host.stringProvider.getString(R.string.reset_cross_signing))
                    buttonClickAction(DebouncedClickListener({
                        host.interactionListener?.didTapInitializeCrossSigning()
                    }))
                }
            }
            data.xSigningKeysAreTrusted    -> {
                genericItem {
                    id("trusted")
                    titleIconResourceId(R.drawable.ic_shield_custom)
                    title(host.stringProvider.getString(R.string.encryption_information_dg_xsigning_trusted))
                }
                genericButtonItem {
                    id("Reset")
                    text(host.stringProvider.getString(R.string.reset_cross_signing))
                    buttonClickAction(DebouncedClickListener({
                        host.interactionListener?.didTapInitializeCrossSigning()
                    }))
                }
            }
            data.xSigningIsEnableInAccount -> {
                genericItem {
                    id("enable")
                    titleIconResourceId(R.drawable.ic_shield_black)
                    title(host.stringProvider.getString(R.string.encryption_information_dg_xsigning_not_trusted))
                }
                genericButtonItem {
                    id("Reset")
                    text(host.stringProvider.getString(R.string.reset_cross_signing))
                    buttonClickAction(DebouncedClickListener({
                        host.interactionListener?.didTapInitializeCrossSigning()
                    }))
                }
            }
            else                           -> {
                genericItem {
                    id("not")
                    title(host.stringProvider.getString(R.string.encryption_information_dg_xsigning_disabled))
                }

                genericPositiveButtonItem {
                    id("Initialize")
                    text(host.stringProvider.getString(R.string.initialize_cross_signing))
                    buttonClickAction(DebouncedClickListener({
                        host.interactionListener?.didTapInitializeCrossSigning()
                    }))
                }
            }
        }

        val crossSigningKeys = data.crossSigningInfo

        crossSigningKeys?.masterKey()?.let {
            genericItemWithValue {
                id("msk")
                titleIconResourceId(R.drawable.key_small)
                title(
                        span {
                            +"Master Key:\n"
                            span {
                                text = it.unpaddedBase64PublicKey ?: ""
                                textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                textSize = host.dimensionConverter.spToPx(12)
                            }
                        }
                )
            }
        }
        crossSigningKeys?.userKey()?.let {
            genericItemWithValue {
                id("usk")
                titleIconResourceId(R.drawable.key_small)
                title(
                        span {
                            +"User Key:\n"
                            span {
                                text = it.unpaddedBase64PublicKey ?: ""
                                textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                textSize = host.dimensionConverter.spToPx(12)
                            }
                        }
                )
            }
        }
        crossSigningKeys?.selfSigningKey()?.let {
            genericItemWithValue {
                id("ssk")
                titleIconResourceId(R.drawable.key_small)
                title(
                        span {
                            +"Self Signed Key:\n"
                            span {
                                text = it.unpaddedBase64PublicKey ?: ""
                                textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                textSize = host.dimensionConverter.spToPx(12)
                            }
                        }
                )
            }
        }
    }
}

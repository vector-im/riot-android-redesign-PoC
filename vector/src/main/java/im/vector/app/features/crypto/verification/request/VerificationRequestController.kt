/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.crypto.verification.request

import androidx.core.text.toSpannable
import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.colorizeMatchingText
import im.vector.app.features.crypto.verification.VerificationBottomSheetViewState
import im.vector.app.features.crypto.verification.epoxy.bottomSheetSelfWaitItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationActionItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationNoticeItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationWaitingItem
import javax.inject.Inject

class VerificationRequestController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider
) : EpoxyController() {

    var listener: Listener? = null

    private var viewState: VerificationBottomSheetViewState? = null

    fun update(viewState: VerificationBottomSheetViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    override fun buildModels() {
        val state = viewState ?: return
        val matrixItem = viewState?.otherUserMxItem ?: return
        val host = this

        if (state.selfVerificationMode) {
            if (state.hasAnyOtherSession) {
                bottomSheetVerificationNoticeItem {
                    id("notice")
                    notice(host.stringProvider.getString(R.string.verification_open_other_to_verify))
                }

                bottomSheetSelfWaitItem {
                    id("waiting")
                }

                dividerItem {
                    id("sep")
                }
            }

            if (state.quadSContainsSecrets) {
                val subtitle = if (state.hasAnyOtherSession) {
                    stringProvider.getString(R.string.verification_use_passphrase)
                } else {
                    null
                }
                bottomSheetVerificationActionItem {
                    id("passphrase")
                    title(host.stringProvider.getString(R.string.verification_cannot_access_other_session))
                    titleColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
                    subTitle(subtitle)
                    iconRes(R.drawable.ic_arrow_right)
                    iconColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
                    listener { host.listener?.onClickRecoverFromPassphrase() }
                }
            }

            dividerItem {
                id("sep1")
            }

            bottomSheetVerificationActionItem {
                id("skip")
                title(host.stringProvider.getString(R.string.skip))
                titleColor(host.colorProvider.getColor(R.color.riotx_destructive_accent))
                iconRes(R.drawable.ic_arrow_right)
                iconColor(host.colorProvider.getColor(R.color.riotx_destructive_accent))
                listener { host.listener?.onClickSkip() }
            }
        } else {
            val styledText =
                    if (state.isMe) {
                        stringProvider.getString(R.string.verify_new_session_notice)
                    } else {
                        matrixItem.let {
                            stringProvider.getString(R.string.verification_request_notice, it.id)
                                    .toSpannable()
                                    .colorizeMatchingText(it.id, colorProvider.getColorFromAttribute(R.attr.vctr_notice_text_color))
                        }
                    }

            bottomSheetVerificationNoticeItem {
                id("notice")
                notice(styledText)
            }

            dividerItem {
                id("sep")
            }

            when (val pr = state.pendingRequest) {
                is Uninitialized -> {
                    bottomSheetVerificationActionItem {
                        id("start")
                        title(host.stringProvider.getString(R.string.start_verification))
                        titleColor(host.colorProvider.getColor(R.color.riotx_accent))
                        subTitle(host.stringProvider.getString(R.string.verification_request_start_notice))
                        iconRes(R.drawable.ic_arrow_right)
                        iconColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
                        listener { host.listener?.onClickOnVerificationStart() }
                    }
                }
                is Loading       -> {
                    bottomSheetVerificationWaitingItem {
                        id("waiting")
                        title(host.stringProvider.getString(R.string.verification_request_waiting_for, matrixItem.getBestName()))
                    }
                }
                is Success       -> {
                    if (!pr.invoke().isReady) {
                        if (state.isMe) {
                            bottomSheetVerificationWaitingItem {
                                id("waiting")
                                title(host.stringProvider.getString(R.string.verification_request_waiting))
                            }
                        } else {
                            bottomSheetVerificationWaitingItem {
                                id("waiting")
                                title(host.stringProvider.getString(R.string.verification_request_waiting_for, matrixItem.getBestName()))
                            }
                        }
                    }
                }
            }
        }

        if (state.isMe && state.currentDeviceCanCrossSign && !state.selfVerificationMode) {
            dividerItem {
                id("sep_notMe")
            }

            bottomSheetVerificationActionItem {
                id("wasnote")
                title(host.stringProvider.getString(R.string.verify_new_session_was_not_me))
                titleColor(host.colorProvider.getColor(R.color.riotx_destructive_accent))
                subTitle(host.stringProvider.getString(R.string.verify_new_session_compromized))
                iconRes(R.drawable.ic_arrow_right)
                iconColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
                listener { host.listener?.onClickOnWasNotMe() }
            }
        }
    }

    interface Listener {
        fun onClickOnVerificationStart()
        fun onClickOnWasNotMe()
        fun onClickRecoverFromPassphrase()
        fun onClickDismiss()
        fun onClickSkip()
    }
}

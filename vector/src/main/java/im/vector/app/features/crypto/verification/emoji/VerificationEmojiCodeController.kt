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

package im.vector.app.features.crypto.verification.emoji

import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Success
import im.vector.app.R
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationActionItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationDecimalCodeItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationEmojisItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationNoticeItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationWaitingItem
import javax.inject.Inject

class VerificationEmojiCodeController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val errorFormatter: ErrorFormatter
) : EpoxyController() {

    var listener: Listener? = null

    private var viewState: VerificationEmojiCodeViewState? = null

    fun update(viewState: VerificationEmojiCodeViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    override fun buildModels() {
        val state = viewState ?: return

        if (state.supportsEmoji) {
            buildEmojiItem(state)
        } else {
            buildDecimal(state)
        }
    }

    private fun buildEmojiItem(state: VerificationEmojiCodeViewState) {
        val host = this
        when (val emojiDescription = state.emojiDescription) {
            is Success -> {
                bottomSheetVerificationNoticeItem {
                    id("notice")
                    notice(host.stringProvider.getString(R.string.verification_emoji_notice))
                }

                bottomSheetVerificationEmojisItem {
                    id("emojis")
                    emojiRepresentation0(emojiDescription()[0])
                    emojiRepresentation1(emojiDescription()[1])
                    emojiRepresentation2(emojiDescription()[2])
                    emojiRepresentation3(emojiDescription()[3])
                    emojiRepresentation4(emojiDescription()[4])
                    emojiRepresentation5(emojiDescription()[5])
                    emojiRepresentation6(emojiDescription()[6])
                }

                buildActions(state)
            }
            is Fail    -> {
                errorWithRetryItem {
                    id("error")
                    text(host.errorFormatter.toHumanReadable(emojiDescription.error))
                }
            }
            else       -> {
                bottomSheetVerificationWaitingItem {
                    id("waiting")
                    title(host.stringProvider.getString(R.string.please_wait))
                }
            }
        }
    }

    private fun buildDecimal(state: VerificationEmojiCodeViewState) {
        val host = this
        when (val decimalDescription = state.decimalDescription) {
            is Success -> {
                bottomSheetVerificationNoticeItem {
                    id("notice")
                    notice(host.stringProvider.getString(R.string.verification_code_notice))
                }

                bottomSheetVerificationDecimalCodeItem {
                    id("decimal")
                    code(state.decimalDescription.invoke() ?: "")
                }

                buildActions(state)
            }
            is Fail    -> {
                errorWithRetryItem {
                    id("error")
                    text(host.errorFormatter.toHumanReadable(decimalDescription.error))
                }
            }
            else       -> {
                bottomSheetVerificationWaitingItem {
                    id("waiting")
                    title(host.stringProvider.getString(R.string.please_wait))
                }
            }
        }
    }

    private fun buildActions(state: VerificationEmojiCodeViewState) {
        val host = this
        dividerItem {
            id("sep0")
        }

        if (state.isWaitingFromOther) {
            bottomSheetVerificationWaitingItem {
                id("waiting")
                title(host.stringProvider.getString(R.string.verification_request_waiting_for, state.otherUser?.getBestName() ?: ""))
            }
        } else {
            bottomSheetVerificationActionItem {
                id("ko")
                title(host.stringProvider.getString(R.string.verification_sas_do_not_match))
                titleColor(host.colorProvider.getColor(R.color.vector_error_color))
                iconRes(R.drawable.ic_check_off)
                iconColor(host.colorProvider.getColor(R.color.vector_error_color))
                listener { host.listener?.onDoNotMatchButtonTapped() }
            }
            dividerItem {
                id("sep1")
            }
            bottomSheetVerificationActionItem {
                id("ok")
                title(host.stringProvider.getString(R.string.verification_sas_match))
                titleColor(host.colorProvider.getColor(R.color.riotx_accent))
                iconRes(R.drawable.ic_check_on)
                iconColor(host.colorProvider.getColor(R.color.riotx_accent))
                listener { host.listener?.onMatchButtonTapped() }
            }
        }
    }

    interface Listener {
        fun onDoNotMatchButtonTapped()
        fun onMatchButtonTapped()
    }
}

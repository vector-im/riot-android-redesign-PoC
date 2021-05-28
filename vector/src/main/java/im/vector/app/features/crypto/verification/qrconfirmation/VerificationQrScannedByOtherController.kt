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

package im.vector.app.features.crypto.verification.qrconfirmation

import com.airbnb.epoxy.EpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.crypto.verification.VerificationBottomSheetViewState
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationActionItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationBigImageItem
import im.vector.app.features.crypto.verification.epoxy.bottomSheetVerificationNoticeItem
import org.matrix.android.sdk.api.crypto.RoomEncryptionTrustLevel
import javax.inject.Inject

class VerificationQrScannedByOtherController @Inject constructor(
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
        val host = this

        bottomSheetVerificationNoticeItem {
            id("notice")
            apply {
                if (state.isMe) {
                    notice(host.stringProvider.getString(R.string.qr_code_scanned_self_verif_notice))
                } else {
                    val name = state.otherUserMxItem?.getBestName() ?: ""
                    notice(host.stringProvider.getString(R.string.qr_code_scanned_by_other_notice, name))
                }
            }
        }

        bottomSheetVerificationBigImageItem {
            id("image")
            roomEncryptionTrustLevel(RoomEncryptionTrustLevel.Trusted)
        }

        dividerItem {
            id("sep0")
        }

        bottomSheetVerificationActionItem {
            id("deny")
            title(host.stringProvider.getString(R.string.qr_code_scanned_by_other_no))
            titleColor(host.colorProvider.getColor(R.color.vector_error_color))
            iconRes(R.drawable.ic_check_off)
            iconColor(host.colorProvider.getColor(R.color.vector_error_color))
            listener { host.listener?.onUserDeniesQrCodeScanned() }
        }

        dividerItem {
            id("sep1")
        }

        bottomSheetVerificationActionItem {
            id("confirm")
            title(host.stringProvider.getString(R.string.qr_code_scanned_by_other_yes))
            titleColor(host.colorProvider.getColor(R.color.riotx_accent))
            iconRes(R.drawable.ic_check_on)
            iconColor(host.colorProvider.getColor(R.color.riotx_accent))
            listener { host.listener?.onUserConfirmsQrCodeScanned() }
        }
    }

    interface Listener {
        fun onUserConfirmsQrCodeScanned()
        fun onUserDeniesQrCodeScanned()
    }
}

/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.spaces.create

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.form.formEditTextItem
import im.vector.app.features.form.formEditableSquareAvatarItem
import im.vector.app.features.form.formMultiLineEditTextItem
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

class SpaceDetailEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer
) : TypedEpoxyController<CreateSpaceState>() {

    var listener: Listener? = null

//    var shouldForceFocusOnce = true

    override fun buildModels(data: CreateSpaceState?) {
        val host = this
        genericFooterItem {
            id("info_help")
            text(
                    if (data?.spaceType == SpaceType.Public) {
                        host.stringProvider.getString(R.string.create_spaces_details_public_header)
                    } else {
                        host.stringProvider.getString(R.string.create_spaces_details_private_header)
                    }
            )
        }

        formEditableSquareAvatarItem {
            id("avatar")
            enabled(true)
            imageUri(data?.avatarUri)
            avatarRenderer(host.avatarRenderer)
            matrixItem(data?.name?.let { MatrixItem.RoomItem("!", it, null).takeIf { !it.displayName.isNullOrBlank() } })
            clickListener { host.listener?.onAvatarChange() }
            deleteListener { host.listener?.onAvatarDelete() }
        }

        formEditTextItem {
            id("name")
            enabled(true)
            value(data?.name)
            hint(host.stringProvider.getString(R.string.create_room_name_hint))
            showBottomSeparator(false)
            errorMessage(data?.nameInlineError)
//            onBind { _, view, _ ->
//                if (shouldForceFocusOnce && data?.name.isNullOrBlank()) {
//                    shouldForceFocusOnce = false
//                    // sad face :(
//                    view.textInputEditText.post {
//                        view.textInputEditText.showKeyboard(true)
//                    }
//                }
//            }
            onTextChange { text ->
                host.listener?.onNameChange(text)
            }
        }

        formMultiLineEditTextItem {
            id("topic")
            enabled(true)
            value(data?.topic)
            hint(host.stringProvider.getString(R.string.create_space_topic_hint))
            showBottomSeparator(false)
            textSizeSp(16)
            onTextChange { text ->
                host.listener?.onTopicChange(text)
            }
        }
    }

    interface Listener {
        fun onAvatarDelete()
        fun onAvatarChange()
        fun onNameChange(newName: String)
        fun onTopicChange(newTopic: String)
    }
}

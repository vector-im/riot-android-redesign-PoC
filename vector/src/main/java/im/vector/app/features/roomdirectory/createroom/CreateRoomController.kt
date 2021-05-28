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

package im.vector.app.features.roomdirectory.createroom

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.discovery.settingsSectionTitleItem
import im.vector.app.features.form.formAdvancedToggleItem
import im.vector.app.features.form.formEditTextItem
import im.vector.app.features.form.formEditableAvatarItem
import im.vector.app.features.form.formSubmitButtonItem
import im.vector.app.features.form.formSwitchItem
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import javax.inject.Inject

class CreateRoomController @Inject constructor(
        private val stringProvider: StringProvider,
        private val roomAliasErrorFormatter: RoomAliasErrorFormatter
) : TypedEpoxyController<CreateRoomViewState>() {

    var listener: Listener? = null

    var index = 0

    override fun buildModels(viewState: CreateRoomViewState) {
        // display the form
        buildForm(viewState, viewState.asyncCreateRoomRequest !is Loading)
    }

    private fun buildForm(viewState: CreateRoomViewState, enableFormElement: Boolean) {
        val host = this
        formEditableAvatarItem {
            id("avatar")
            enabled(enableFormElement)
            imageUri(viewState.avatarUri)
            clickListener { host.listener?.onAvatarChange() }
            deleteListener { host.listener?.onAvatarDelete() }
        }
        settingsSectionTitleItem {
            id("nameSection")
            titleResId(R.string.create_room_name_section)
        }
        formEditTextItem {
            id("name")
            enabled(enableFormElement)
            value(viewState.roomName)
            hint(host.stringProvider.getString(R.string.create_room_name_hint))

            onTextChange { text ->
                host.listener?.onNameChange(text)
            }
        }
        settingsSectionTitleItem {
            id("topicSection")
            titleResId(R.string.create_room_topic_section)
        }
        formEditTextItem {
            id("topic")
            enabled(enableFormElement)
            value(viewState.roomTopic)
            singleLine(false)
            hint(host.stringProvider.getString(R.string.create_room_topic_hint))

            onTextChange { text ->
                host.listener?.onTopicChange(text)
            }
        }
        settingsSectionTitleItem {
            id("settingsSection")
            titleResId(R.string.create_room_settings_section)
        }
        formSwitchItem {
            id("public")
            enabled(enableFormElement)
            title(host.stringProvider.getString(R.string.create_room_public_title))
            summary(host.stringProvider.getString(R.string.create_room_public_description))
            switchChecked(viewState.roomVisibilityType is CreateRoomViewState.RoomVisibilityType.Public)
            showDivider(viewState.roomVisibilityType !is CreateRoomViewState.RoomVisibilityType.Public)

            listener { value ->
                host.listener?.setIsPublic(value)
            }
        }
        if (viewState.roomVisibilityType is CreateRoomViewState.RoomVisibilityType.Public) {
            // Room alias for public room
            roomAliasEditItem {
                id("alias")
                enabled(enableFormElement)
                value(viewState.roomVisibilityType.aliasLocalPart)
                homeServer(":" + viewState.homeServerName)
                errorMessage(
                        host.roomAliasErrorFormatter.format(
                                (((viewState.asyncCreateRoomRequest as? Fail)?.error) as? CreateRoomFailure.AliasError)?.aliasError)
                )
                onTextChange { value ->
                    host.listener?.setAliasLocalPart(value)
                }
            }
        } else {
            // Room encryption for private room
            formSwitchItem {
                id("encryption")
                enabled(enableFormElement)
                title(host.stringProvider.getString(R.string.create_room_encryption_title))
                summary(
                        if (viewState.hsAdminHasDisabledE2E) {
                            host.stringProvider.getString(R.string.settings_hs_admin_e2e_disabled)
                        } else {
                            host.stringProvider.getString(R.string.create_room_encryption_description)
                        }
                )
                switchChecked(viewState.isEncrypted)

                listener { value ->
                    host.listener?.setIsEncrypted(value)
                }
            }
        }
        formAdvancedToggleItem {
            id("showAdvanced")
            title(host.stringProvider.getString(if (viewState.showAdvanced) R.string.hide_advanced else R.string.show_advanced))
            expanded(!viewState.showAdvanced)
            listener { host.listener?.toggleShowAdvanced() }
        }
        if (viewState.showAdvanced) {
            formSwitchItem {
                id("federation")
                enabled(enableFormElement)
                title(host.stringProvider.getString(R.string.create_room_disable_federation_title, viewState.homeServerName))
                summary(host.stringProvider.getString(R.string.create_room_disable_federation_description))
                switchChecked(viewState.disableFederation)
                showDivider(false)
                listener { value -> host.listener?.setDisableFederation(value) }
            }
        }
        formSubmitButtonItem {
            id("submit")
            enabled(enableFormElement)
            buttonTitleId(R.string.create_room_action_create)
            buttonClickListener { host.listener?.submit() }
        }
    }

    interface Listener {
        fun onAvatarDelete()
        fun onAvatarChange()
        fun onNameChange(newName: String)
        fun onTopicChange(newTopic: String)
        fun setIsPublic(isPublic: Boolean)
        fun setAliasLocalPart(aliasLocalPart: String)
        fun setIsEncrypted(isEncrypted: Boolean)
        fun toggleShowAdvanced()
        fun setDisableFederation(disableFederation: Boolean)
        fun submit()
    }
}

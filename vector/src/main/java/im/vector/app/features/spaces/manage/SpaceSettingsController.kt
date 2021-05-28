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

package im.vector.app.features.spaces.manage

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.epoxy.profiles.buildProfileSection
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.form.formEditTextItem
import im.vector.app.features.form.formEditableSquareAvatarItem
import im.vector.app.features.form.formMultiLineEditTextItem
import im.vector.app.features.form.formSwitchItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomprofile.settings.RoomSettingsViewState
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class SpaceSettingsController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer,
        colorProvider: ColorProvider,
        private val vectorPreferences: VectorPreferences
) : TypedEpoxyController<RoomSettingsViewState>() {

    interface Callback {
        // Delete the avatar, or cancel an avatar change
        fun onAvatarDelete()
        fun onAvatarChange()
        fun onNameChanged(name: String)
        fun onTopicChanged(topic: String)
        fun onHistoryVisibilityClicked()
        fun onJoinRuleClicked()
        fun onToggleGuestAccess()
        fun onDevTools()
        fun onDevRoomSettings()
        fun onManageRooms()
        fun setIsPublic(public: Boolean)
    }

    private val dividerColor = colorProvider.getColorFromAttribute(R.attr.vctr_list_divider_color)

    var callback: Callback? = null

    override fun buildModels(data: RoomSettingsViewState?) {
        val roomSummary = data?.roomSummary?.invoke() ?: return
        val host = this

        formEditableSquareAvatarItem {
            id("avatar")
            enabled(data.actionPermissions.canChangeAvatar)
            when (val avatarAction = data.avatarAction) {
                RoomSettingsViewState.AvatarAction.None -> {
                    // Use the current value
                    avatarRenderer(host.avatarRenderer)
                    // We do not want to use the fallback avatar url, which can be the other user avatar, or the current user avatar.
                    matrixItem(roomSummary.toMatrixItem().copy(avatarUrl = data.currentRoomAvatarUrl))
                }
                RoomSettingsViewState.AvatarAction.DeleteAvatar ->
                    imageUri(null)
                is RoomSettingsViewState.AvatarAction.UpdateAvatar ->
                    imageUri(avatarAction.newAvatarUri)
            }
            clickListener { host.callback?.onAvatarChange() }
            deleteListener { host.callback?.onAvatarDelete() }
        }

        buildProfileSection(
                stringProvider.getString(R.string.settings)
        )

        formEditTextItem {
            id("name")
            enabled(data.actionPermissions.canChangeName)
            value(data.newName ?: roomSummary.displayName)
            hint(host.stringProvider.getString(R.string.create_room_name_hint))
            showBottomSeparator(false)
            onTextChange { text ->
                host.callback?.onNameChanged(text)
            }
        }

        formMultiLineEditTextItem {
            id("topic")
            enabled(data.actionPermissions.canChangeTopic)
            value(data.newTopic ?: roomSummary.topic)
            hint(host.stringProvider.getString(R.string.create_space_topic_hint))
            showBottomSeparator(false)
            onTextChange { text ->
                host.callback?.onTopicChanged(text)
            }
        }

        if (vectorPreferences.labsUseExperimentalRestricted()) {
            buildProfileAction(
                    id = "joinRule",
                    title = stringProvider.getString(R.string.room_settings_room_access_title),
                    subtitle = data.getJoinRuleWording(stringProvider),
                    dividerColor = dividerColor,
                    divider = true,
                    editable = data.actionPermissions.canChangeJoinRule,
                    action = { if (data.actionPermissions.canChangeJoinRule) callback?.onJoinRuleClicked() }
            )
        } else {
            val isPublic = (data.newRoomJoinRules.newJoinRules ?: data.currentRoomJoinRules) == RoomJoinRules.PUBLIC
            formSwitchItem {
                id("isPublic")
                enabled(data.actionPermissions.canChangeJoinRule)
                title(host.stringProvider.getString(R.string.make_this_space_public))
                switchChecked(isPublic)

                listener { value ->
                    host.callback?.setIsPublic(value)
                }
            }
        }

        buildProfileAction(
                id = "manage_rooms",
                title = stringProvider.getString(R.string.space_settings_manage_rooms),
                // subtitle = data.getJoinRuleWording(stringProvider),
                dividerColor = dividerColor,
                divider = vectorPreferences.developerMode(),
                editable = data.actionPermissions.canAddChildren,
                action = {
                    if (data.actionPermissions.canAddChildren) callback?.onManageRooms()
                }
        )

        if (vectorPreferences.developerMode()) {
            buildProfileAction(
                    id = "dev_tools",
                    title = stringProvider.getString(R.string.settings_dev_tools),
                    icon = R.drawable.ic_verification_glasses,
                    tintIcon = false,
                    dividerColor = dividerColor,
                    divider = true,
                    action = {
                        callback?.onDevTools()
                    }
            )

            buildProfileAction(
                    id = "room_tools",
                    title = stringProvider.getString(R.string.room_list_quick_actions_room_settings),
                    icon = R.drawable.ic_verification_glasses,
                    tintIcon = false,
                    dividerColor = dividerColor,
                    divider = false,
                    action = {
                        callback?.onDevRoomSettings()
                    }
            )
        }
    }
}

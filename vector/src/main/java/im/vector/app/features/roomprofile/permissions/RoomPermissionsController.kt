/*
 * Copyright 2021 New Vector Ltd
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

package im.vector.app.features.roomprofile.permissions

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Success
import im.vector.app.R
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.epoxy.profiles.buildProfileSection
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.discovery.settingsInfoItem
import im.vector.app.features.form.formAdvancedToggleItem
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.banOrDefault
import org.matrix.android.sdk.api.session.room.model.eventsDefaultOrDefault
import org.matrix.android.sdk.api.session.room.model.inviteOrDefault
import org.matrix.android.sdk.api.session.room.model.kickOrDefault
import org.matrix.android.sdk.api.session.room.model.redactOrDefault
import org.matrix.android.sdk.api.session.room.model.stateDefaultOrDefault
import org.matrix.android.sdk.api.session.room.model.usersDefaultOrDefault
import org.matrix.android.sdk.api.session.room.powerlevels.Role
import javax.inject.Inject

class RoomPermissionsController @Inject constructor(
        private val stringProvider: StringProvider,
        private val roleFormatter: RoleFormatter,
        colorProvider: ColorProvider
) : TypedEpoxyController<RoomPermissionsViewState>() {

    interface Callback {
        fun onEditPermission(editablePermission: EditablePermission, currentRole: Role)
        fun toggleShowAllPermissions()
    }

    var callback: Callback? = null

    private val dividerColor = colorProvider.getColorFromAttribute(R.attr.vctr_list_divider_color)

    // Order is the order applied in the UI
    // Element Web order is not really nice, try to put the settings which are more likely to be updated first
    // And a second section, hidden by default
    private val usefulEditablePermissions = listOf(
            EditablePermission.ChangeRoomAvatar(),
            EditablePermission.ChangeRoomName(),
            EditablePermission.ChangeTopic()
    )

    private val advancedEditablePermissions = listOf(
            EditablePermission.ChangeMainAddressForTheRoom(),

            EditablePermission.DefaultRole(),
            EditablePermission.InviteUsers(),
            EditablePermission.KickUsers(),
            EditablePermission.BanUsers(),

            EditablePermission.SendMessages(),

            EditablePermission.RemoveMessagesSentByOthers(),
            EditablePermission.NotifyEveryone(),

            EditablePermission.ChangeSettings(),
            EditablePermission.ModifyWidgets(),
            EditablePermission.ChangeHistoryVisibility(),
            EditablePermission.ChangePermissions(),
            EditablePermission.SendRoomServerAclEvents(),
            EditablePermission.EnableRoomEncryption(),
            EditablePermission.UpgradeTheRoom()
    )

    init {
        setData(null)
    }

    override fun buildModels(data: RoomPermissionsViewState?) {
        val host = this
        buildProfileSection(
                stringProvider.getString(R.string.room_permissions_title)
        )

        when (val content = data?.currentPowerLevelsContent) {
            is Success -> buildPermissions(data, content())
            else       -> {
                loadingItem {
                    id("loading")
                    loadingText(host.stringProvider.getString(R.string.loading))
                }
            }
        }
    }

    private fun buildPermissions(data: RoomPermissionsViewState, content: PowerLevelsContent) {
        val host = this
        val editable = data.actionPermissions.canChangePowerLevels
        settingsInfoItem {
            id("notice")
            helperText(host.stringProvider.getString(if (editable) R.string.room_permissions_notice else R.string.room_permissions_notice_read_only))
        }

        // Useful permissions
        usefulEditablePermissions.forEach { buildPermission(it, content, editable) }

        // Toggle
        formAdvancedToggleItem {
            id("showAdvanced")
            title(host.stringProvider.getString(if (data.showAdvancedPermissions) R.string.hide_advanced else R.string.show_advanced))
            expanded(!data.showAdvancedPermissions)
            listener { host.callback?.toggleShowAllPermissions() }
        }

        // Advanced permissions
        if (data.showAdvancedPermissions) {
            advancedEditablePermissions.forEach { buildPermission(it, content, editable) }
        }
    }

    private fun buildPermission(editablePermission: EditablePermission, content: PowerLevelsContent, editable: Boolean) {
        val currentRole = getCurrentRole(editablePermission, content)
        buildProfileAction(
                id = editablePermission.labelResId.toString(),
                title = stringProvider.getString(editablePermission.labelResId),
                subtitle = roleFormatter.format(currentRole),
                dividerColor = dividerColor,
                divider = true,
                editable = editable,
                action = {
                    callback
                            ?.takeIf { editable }
                            ?.onEditPermission(editablePermission, currentRole)
                }
        )
    }

    private fun getCurrentRole(editablePermission: EditablePermission, content: PowerLevelsContent): Role {
        val value = when (editablePermission) {
            is EditablePermission.EventTypeEditablePermission -> content.events?.get(editablePermission.eventType) ?: content.stateDefaultOrDefault()
            is EditablePermission.DefaultRole                 -> content.usersDefaultOrDefault()
            is EditablePermission.SendMessages                -> content.eventsDefaultOrDefault()
            is EditablePermission.InviteUsers                 -> content.inviteOrDefault()
            is EditablePermission.ChangeSettings              -> content.stateDefaultOrDefault()
            is EditablePermission.KickUsers                   -> content.kickOrDefault()
            is EditablePermission.BanUsers                    -> content.banOrDefault()
            is EditablePermission.RemoveMessagesSentByOthers  -> content.redactOrDefault()
            is EditablePermission.NotifyEveryone              -> content.notificationLevel(PowerLevelsContent.NOTIFICATIONS_ROOM_KEY)
        }

        return Role.fromValue(
                value,
                when (editablePermission) {
                    is EditablePermission.EventTypeEditablePermission -> content.stateDefaultOrDefault()
                    is EditablePermission.DefaultRole                 -> Role.Default.value
                    is EditablePermission.SendMessages                -> Role.Default.value
                    is EditablePermission.InviteUsers                 -> Role.Moderator.value
                    is EditablePermission.ChangeSettings              -> Role.Moderator.value
                    is EditablePermission.KickUsers                   -> Role.Moderator.value
                    is EditablePermission.BanUsers                    -> Role.Moderator.value
                    is EditablePermission.RemoveMessagesSentByOthers  -> Role.Moderator.value
                    is EditablePermission.NotifyEveryone              -> Role.Moderator.value
                }
        )
    }
}

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

package im.vector.app.features.roomprofile.settings

import android.net.Uri
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.roomprofile.RoomProfileArgs
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class RoomSettingsViewState(
        val roomId: String,
        // Default value: https://matrix.org/docs/spec/client_server/r0.6.1#id88
        val currentHistoryVisibility: RoomHistoryVisibility = RoomHistoryVisibility.SHARED,
        val currentRoomJoinRules: RoomJoinRules = RoomJoinRules.INVITE,
        val currentGuestAccess: GuestAccess? = null,
        val roomSummary: Async<RoomSummary> = Uninitialized,
        val isLoading: Boolean = false,
        val currentRoomAvatarUrl: String? = null,
        val avatarAction: AvatarAction = AvatarAction.None,
        val newName: String? = null,
        val newTopic: String? = null,
        val newHistoryVisibility: RoomHistoryVisibility? = null,
        val newRoomJoinRules: NewJoinRule = NewJoinRule(),
        val showSaveAction: Boolean = false,
        val actionPermissions: ActionPermissions = ActionPermissions(),
        val supportsRestricted: Boolean = false,
        val canUpgradeToRestricted: Boolean = false
) : MvRxState {

    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)

    data class ActionPermissions(
            val canChangeAvatar: Boolean = false,
            val canChangeName: Boolean = false,
            val canChangeTopic: Boolean = false,
            val canChangeHistoryVisibility: Boolean = false,
            val canChangeJoinRule: Boolean = false,
            val canAddChildren: Boolean = false
    )

    sealed class AvatarAction {
        object None : AvatarAction()
        object DeleteAvatar : AvatarAction()
        data class UpdateAvatar(val newAvatarUri: Uri,
                                val newAvatarFileName: String) : AvatarAction()
    }

    data class NewJoinRule(
            val newJoinRules: RoomJoinRules? = null,
            val newGuestAccess: GuestAccess? = null
    ) {
        fun hasChanged() = newJoinRules != null || newGuestAccess != null
    }

    fun getJoinRuleWording(stringProvider: StringProvider): String {
        return when (val joinRule = newRoomJoinRules.newJoinRules ?: currentRoomJoinRules) {
            RoomJoinRules.INVITE -> {
                stringProvider.getString(R.string.room_settings_room_access_private_title)
            }
            RoomJoinRules.PUBLIC -> {
                stringProvider.getString(R.string.room_settings_room_access_public_title)
            }
            RoomJoinRules.KNOCK -> {
                stringProvider.getString(R.string.room_settings_room_access_entry_knock)
            }
            RoomJoinRules.RESTRICTED -> {
                stringProvider.getString(R.string.room_settings_room_access_restricted_title)
            }
            else                     -> {
                stringProvider.getString(R.string.room_settings_room_access_entry_unknown, joinRule.value)
            }
        }
    }
}

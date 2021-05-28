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

package im.vector.app.features.roommemberprofile

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.epoxy.profiles.buildProfileSection
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.room.powerlevels.Role
import javax.inject.Inject

class RoomMemberProfileController @Inject constructor(
        private val stringProvider: StringProvider,
        colorProvider: ColorProvider,
        private val session: Session
) : TypedEpoxyController<RoomMemberProfileViewState>() {

    private val dividerColor = colorProvider.getColorFromAttribute(R.attr.vctr_list_divider_color)

    var callback: Callback? = null

    interface Callback {
        fun onIgnoreClicked()
        fun onTapVerify()
        fun onShowDeviceList()
        fun onShowDeviceListNoCrossSigning()
        fun onOpenDmClicked()
        fun onJumpToReadReceiptClicked()
        fun onMentionClicked()
        fun onEditPowerLevel(currentRole: Role)
        fun onKickClicked()
        fun onBanClicked(isUserBanned: Boolean)
        fun onCancelInviteClicked()
        fun onInviteClicked()
    }

    override fun buildModels(data: RoomMemberProfileViewState?) {
        if (data?.userMatrixItem?.invoke() == null) {
            return
        }
        if (data.showAsMember) {
            buildRoomMemberActions(data)
        } else {
            buildUserActions(data)
        }
    }

    private fun buildUserActions(state: RoomMemberProfileViewState) {
        val ignoreActionTitle = state.buildIgnoreActionTitle() ?: return
        // More
        buildProfileSection(stringProvider.getString(R.string.room_profile_section_more))
        buildProfileAction(
                id = "ignore",
                title = ignoreActionTitle,
                dividerColor = dividerColor,
                destructive = true,
                editable = false,
                divider = false,
                action = { callback?.onIgnoreClicked() }
        )
        if (!state.isMine) {
            buildProfileAction(
                    id = "direct",
                    editable = false,
                    title = stringProvider.getString(R.string.room_member_open_or_create_dm),
                    dividerColor = dividerColor,
                    action = { callback?.onOpenDmClicked() }
            )
        }
    }

    private fun buildRoomMemberActions(state: RoomMemberProfileViewState) {
        buildSecuritySection(state)
        buildMoreSection(state)
        buildAdminSection(state)
    }

    private fun buildSecuritySection(state: RoomMemberProfileViewState) {
        // Security
        buildProfileSection(stringProvider.getString(R.string.room_profile_section_security))
        val host = this

        if (state.isRoomEncrypted) {
            if (state.userMXCrossSigningInfo != null) {
                // Cross signing is enabled for this user
                if (state.userMXCrossSigningInfo.isTrusted()) {
                    // User is trusted
                    val icon = if (state.allDevicesAreTrusted) {
                        R.drawable.ic_shield_trusted
                    } else {
                        R.drawable.ic_shield_warning
                    }

                    val titleRes = if (state.allDevicesAreTrusted) {
                        R.string.verification_profile_verified
                    } else {
                        R.string.verification_profile_warning
                    }

                    buildProfileAction(
                            id = "learn_more",
                            title = stringProvider.getString(titleRes),
                            dividerColor = dividerColor,
                            editable = true,
                            icon = icon,
                            tintIcon = false,
                            divider = false,
                            action = { callback?.onShowDeviceList() }
                    )
                } else {
                    // Not trusted, propose to verify
                    if (!state.isMine) {
                        buildProfileAction(
                                id = "learn_more",
                                title = stringProvider.getString(R.string.verification_profile_verify),
                                dividerColor = dividerColor,
                                editable = true,
                                icon = R.drawable.ic_shield_black,
                                divider = false,
                                action = { callback?.onTapVerify() }
                        )
                    } else {
                        buildProfileAction(
                                id = "learn_more",
                                title = stringProvider.getString(R.string.room_profile_section_security_learn_more),
                                dividerColor = dividerColor,
                                editable = false,
                                divider = false,
                                action = { callback?.onShowDeviceListNoCrossSigning() }
                        )
                    }

                    genericFooterItem {
                        id("verify_footer")
                        text(host.stringProvider.getString(R.string.room_profile_encrypted_subtitle))
                        centered(false)
                    }
                }
            } else {
                buildProfileAction(
                        id = "learn_more",
                        title = stringProvider.getString(R.string.room_profile_section_security_learn_more),
                        dividerColor = dividerColor,
                        editable = false,
                        divider = false,
                        subtitle = stringProvider.getString(R.string.room_profile_encrypted_subtitle),
                        action = { callback?.onShowDeviceListNoCrossSigning() }
                )
            }
        } else {
            genericFooterItem {
                id("verify_footer_not_encrypted")
                text(host.stringProvider.getString(R.string.room_profile_not_encrypted_subtitle))
                centered(false)
            }
        }
    }

    private fun buildMoreSection(state: RoomMemberProfileViewState) {
        // More
        if (!state.isMine) {
            val membership = state.asyncMembership() ?: return

            buildProfileSection(stringProvider.getString(R.string.room_profile_section_more))

            buildProfileAction(
                    id = "direct",
                    editable = false,
                    title = stringProvider.getString(R.string.room_member_open_or_create_dm),
                    dividerColor = dividerColor,
                    action = { callback?.onOpenDmClicked() }
            )

            if (state.hasReadReceipt) {
                buildProfileAction(
                        id = "read_receipt",
                        editable = false,
                        title = stringProvider.getString(R.string.room_member_jump_to_read_receipt),
                        dividerColor = dividerColor,
                        action = { callback?.onJumpToReadReceiptClicked() }
                )
            }

            val ignoreActionTitle = state.buildIgnoreActionTitle()

            buildProfileAction(
                    id = "mention",
                    title = stringProvider.getString(R.string.room_participants_action_mention),
                    dividerColor = dividerColor,
                    editable = false,
                    divider = ignoreActionTitle != null,
                    action = { callback?.onMentionClicked() }
            )

            val canInvite = state.actionPermissions.canInvite
            if (canInvite && (membership == Membership.LEAVE || membership == Membership.KNOCK)) {
                buildProfileAction(
                        id = "invite",
                        title = stringProvider.getString(R.string.room_participants_action_invite),
                        dividerColor = dividerColor,
                        destructive = false,
                        editable = false,
                        divider = ignoreActionTitle != null,
                        action = { callback?.onInviteClicked() }
                )
            }
            if (ignoreActionTitle != null) {
                buildProfileAction(
                        id = "ignore",
                        title = ignoreActionTitle,
                        dividerColor = dividerColor,
                        destructive = true,
                        editable = false,
                        divider = false,
                        action = { callback?.onIgnoreClicked() }
                )
            }
        }
    }

    private fun buildAdminSection(state: RoomMemberProfileViewState) {
        val powerLevelsContent = state.powerLevelsContent ?: return
        val powerLevelsStr = state.userPowerLevelString() ?: return
        val powerLevelsHelper = PowerLevelsHelper(powerLevelsContent)
        val userPowerLevel = powerLevelsHelper.getUserRole(state.userId)
        val myPowerLevel = powerLevelsHelper.getUserRole(session.myUserId)
        if ((!state.isMine && myPowerLevel <= userPowerLevel)) {
            return
        }
        val membership = state.asyncMembership() ?: return
        val canKick = !state.isMine && state.actionPermissions.canKick
        val canBan = !state.isMine && state.actionPermissions.canBan
        val canEditPowerLevel = state.actionPermissions.canEditPowerLevel
        if (canKick || canBan || canEditPowerLevel) {
            buildProfileSection(stringProvider.getString(R.string.room_profile_section_admin))
        }
        if (canEditPowerLevel) {
            buildProfileAction(
                    id = "edit_power_level",
                    editable = true,
                    title = stringProvider.getString(R.string.power_level_title),
                    subtitle = powerLevelsStr,
                    divider = canKick || canBan,
                    dividerColor = dividerColor,
                    editableRes = R.drawable.ic_edit,
                    action = { callback?.onEditPowerLevel(userPowerLevel) }
            )
        }

        if (canKick) {
            when (membership) {
                Membership.JOIN   -> {
                    buildProfileAction(
                            id = "kick",
                            editable = false,
                            divider = canBan,
                            destructive = true,
                            title = stringProvider.getString(R.string.room_participants_action_kick),
                            dividerColor = dividerColor,
                            action = { callback?.onKickClicked() }
                    )
                }
                Membership.INVITE -> {
                    buildProfileAction(
                            id = "cancel_invite",
                            title = stringProvider.getString(R.string.room_participants_action_cancel_invite),
                            divider = canBan,
                            dividerColor = dividerColor,
                            destructive = true,
                            editable = false,
                            action = { callback?.onCancelInviteClicked() }
                    )
                }
                else              -> Unit
            }
        }
        if (canBan) {
            val banActionTitle = if (membership == Membership.BAN) {
                stringProvider.getString(R.string.room_participants_action_unban)
            } else {
                stringProvider.getString(R.string.room_participants_action_ban)
            }
            buildProfileAction(
                    id = "ban",
                    editable = false,
                    destructive = true,
                    title = banActionTitle,
                    dividerColor = dividerColor,
                    action = { callback?.onBanClicked(membership == Membership.BAN) }
            )
        }
    }

    private fun RoomMemberProfileViewState.buildIgnoreActionTitle(): String? {
        val isIgnored = isIgnored() ?: return null
        return if (isIgnored) {
            stringProvider.getString(R.string.unignore)
        } else {
            stringProvider.getString(R.string.ignore)
        }
    }
}

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

package im.vector.app.core.di

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.ViewModelProvider
import dagger.BindsInstance
import dagger.Component
import im.vector.app.core.dialogs.UnrecognizedCertificateDialog
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.features.MainActivity
import im.vector.app.features.auth.ReAuthActivity
import im.vector.app.features.call.CallControlsBottomSheet
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.conference.VectorJitsiActivity
import im.vector.app.features.call.transfer.CallTransferActivity
import im.vector.app.features.createdirect.CreateDirectRoomActivity
import im.vector.app.features.crypto.keysbackup.settings.KeysBackupManageActivity
import im.vector.app.features.crypto.keysbackup.setup.KeysBackupSetupActivity
import im.vector.app.features.crypto.quads.SharedSecureStorageActivity
import im.vector.app.features.crypto.recover.BootstrapBottomSheet
import im.vector.app.features.crypto.verification.VerificationBottomSheet
import im.vector.app.features.debug.DebugMenuActivity
import im.vector.app.features.devtools.RoomDevToolActivity
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.HomeModule
import im.vector.app.features.home.room.detail.JoinReplacementRoomBottomSheet
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.home.room.detail.readreceipts.DisplayReadReceiptsBottomSheet
import im.vector.app.features.home.room.detail.search.SearchActivity
import im.vector.app.features.home.room.detail.timeline.action.MessageActionsBottomSheet
import im.vector.app.features.home.room.detail.timeline.edithistory.ViewEditHistoryBottomSheet
import im.vector.app.features.home.room.detail.timeline.reactions.ViewReactionsBottomSheet
import im.vector.app.features.home.room.detail.upgrade.MigrateRoomBottomSheet
import im.vector.app.features.home.room.detail.widget.RoomWidgetsBottomSheet
import im.vector.app.features.home.room.filtered.FilteredRoomsActivity
import im.vector.app.features.home.room.list.RoomListModule
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsBottomSheet
import im.vector.app.features.invite.AutoAcceptInvites
import im.vector.app.features.invite.InviteUsersToRoomActivity
import im.vector.app.features.invite.VectorInviteView
import im.vector.app.features.link.LinkHandlerActivity
import im.vector.app.features.login.LoginActivity
import im.vector.app.features.login2.LoginActivity2
import im.vector.app.features.matrixto.MatrixToBottomSheet
import im.vector.app.features.media.BigImageViewerActivity
import im.vector.app.features.media.VectorAttachmentViewerActivity
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.permalink.PermalinkHandlerActivity
import im.vector.app.features.pin.PinLocker
import im.vector.app.features.qrcode.QrCodeScannerActivity
import im.vector.app.features.rageshake.BugReportActivity
import im.vector.app.features.rageshake.BugReporter
import im.vector.app.features.rageshake.RageShake
import im.vector.app.features.reactions.EmojiReactionPickerActivity
import im.vector.app.features.reactions.widget.ReactionButton
import im.vector.app.features.roomdirectory.RoomDirectoryActivity
import im.vector.app.features.roomdirectory.createroom.CreateRoomActivity
import im.vector.app.features.roommemberprofile.RoomMemberProfileActivity
import im.vector.app.features.roommemberprofile.devices.DeviceListBottomSheet
import im.vector.app.features.roomprofile.RoomProfileActivity
import im.vector.app.features.roomprofile.alias.detail.RoomAliasBottomSheet
import im.vector.app.features.roomprofile.settings.historyvisibility.RoomHistoryVisibilityBottomSheet
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleActivity
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleBottomSheet
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.app.features.settings.devices.DeviceVerificationInfoBottomSheet
import im.vector.app.features.share.IncomingShareActivity
import im.vector.app.features.signout.soft.SoftLogoutActivity
import im.vector.app.features.spaces.InviteRoomSpaceChooserBottomSheet
import im.vector.app.features.spaces.SpaceCreationActivity
import im.vector.app.features.spaces.SpaceExploreActivity
import im.vector.app.features.spaces.SpaceSettingsMenuBottomSheet
import im.vector.app.features.spaces.invite.SpaceInviteBottomSheet
import im.vector.app.features.spaces.manage.SpaceManageActivity
import im.vector.app.features.spaces.share.ShareSpaceBottomSheet
import im.vector.app.features.terms.ReviewTermsActivity
import im.vector.app.features.ui.UiStateRepository
import im.vector.app.features.usercode.UserCodeActivity
import im.vector.app.features.widgets.WidgetActivity
import im.vector.app.features.widgets.permissions.RoomWidgetPermissionBottomSheet
import im.vector.app.features.workers.signout.SignOutBottomSheetDialogFragment

@Component(
        dependencies = [
            VectorComponent::class
        ],
        modules = [
            ViewModelModule::class,
            FragmentModule::class,
            HomeModule::class,
            RoomListModule::class,
            ScreenModule::class
        ]
)
@ScreenScope
interface ScreenComponent {

    /* ==========================================================================================
     * Shortcut to VectorComponent elements
     * ========================================================================================== */

    fun activeSessionHolder(): ActiveSessionHolder
    fun fragmentFactory(): FragmentFactory
    fun viewModelFactory(): ViewModelProvider.Factory
    fun bugReporter(): BugReporter
    fun rageShake(): RageShake
    fun navigator(): Navigator
    fun pinLocker(): PinLocker
    fun errorFormatter(): ErrorFormatter
    fun uiStateRepository(): UiStateRepository
    fun unrecognizedCertificateDialog(): UnrecognizedCertificateDialog
    fun autoAcceptInvites(): AutoAcceptInvites

    /* ==========================================================================================
     * Activities
     * ========================================================================================== */

    fun inject(activity: HomeActivity)
    fun inject(activity: RoomDetailActivity)
    fun inject(activity: RoomProfileActivity)
    fun inject(activity: RoomMemberProfileActivity)
    fun inject(activity: VectorSettingsActivity)
    fun inject(activity: KeysBackupManageActivity)
    fun inject(activity: EmojiReactionPickerActivity)
    fun inject(activity: LoginActivity)
    fun inject(activity: LoginActivity2)
    fun inject(activity: LinkHandlerActivity)
    fun inject(activity: MainActivity)
    fun inject(activity: RoomDirectoryActivity)
    fun inject(activity: KeysBackupSetupActivity)
    fun inject(activity: BugReportActivity)
    fun inject(activity: FilteredRoomsActivity)
    fun inject(activity: CreateRoomActivity)
    fun inject(activity: CreateDirectRoomActivity)
    fun inject(activity: IncomingShareActivity)
    fun inject(activity: SoftLogoutActivity)
    fun inject(activity: PermalinkHandlerActivity)
    fun inject(activity: QrCodeScannerActivity)
    fun inject(activity: DebugMenuActivity)
    fun inject(activity: SharedSecureStorageActivity)
    fun inject(activity: BigImageViewerActivity)
    fun inject(activity: InviteUsersToRoomActivity)
    fun inject(activity: ReviewTermsActivity)
    fun inject(activity: WidgetActivity)
    fun inject(activity: VectorCallActivity)
    fun inject(activity: VectorAttachmentViewerActivity)
    fun inject(activity: VectorJitsiActivity)
    fun inject(activity: SearchActivity)
    fun inject(activity: UserCodeActivity)
    fun inject(activity: CallTransferActivity)
    fun inject(activity: ReAuthActivity)
    fun inject(activity: RoomDevToolActivity)
    fun inject(activity: SpaceCreationActivity)
    fun inject(activity: SpaceExploreActivity)
    fun inject(activity: SpaceManageActivity)
    fun inject(activity: RoomJoinRuleActivity)

    /* ==========================================================================================
     * BottomSheets
     * ========================================================================================== */

    fun inject(bottomSheet: MessageActionsBottomSheet)
    fun inject(bottomSheet: ViewReactionsBottomSheet)
    fun inject(bottomSheet: ViewEditHistoryBottomSheet)
    fun inject(bottomSheet: DisplayReadReceiptsBottomSheet)
    fun inject(bottomSheet: RoomListQuickActionsBottomSheet)
    fun inject(bottomSheet: RoomAliasBottomSheet)
    fun inject(bottomSheet: RoomHistoryVisibilityBottomSheet)
    fun inject(bottomSheet: RoomJoinRuleBottomSheet)
    fun inject(bottomSheet: VerificationBottomSheet)
    fun inject(bottomSheet: DeviceVerificationInfoBottomSheet)
    fun inject(bottomSheet: DeviceListBottomSheet)
    fun inject(bottomSheet: BootstrapBottomSheet)
    fun inject(bottomSheet: RoomWidgetPermissionBottomSheet)
    fun inject(bottomSheet: RoomWidgetsBottomSheet)
    fun inject(bottomSheet: CallControlsBottomSheet)
    fun inject(bottomSheet: SignOutBottomSheetDialogFragment)
    fun inject(bottomSheet: MatrixToBottomSheet)
    fun inject(bottomSheet: ShareSpaceBottomSheet)
    fun inject(bottomSheet: SpaceSettingsMenuBottomSheet)
    fun inject(bottomSheet: InviteRoomSpaceChooserBottomSheet)
    fun inject(bottomSheet: SpaceInviteBottomSheet)
    fun inject(bottomSheet: JoinReplacementRoomBottomSheet)
    fun inject(bottomSheet: MigrateRoomBottomSheet)

    /* ==========================================================================================
     * Others
     * ========================================================================================== */

    fun inject(view: VectorInviteView)
    fun inject(preference: UserAvatarPreference)
    fun inject(button: ReactionButton)

    /* ==========================================================================================
     * Factory
     * ========================================================================================== */

    @Component.Factory
    interface Factory {
        fun create(vectorComponent: VectorComponent,
                   @BindsInstance context: AppCompatActivity
        ): ScreenComponent
    }
}

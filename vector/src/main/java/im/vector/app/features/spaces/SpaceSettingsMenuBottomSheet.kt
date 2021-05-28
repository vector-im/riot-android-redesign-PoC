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

package im.vector.app.features.spaces

import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.airbnb.mvrx.args
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.dialogs.withColoredButton
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.core.resources.ColorProvider
import im.vector.app.databinding.BottomSheetSpaceSettingsBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.powerlevel.PowerLevelsObservableFactory
import im.vector.app.features.rageshake.BugReporter
import im.vector.app.features.rageshake.ReportType
import im.vector.app.features.roomprofile.RoomProfileActivity
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.spaces.manage.ManageType
import im.vector.app.features.spaces.manage.SpaceManageActivity
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.gujun.android.span.span
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.room.powerlevels.Role
import org.matrix.android.sdk.api.util.toMatrixItem
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class SpaceBottomSheetSettingsArgs(
        val spaceId: String
) : Parcelable

// XXX make proper view model before leaving beta
class SpaceSettingsMenuBottomSheet : VectorBaseBottomSheetDialogFragment<BottomSheetSpaceSettingsBinding>() {

    @Inject lateinit var navigator: Navigator
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var bugReporter: BugReporter
    @Inject lateinit var colorProvider: ColorProvider

    private val spaceArgs: SpaceBottomSheetSettingsArgs by args()

    interface InteractionListener {
        fun onShareSpaceSelected(spaceId: String)
    }

    var interactionListener: InteractionListener? = null

    override val showExpanded = true

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    var isLastAdmin: Boolean = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetSpaceSettingsBinding {
        return BottomSheetSpaceSettingsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val session = activeSessionHolder.getSafeActiveSession() ?: return
        val roomSummary = session.getRoomSummary(spaceArgs.spaceId)
        roomSummary?.toMatrixItem()?.let {
            avatarRenderer.renderSpace(it, views.spaceAvatarImageView)
        }
        views.spaceNameView.text = roomSummary?.displayName
        views.spaceDescription.setTextOrHide(roomSummary?.topic?.takeIf { it.isNotEmpty() })

        val room = session.getRoom(spaceArgs.spaceId) ?: return

        PowerLevelsObservableFactory(room)
                .createObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { powerLevelContent ->
                    val powerLevelsHelper = PowerLevelsHelper(powerLevelContent)
                    val canInvite = powerLevelsHelper.isUserAbleToInvite(session.myUserId)
                    val canAddChild = powerLevelsHelper.isUserAllowedToSend(session.myUserId, true, EventType.STATE_SPACE_CHILD)

                    val canChangeAvatar = powerLevelsHelper.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_AVATAR)
                    val canChangeName = powerLevelsHelper.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_NAME)
                    val canChangeTopic = powerLevelsHelper.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_TOPIC)

                    views.spaceSettings.isVisible = canChangeAvatar || canChangeName || canChangeTopic

                    views.invitePeople.isVisible = canInvite || roomSummary?.isPublic.orFalse()
                    views.addRooms.isVisible = canAddChild

                    val isAdmin = powerLevelsHelper.getUserRole(session.myUserId) is Role.Admin
                    val otherAdminCount = roomSummary?.otherMemberIds
                            ?.map { powerLevelsHelper.getUserRole(it) }
                            ?.count { it is Role.Admin }
                            ?: 0
                    isLastAdmin = isAdmin && otherAdminCount == 0
                }.disposeOnDestroyView()

        views.spaceBetaTag.setOnClickListener {
            bugReporter.openBugReportScreen(requireActivity(), ReportType.SPACE_BETA_FEEDBACK)
        }

        views.invitePeople.views.bottomSheetActionClickableZone.debouncedClicks {
            dismiss()
            interactionListener?.onShareSpaceSelected(spaceArgs.spaceId)
        }

        views.showMemberList.views.bottomSheetActionClickableZone.debouncedClicks {
            navigator.openRoomProfile(requireContext(), spaceArgs.spaceId, RoomProfileActivity.EXTRA_DIRECT_ACCESS_ROOM_MEMBERS)
        }

        views.spaceSettings.views.bottomSheetActionClickableZone.debouncedClicks {
//            navigator.openRoomProfile(requireContext(), spaceArgs.spaceId)
            startActivity(SpaceManageActivity.newIntent(requireActivity(), spaceArgs.spaceId, ManageType.Settings))
        }

        views.exploreRooms.views.bottomSheetActionClickableZone.debouncedClicks {
            startActivity(SpaceExploreActivity.newIntent(requireContext(), spaceArgs.spaceId))
        }

        views.addRooms.views.bottomSheetActionClickableZone.debouncedClicks {
            dismiss()
            startActivity(SpaceManageActivity.newIntent(requireActivity(), spaceArgs.spaceId, ManageType.AddRooms))
        }

        views.leaveSpace.views.bottomSheetActionClickableZone.debouncedClicks {
            val spaceSummary = activeSessionHolder.getSafeActiveSession()?.getRoomSummary(spaceArgs.spaceId)
                    ?: return@debouncedClicks
            val warningMessage: CharSequence? = if (spaceSummary.otherMemberIds.isEmpty()) {
                span(getString(R.string.space_leave_prompt_msg_only_you)) {
                    textColor = colorProvider.getColor(R.color.riotx_destructive_accent)
                }
            } else if (isLastAdmin) {
                span(getString(R.string.space_leave_prompt_msg_as_admin)) {
                    textColor = colorProvider.getColor(R.color.riotx_destructive_accent)
                }
            } else if (!spaceSummary.isPublic) {
                span(getString(R.string.space_leave_prompt_msg_private)) {
                    textColor = colorProvider.getColor(R.color.riotx_destructive_accent)
                }
            } else {
                null
            }

            AlertDialog.Builder(requireContext())
                    .setMessage(warningMessage)
                    .setTitle(getString(R.string.space_leave_prompt_msg))
                    .setPositiveButton(R.string.leave) { _, _ ->
                        session.coroutineScope.launch {
                            try {
                                session.getRoom(spaceArgs.spaceId)?.leave(null)
                            } catch (failure: Throwable) {
                                Timber.e(failure, "Failed to leave space")
                            }
                        }
                        dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                    .withColoredButton(DialogInterface.BUTTON_POSITIVE)
        }
    }

    companion object {
        fun newInstance(spaceId: String, interactionListener: InteractionListener): SpaceSettingsMenuBottomSheet {
            return SpaceSettingsMenuBottomSheet().apply {
                this.interactionListener = interactionListener
                setArguments(SpaceBottomSheetSettingsArgs(spaceId))
            }
        }
    }
}

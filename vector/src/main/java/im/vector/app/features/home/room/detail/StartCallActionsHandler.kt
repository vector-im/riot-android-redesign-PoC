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

package im.vector.app.features.home.room.detail

import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.platform.Restorable
import im.vector.app.core.utils.PERMISSIONS_FOR_AUDIO_IP_CALL
import im.vector.app.core.utils.PERMISSIONS_FOR_VIDEO_IP_CALL
import im.vector.app.core.utils.checkPermissions
import im.vector.app.features.call.DialerChoiceBottomSheet
import im.vector.app.features.call.dialpad.CallDialPadBottomSheet
import im.vector.app.features.call.dialpad.DialPadFragment
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.widgets.model.WidgetType

private const val DIALER_OPTION_TAG = "DIALER_OPTION_TAG"
private const val DIAL_PAD_TAG = "DIAL_PAD_TAG"

class StartCallActionsHandler(
        private val roomId: String,
        private val fragment: Fragment,
        private val callManager: WebRtcCallManager,
        private val vectorPreferences: VectorPreferences,
        private val roomDetailViewModel: RoomDetailViewModel,
        private val startCallActivityResultLauncher: ActivityResultLauncher<Array<String>>,
        private val showDialogWithMessage: (String) -> Unit,
        private val onTapToReturnToCall: () -> Unit): Restorable {

    fun onVideoCallClicked() {
        handleCallRequest(true)
    }

    fun onVoiceCallClicked() = withState(roomDetailViewModel) {
        if (it.showDialerOption) {
            displayDialerChoiceBottomSheet()
        } else {
            handleCallRequest(false)
        }
    }

    private fun DialerChoiceBottomSheet.applyListeners(): DialerChoiceBottomSheet {
        onDialPadClicked = ::displayDialPadBottomSheet
        onVoiceCallClicked = { handleCallRequest(false) }
        return this
    }

    private fun CallDialPadBottomSheet.applyCallback(): CallDialPadBottomSheet {
        callback = object : DialPadFragment.Callback {
            override fun onOkClicked(formatted: String?, raw: String?) {
                if (raw.isNullOrEmpty()) return
                roomDetailViewModel.handle(RoomDetailAction.StartCallWithPhoneNumber(raw, false))
            }
        }
        return this
    }

    private fun displayDialerChoiceBottomSheet() {
        DialerChoiceBottomSheet()
                .applyListeners()
                .show(fragment.parentFragmentManager, DIALER_OPTION_TAG)
    }

    private fun displayDialPadBottomSheet() {
        CallDialPadBottomSheet.newInstance(true)
                .applyCallback()
                .show(fragment.parentFragmentManager, DIAL_PAD_TAG)
    }

    private fun handleCallRequest(isVideoCall: Boolean) = withState(roomDetailViewModel) { state ->
        val roomSummary = state.asyncRoomSummary.invoke() ?: return@withState
        when (roomSummary.joinedMembersCount) {
            1 -> {
                val pendingInvite = roomSummary.invitedMembersCount ?: 0 > 0
                if (pendingInvite) {
                    // wait for other to join
                    showDialogWithMessage(fragment.getString(R.string.cannot_call_yourself_with_invite))
                } else {
                    // You cannot place a call with yourself.
                    showDialogWithMessage(fragment.getString(R.string.cannot_call_yourself))
                }
            }
            2 -> {
                val currentCall = callManager.getCurrentCall()
                if (currentCall != null) {
                    // resume existing if same room, if not prompt to kill and then restart new call?
                    if (currentCall.signalingRoomId == roomId) {
                        onTapToReturnToCall()
                    }
                    //                        else {
                    // TODO might not work well, and should prompt
                    //                            webRtcPeerConnectionManager.endCall()
                    //                            safeStartCall(it, isVideoCall)
                    //                        }
                } else if (!state.isAllowedToStartWebRTCCall) {
                    showDialogWithMessage(fragment.getString(
                            if (state.isDm()) {
                                R.string.no_permissions_to_start_webrtc_call_in_direct_room
                            } else {
                                R.string.no_permissions_to_start_webrtc_call
                            })
                    )
                } else {
                    safeStartCall(isVideoCall)
                }
            }
            else -> {
                // it's jitsi call
                // can you add widgets??
                if (!state.isAllowedToManageWidgets) {
                    // You do not have permission to start a conference call in this room
                    showDialogWithMessage(fragment.getString(
                            if (state.isDm()) {
                                R.string.no_permissions_to_start_conf_call_in_direct_room
                            } else {
                                R.string.no_permissions_to_start_conf_call
                            }
                    ))
                } else {
                    if (state.activeRoomWidgets()?.filter { it.type == WidgetType.Jitsi }?.any() == true) {
                        // A conference is already in progress!
                        showDialogWithMessage(fragment.getString(R.string.conference_call_in_progress))
                    } else {
                        AlertDialog.Builder(fragment.requireContext())
                                .setTitle(if (isVideoCall) R.string.video_meeting else R.string.audio_meeting)
                                .setMessage(R.string.audio_video_meeting_description)
                                .setPositiveButton(fragment.getString(R.string.create)) { _, _ ->
                                    // create the widget, then navigate to it..
                                    roomDetailViewModel.handle(RoomDetailAction.AddJitsiWidget(isVideoCall))
                                }
                                .setNegativeButton(fragment.getString(R.string.cancel), null)
                                .show()
                    }
                }
            }
        }
    }

    private fun safeStartCall(isVideoCall: Boolean) {
        if (vectorPreferences.preventAccidentalCall()) {
            AlertDialog.Builder(fragment.requireActivity())
                    .setMessage(if (isVideoCall) R.string.start_video_call_prompt_msg else R.string.start_voice_call_prompt_msg)
                    .setPositiveButton(if (isVideoCall) R.string.start_video_call else R.string.start_voice_call) { _, _ ->
                        safeStartCall2(isVideoCall)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
        } else {
            safeStartCall2(isVideoCall)
        }
    }

    private fun safeStartCall2(isVideoCall: Boolean) {
        val startCallAction = RoomDetailAction.StartCall(isVideoCall)
        roomDetailViewModel.pendingAction = startCallAction
        if (isVideoCall) {
            if (checkPermissions(PERMISSIONS_FOR_VIDEO_IP_CALL,
                            fragment.requireActivity(),
                            startCallActivityResultLauncher,
                            R.string.permissions_rationale_msg_camera_and_audio)) {
                roomDetailViewModel.pendingAction = null
                roomDetailViewModel.handle(startCallAction)
            }
        } else {
            if (checkPermissions(PERMISSIONS_FOR_AUDIO_IP_CALL,
                            fragment.requireActivity(),
                            startCallActivityResultLauncher,
                            R.string.permissions_rationale_msg_record_audio)) {
                roomDetailViewModel.pendingAction = null
                roomDetailViewModel.handle(startCallAction)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) = Unit

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            (fragment.parentFragmentManager.findFragmentByTag(DIALER_OPTION_TAG) as? DialerChoiceBottomSheet)?.applyListeners()
            (fragment.parentFragmentManager.findFragmentByTag(DIAL_PAD_TAG) as? CallDialPadBottomSheet)?.applyCallback()
        }
    }
}

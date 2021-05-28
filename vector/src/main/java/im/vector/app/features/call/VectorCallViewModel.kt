/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.call

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.call.audio.CallAudioManager
import im.vector.app.features.call.webrtc.WebRtcCall
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.call.webrtc.getOpponentAsMatrixItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.call.MxPeerConnectionState
import org.matrix.android.sdk.api.session.room.model.call.supportCallTransfer

class VectorCallViewModel @AssistedInject constructor(
        @Assisted initialState: VectorCallViewState,
        val session: Session,
        val callManager: WebRtcCallManager,
        val proximityManager: CallProximityManager
) : VectorViewModel<VectorCallViewState, VectorCallViewActions, VectorCallViewEvents>(initialState) {

    private var call: WebRtcCall? = null

    private var connectionTimeoutJob: Job? = null
    private var hasBeenConnectedOnce = false

    private val callListener = object : WebRtcCall.Listener {

        override fun onHoldUnhold() {
            setState {
                copy(
                        isLocalOnHold = call?.isLocalOnHold ?: false,
                        isRemoteOnHold = call?.remoteOnHold ?: false
                )
            }
        }

        override fun onCaptureStateChanged() {
            setState {
                copy(
                        isVideoCaptureInError = call?.videoCapturerIsInError ?: false,
                        isHD = call?.currentCaptureFormat() is CaptureFormat.HD
                )
            }
        }

        override fun onCameraChanged() {
            setState {
                copy(
                        canSwitchCamera = call?.canSwitchCamera() ?: false,
                        isFrontCamera = call?.currentCameraType() == CameraType.FRONT
                )
            }
        }

        override fun onTick(formattedDuration: String) {
            setState {
                copy(formattedDuration = formattedDuration)
            }
        }

        override fun onStateUpdate(call: MxCall) {
            val callState = call.state
            if (callState is CallState.Connected && callState.iceConnectionState == MxPeerConnectionState.CONNECTED) {
                hasBeenConnectedOnce = true
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
            } else {
                // do we reset as long as it's moving?
                connectionTimeoutJob?.cancel()
                if (hasBeenConnectedOnce) {
                    connectionTimeoutJob = viewModelScope.launch {
                        delay(30_000)
                        try {
                            val turn = session.callSignalingService().getTurnServer()
                            _viewEvents.post(VectorCallViewEvents.ConnectionTimeout(turn))
                        } catch (failure: Throwable) {
                            _viewEvents.post(VectorCallViewEvents.ConnectionTimeout(null))
                        }
                    }
                }
            }
            setState {
                copy(
                        callState = Success(callState),
                        canOpponentBeTransferred = call.capabilities.supportCallTransfer()
                )
            }
        }
    }

    private val currentCallListener = object : WebRtcCallManager.CurrentCallListener {

        override fun onCurrentCallChange(call: WebRtcCall?) {
            if (call == null) {
                _viewEvents.post(VectorCallViewEvents.DismissNoCall)
            } else {
                updateOtherKnownCall(call)
            }
        }

        override fun onAudioDevicesChange() {
            val currentSoundDevice = callManager.audioManager.selectedDevice ?: return
            if (currentSoundDevice == CallAudioManager.Device.PHONE) {
                proximityManager.start()
            } else {
                proximityManager.stop()
            }
            setState {
                copy(
                        availableDevices = callManager.audioManager.availableDevices,
                        device = currentSoundDevice
                )
            }
        }
    }

    private fun updateOtherKnownCall(currentCall: WebRtcCall) {
        val otherCall = callManager.getCalls().firstOrNull {
            it.callId != currentCall.callId && it.mxCall.state is CallState.Connected
        }
        setState {
            if (otherCall == null) {
                copy(otherKnownCallInfo = null)
            } else {
                val otherUserItem = otherCall.getOpponentAsMatrixItem(session)
                copy(otherKnownCallInfo = VectorCallViewState.CallInfo(otherCall.callId, otherUserItem))
            }
        }
    }

    init {
        val webRtcCall = callManager.getCallById(initialState.callId)
        if (webRtcCall == null) {
            setState {
                copy(callState = Fail(IllegalArgumentException("No call")))
            }
        } else {
            call = webRtcCall
            callManager.addCurrentCallListener(currentCallListener)
            val item  = webRtcCall.getOpponentAsMatrixItem(session)
            webRtcCall.addListener(callListener)
            val currentSoundDevice = callManager.audioManager.selectedDevice
            if (currentSoundDevice == CallAudioManager.Device.PHONE) {
                proximityManager.start()
            }
            setState {
                copy(
                        isVideoCall = webRtcCall.mxCall.isVideoCall,
                        callState = Success(webRtcCall.mxCall.state),
                        callInfo = VectorCallViewState.CallInfo(callId, item),
                        device = currentSoundDevice ?: CallAudioManager.Device.PHONE,
                        isLocalOnHold = webRtcCall.isLocalOnHold,
                        isRemoteOnHold = webRtcCall.remoteOnHold,
                        availableDevices = callManager.audioManager.availableDevices,
                        isFrontCamera = webRtcCall.currentCameraType() == CameraType.FRONT,
                        canSwitchCamera = webRtcCall.canSwitchCamera(),
                        formattedDuration = webRtcCall.formattedDuration(),
                        isHD = webRtcCall.mxCall.isVideoCall && webRtcCall.currentCaptureFormat() is CaptureFormat.HD,
                        canOpponentBeTransferred = webRtcCall.mxCall.capabilities.supportCallTransfer()
                )
            }
            updateOtherKnownCall(webRtcCall)
        }
    }

    override fun onCleared() {
        callManager.removeCurrentCallListener(currentCallListener)
        call?.removeListener(callListener)
        proximityManager.stop()
        super.onCleared()
    }

    override fun handle(action: VectorCallViewActions) = withState { state ->
        when (action) {
            VectorCallViewActions.EndCall -> call?.endCall()
            VectorCallViewActions.AcceptCall -> {
                setState {
                    copy(callState = Loading())
                }
                call?.acceptIncomingCall()
            }
            VectorCallViewActions.DeclineCall -> {
                setState {
                    copy(callState = Loading())
                }
                call?.endCall()
            }
            VectorCallViewActions.ToggleMute -> {
                val muted = state.isAudioMuted
                call?.muteCall(!muted)
                setState {
                    copy(isAudioMuted = !muted)
                }
            }
            VectorCallViewActions.ToggleVideo -> {
                if (state.isVideoCall) {
                    val videoEnabled = state.isVideoEnabled
                    call?.enableVideo(!videoEnabled)
                    setState {
                        copy(isVideoEnabled = !videoEnabled)
                    }
                }
                Unit
            }
            VectorCallViewActions.ToggleHoldResume -> {
                val isRemoteOnHold = state.isRemoteOnHold
                call?.updateRemoteOnHold(!isRemoteOnHold)
            }
            is VectorCallViewActions.ChangeAudioDevice -> {
                callManager.audioManager.setAudioDevice(action.device)
            }
            VectorCallViewActions.SwitchSoundDevice -> {
                _viewEvents.post(
                        VectorCallViewEvents.ShowSoundDeviceChooser(state.availableDevices, state.device)
                )
            }
            VectorCallViewActions.HeadSetButtonPressed -> {
                if (state.callState.invoke() is CallState.LocalRinging) {
                    // accept call
                    call?.acceptIncomingCall()
                }
                if (state.callState.invoke() is CallState.Connected) {
                    // end call?
                    call?.endCall()
                }
                Unit
            }
            VectorCallViewActions.ToggleCamera -> {
                call?.switchCamera()
            }
            VectorCallViewActions.ToggleHDSD -> {
                if (!state.isVideoCall) return@withState
                call?.setCaptureFormat(if (state.isHD) CaptureFormat.SD else CaptureFormat.HD)
            }
            VectorCallViewActions.OpenDialPad -> {
                _viewEvents.post(VectorCallViewEvents.ShowDialPad)
            }
            is VectorCallViewActions.SendDtmfDigit -> {
                call?.sendDtmfDigit(action.digit)
            }
            VectorCallViewActions.InitiateCallTransfer -> {
                _viewEvents.post(
                        VectorCallViewEvents.ShowCallTransferScreen
                )
            }
        }.exhaustive
    }

    @AssistedFactory
    interface Factory {
        fun create(initialState: VectorCallViewState): VectorCallViewModel
    }

    companion object : MvRxViewModelFactory<VectorCallViewModel, VectorCallViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: VectorCallViewState): VectorCallViewModel {
            val callActivity: VectorCallActivity = viewModelContext.activity()
            return callActivity.viewModelFactory.create(state)
        }
    }
}

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

package im.vector.app.features.call.webrtc

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import im.vector.app.ActiveSessionDataSource
import im.vector.app.BuildConfig
import im.vector.app.core.services.CallService
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.audio.CallAudioManager
import im.vector.app.features.call.lookup.CallProtocolsChecker
import im.vector.app.features.call.lookup.CallUserMapper
import im.vector.app.features.call.utils.EglUtils
import im.vector.app.features.call.vectorCallService
import im.vector.app.core.pushers.UPHelper
import kotlinx.coroutines.asCoroutineDispatcher
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallListener
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.room.model.call.CallAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallAssertedIdentityContent
import org.matrix.android.sdk.api.session.room.model.call.CallCandidatesContent
import org.matrix.android.sdk.api.session.room.model.call.CallHangupContent
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.session.room.model.call.CallNegotiateContent
import org.matrix.android.sdk.api.session.room.model.call.CallRejectContent
import org.matrix.android.sdk.api.session.room.model.call.CallSelectAnswerContent
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage peerConnectionFactory & Peer connections outside of activity lifecycle to resist configuration changes
 * Use app context
 */
@Singleton
class WebRtcCallManager @Inject constructor(
        private val context: Context,
        private val activeSessionDataSource: ActiveSessionDataSource
) : CallListener, LifecycleObserver {

    private val currentSession: Session?
        get() = activeSessionDataSource.currentValue?.orNull()

    private val protocolsChecker: CallProtocolsChecker?
        get() = currentSession?.vectorCallService?.protocolChecker

    private val callUserMapper: CallUserMapper?
        get() = currentSession?.vectorCallService?.userMapper

    interface CurrentCallListener {
        fun onCurrentCallChange(call: WebRtcCall?) {}
        fun onAudioDevicesChange() {}
    }

    val supportedPSTNProtocol: String?
        get() = protocolsChecker?.supportedPSTNProtocol

    val supportsPSTNProtocol: Boolean
        get() = supportedPSTNProtocol != null

    val supportsVirtualRooms: Boolean
        get() = protocolsChecker?.supportVirtualRooms.orFalse()

    fun addProtocolsCheckerListener(listener: CallProtocolsChecker.Listener) {
        protocolsChecker?.addListener(listener)
    }

    fun removeProtocolsCheckerListener(listener: CallProtocolsChecker.Listener) {
        protocolsChecker?.removeListener(listener)
    }

    private val currentCallsListeners = CopyOnWriteArrayList<CurrentCallListener>()

    fun addCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.add(listener)
    }

    fun removeCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.remove(listener)
    }

    val audioManager = CallAudioManager(context) {
        currentCallsListeners.forEach {
            tryOrNull { it.onAudioDevicesChange() }
        }
    }.apply {
        setMode(CallAudioManager.Mode.DEFAULT)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val rootEglBase by lazy { EglUtils.rootEglBase }

    private var isInBackground: Boolean = true

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun entersForeground() {
        isInBackground = false
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun entersBackground() {
        isInBackground = true
    }

    /**
     * The current call is the call we interacted with whatever his state (connected,resumed, held...)
     * As soon as we interact with an other call, it replaces this one and put it on held if not already.
     */
    var currentCall: AtomicReference<WebRtcCall?> = AtomicReference(null)
    private fun AtomicReference<WebRtcCall?>.setAndNotify(newValue: WebRtcCall?) {
        set(newValue)
        currentCallsListeners.forEach {
            tryOrNull { it.onCurrentCallChange(newValue) }
        }
    }

    private val advertisedCalls = HashSet<String>()
    private val callsByCallId = ConcurrentHashMap<String, WebRtcCall>()
    private val callsByRoomId = ConcurrentHashMap<String, MutableList<WebRtcCall>>()

    // Calls started as an attended transfer, ie. with the intention of transferring another
    // call with a different party to this one.
    // callId (target) -> call (transferee)
    private val transferees = ConcurrentHashMap<String, WebRtcCall>()

    fun getCallById(callId: String): WebRtcCall? {
        return callsByCallId[callId]
    }

    fun getCallsByRoomId(roomId: String): List<WebRtcCall> {
        return callsByRoomId[roomId] ?: emptyList()
    }

    fun getTransfereeForCallId(callId: String): WebRtcCall? {
        return transferees[callId]
    }

    fun getCurrentCall(): WebRtcCall? {
        return currentCall.get()
    }

    fun getCalls(): List<WebRtcCall> {
        return callsByCallId.values.toList()
    }

    fun checkForProtocolsSupportIfNeeded() {
        protocolsChecker?.checkProtocols()
    }

    /**
     * @return a set of all advertised call during the lifetime of the app.
     */
    fun getAdvertisedCalls() = advertisedCalls

    fun headSetButtonTapped() {
        Timber.v("## VOIP headSetButtonTapped")
        val call = getCurrentCall() ?: return
        if (call.mxCall.state is CallState.LocalRinging) {
            call.acceptIncomingCall()
        }
        if (call.mxCall.state is CallState.Connected) {
            // end call?
            call.endCall()
        }
    }

    private fun createPeerConnectionFactoryIfNeeded() {
        if (peerConnectionFactory != null) return
        Timber.v("## VOIP createPeerConnectionFactory")
        val eglBaseContext = rootEglBase?.eglBaseContext ?: return Unit.also {
            Timber.e("## VOIP No EGL BASE")
        }

        Timber.v("## VOIP PeerConnectionFactory.initialize")
        PeerConnectionFactory.initialize(PeerConnectionFactory
                .InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                eglBaseContext,
                /* enableIntelVp8Encoder */
                true,
                /* enableH264HighProfile */
                true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        Timber.v("## VOIP PeerConnectionFactory.createPeerConnectionFactory ...")
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()
    }

    private fun onCallActive(call: WebRtcCall) {
        Timber.v("## VOIP WebRtcPeerConnectionManager onCall active: ${call.mxCall.callId}")
        val currentCall = getCurrentCall().takeIf { it != call }
        currentCall?.updateRemoteOnHold(onHold = true)
        audioManager.setMode(if (call.mxCall.isVideoCall) CallAudioManager.Mode.VIDEO_CALL else CallAudioManager.Mode.AUDIO_CALL)
        this.currentCall.setAndNotify(call)
    }

    private fun onCallEnded(callId: String) {
        Timber.v("## VOIP WebRtcPeerConnectionManager onCall ended: $callId")
        val webRtcCall = callsByCallId.remove(callId) ?: return Unit.also {
            Timber.v("On call ended for unknown call $callId")
        }
        CallService.onCallTerminated(context, callId)
        callsByRoomId[webRtcCall.signalingRoomId]?.remove(webRtcCall)
        callsByRoomId[webRtcCall.nativeRoomId]?.remove(webRtcCall)
        transferees.remove(callId)
        if (getCurrentCall()?.callId == callId) {
            val otherCall = getCalls().lastOrNull()
            currentCall.setAndNotify(otherCall)
        }
        // There is no active calls
        if (getCurrentCall() == null) {
            Timber.v("## VOIP Dispose peerConnectionFactory as there is no need to keep one")
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            audioManager.setMode(CallAudioManager.Mode.DEFAULT)
            // did we start background sync? so we should stop it
            if (isInBackground) {
                if (UPHelper.hasEndpoint(context)) {
                    currentSession?.stopAnyBackgroundSync()
                } else {
                    // for fdroid we should not stop, it should continue syncing
                    // maybe we should restore default timeout/delay though?
                }
            }
        }
    }

    suspend fun startOutgoingCall(nativeRoomId: String, otherUserId: String, isVideoCall: Boolean, transferee: WebRtcCall? = null) {
        val signalingRoomId = callUserMapper?.getOrCreateVirtualRoomForRoom(nativeRoomId, otherUserId) ?: nativeRoomId
        Timber.v("## VOIP startOutgoingCall in room $signalingRoomId to $otherUserId isVideo $isVideoCall")
        if (getCallsByRoomId(nativeRoomId).isNotEmpty()) {
            Timber.w("## VOIP you already have a call in this room")
            return
        }
        if (getCurrentCall() != null && getCurrentCall()?.mxCall?.state !is CallState.Connected || getCalls().size >= 2) {
            Timber.w("## VOIP cannot start outgoing call")
            // Just ignore, maybe we could answer from other session?
            return
        }
        executor.execute {
            createPeerConnectionFactoryIfNeeded()
        }
        getCurrentCall()?.updateRemoteOnHold(onHold = true)
        val mxCall = currentSession?.callSignalingService()?.createOutgoingCall(signalingRoomId, otherUserId, isVideoCall) ?: return
        val webRtcCall = createWebRtcCall(mxCall, nativeRoomId)
        currentCall.setAndNotify(webRtcCall)
        if (transferee != null) {
            transferees[webRtcCall.callId] = transferee
        }
        CallService.onOutgoingCallRinging(
                context = context.applicationContext,
                callId = mxCall.callId)

        // start the activity now
        context.startActivity(VectorCallActivity.newIntent(context, webRtcCall, VectorCallActivity.OUTGOING_CREATED))
    }

    override fun onCallIceCandidateReceived(mxCall: MxCall, iceCandidatesContent: CallCandidatesContent) {
        Timber.v("## VOIP onCallIceCandidateReceived for call ${mxCall.callId}")
        val call = callsByCallId[iceCandidatesContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallIceCandidateReceived for non active call? ${iceCandidatesContent.callId}")
                }
        call.onCallIceCandidateReceived(iceCandidatesContent)
    }

    private fun createWebRtcCall(mxCall: MxCall, nativeRoomId: String): WebRtcCall {
        val webRtcCall = WebRtcCall(
                mxCall = mxCall,
                nativeRoomId = nativeRoomId,
                rootEglBase = rootEglBase,
                context = context,
                dispatcher = dispatcher,
                peerConnectionFactoryProvider = {
                    createPeerConnectionFactoryIfNeeded()
                    peerConnectionFactory
                },
                sessionProvider = { currentSession },
                onCallBecomeActive = this::onCallActive,
                onCallEnded = this::onCallEnded
        )
        advertisedCalls.add(mxCall.callId)
        callsByCallId[mxCall.callId] = webRtcCall
        callsByRoomId.getOrPut(nativeRoomId) { ArrayList(1) }
                .add(webRtcCall)
        callsByRoomId.getOrPut(mxCall.roomId) { ArrayList(1) }
                .add(webRtcCall)
        if (getCurrentCall() == null) {
            currentCall.setAndNotify(webRtcCall)
        }
        return webRtcCall
    }

    fun endCallForRoom(roomId: String, originatedByMe: Boolean = true) {
        callsByRoomId[roomId]?.firstOrNull()?.endCall(originatedByMe)
    }

    override fun onCallInviteReceived(mxCall: MxCall, callInviteContent: CallInviteContent) {
        Timber.v("## VOIP onCallInviteReceived callId ${mxCall.callId}")
        val nativeRoomId = callUserMapper?.nativeRoomForVirtualRoom(mxCall.roomId) ?: mxCall.roomId
        if (getCallsByRoomId(nativeRoomId).isNotEmpty()) {
            Timber.w("## VOIP you already have a call in this room")
            return
        }
        if ((getCurrentCall() != null && getCurrentCall()?.mxCall?.state !is CallState.Connected) || getCalls().size >= 2) {
            Timber.w("## VOIP receiving incoming call but cannot handle it")
            // Just ignore, maybe we could answer from other session?
            return
        }
        createWebRtcCall(mxCall, nativeRoomId).apply {
            offerSdp = callInviteContent.offer
        }
        // Start background service with notification
        CallService.onIncomingCallRinging(
                context = context,
                callId = mxCall.callId,
                isInBackground = isInBackground
        )
        // If this is received while in background, the app will not sync,
        // and thus won't be able to received events. For example if the call is
        // accepted on an other session this device will continue ringing
        if (isInBackground) {
            if (UPHelper.hasEndpoint(context)) {
                // only for push version as fdroid version is already doing it?
                currentSession?.startAutomaticBackgroundSync(30, 0)
            } else {
                // Maybe increase sync freq? but how to set back to default values?
            }
        }
    }

    override fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        val call = callsByCallId[callAnswerContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallAnswerReceived for non active call? ${callAnswerContent.callId}")
                }
        val mxCall = call.mxCall
        // Update service state
        CallService.onPendingCall(
                context = context,
                callId = mxCall.callId
        )
        call.onCallAnswerReceived(callAnswerContent)
    }

    override fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        val call = callsByCallId[callHangupContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallHangupReceived for non active call? ${callHangupContent.callId}")
                }
        call.endCall(false)
    }

    override fun onCallRejectReceived(callRejectContent: CallRejectContent) {
        val call = callsByCallId[callRejectContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallRejectReceived for non active call? ${callRejectContent.callId}")
                }
        call.endCall(false)
    }

    override fun onCallSelectAnswerReceived(callSelectAnswerContent: CallSelectAnswerContent) {
        val call = callsByCallId[callSelectAnswerContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallSelectAnswerReceived for non active call? ${callSelectAnswerContent.callId}")
                }
        val selectedPartyId = callSelectAnswerContent.selectedPartyId
        if (selectedPartyId != call.mxCall.ourPartyId) {
            Timber.i("Got select_answer for party ID $selectedPartyId: we are party ID ${call.mxCall.ourPartyId}.")
            // The other party has picked somebody else's answer
            call.endCall(false)
        }
    }

    override fun onCallNegotiateReceived(callNegotiateContent: CallNegotiateContent) {
        val call = callsByCallId[callNegotiateContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallNegotiateReceived for non active call? ${callNegotiateContent.callId}")
                }
        call.onCallNegotiateReceived(callNegotiateContent)
    }

    override fun onCallManagedByOtherSession(callId: String) {
        Timber.v("## VOIP onCallManagedByOtherSession: $callId")
        onCallEnded(callId)
    }

    override fun onCallAssertedIdentityReceived(callAssertedIdentityContent: CallAssertedIdentityContent) {
        if (!BuildConfig.handleCallAssertedIdentityEvents) {
            return
        }
        val call = callsByCallId[callAssertedIdentityContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallAssertedIdentityReceived for non active call? ${callAssertedIdentityContent.callId}")
                }
       call.onCallAssertedIdentityReceived(callAssertedIdentityContent)
    }
}

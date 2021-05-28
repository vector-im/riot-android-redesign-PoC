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
import android.hardware.camera2.CameraManager
import androidx.core.content.getSystemService
import im.vector.app.core.services.CallService
import im.vector.app.core.utils.CountUpTimer
import im.vector.app.core.utils.TextUtils.formatDuration
import im.vector.app.features.call.CameraEventsHandlerAdapter
import im.vector.app.features.call.CameraProxy
import im.vector.app.features.call.CameraType
import im.vector.app.features.call.CaptureFormat
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.utils.asWebRTC
import im.vector.app.features.call.utils.awaitCreateAnswer
import im.vector.app.features.call.utils.awaitCreateOffer
import im.vector.app.features.call.utils.awaitSetLocalDescription
import im.vector.app.features.call.utils.awaitSetRemoteDescription
import im.vector.app.features.call.utils.mapToCallCandidate
import im.vector.app.features.session.coroutineScope
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.call.MxPeerConnectionState
import org.matrix.android.sdk.api.session.call.TurnServerResponse
import org.matrix.android.sdk.api.session.room.model.call.CallAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallCandidatesContent
import org.matrix.android.sdk.api.session.room.model.call.CallHangupContent
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.session.room.model.call.CallNegotiateContent
import org.matrix.android.sdk.api.session.room.model.call.SdpType
import org.threeten.bp.Duration
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext

private const val STREAM_ID = "ARDAMS"
private const val AUDIO_TRACK_ID = "ARDAMSa0"
private const val VIDEO_TRACK_ID = "ARDAMSv0"
private val DEFAULT_AUDIO_CONSTRAINTS = MediaConstraints()

class WebRtcCall(val mxCall: MxCall,
                 // This is where the call is placed from an ui perspective. In case of virtual room, it can differs from the signalingRoomId.
                 val nativeRoomId: String,
                 private val rootEglBase: EglBase?,
                 private val context: Context,
                 private val dispatcher: CoroutineContext,
                 private val sessionProvider: Provider<Session?>,
                 private val peerConnectionFactoryProvider: Provider<PeerConnectionFactory?>,
                 private val onCallBecomeActive: (WebRtcCall) -> Unit,
                 private val onCallEnded: (String) -> Unit) : MxCall.StateListener {

    interface Listener : MxCall.StateListener {
        fun onCaptureStateChanged() {}
        fun onCameraChanged() {}
        fun onHoldUnhold() {}
        fun onTick(formattedDuration: String) {}
        override fun onStateUpdate(call: MxCall) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val sessionScope: CoroutineScope?
        get() = sessionProvider.get()?.coroutineScope

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    val callId = mxCall.callId
    // room where call signaling is placed. In case of virtual room it can differs from the nativeRoomId.
    val signalingRoomId = mxCall.roomId

    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    // Perfect negotiation state: https://www.w3.org/TR/webrtc/#perfect-negotiation-example
    private var makingOffer: Boolean = false
    private var ignoreOffer: Boolean = false

    private var videoCapturer: CameraVideoCapturer? = null

    private val availableCamera = ArrayList<CameraProxy>()
    private var cameraInUse: CameraProxy? = null
    private var currentCaptureFormat: CaptureFormat = CaptureFormat.HD
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null

    private val timer = CountUpTimer(Duration.ofSeconds(1).toMillis()).apply {
        tickListener = object : CountUpTimer.TickListener {
            override fun onTick(milliseconds: Long) {
                val formattedDuration = formatDuration(Duration.ofMillis(milliseconds))
                listeners.forEach {
                    tryOrNull { it.onTick(formattedDuration) }
                }
            }
        }
    }

    // Mute status
    var micMuted = false
        private set
    var videoMuted = false
        private set
    var remoteOnHold = false
        private set
    var isLocalOnHold = false
        private set

    // This value is used to track localOnHold when changing remoteOnHold value
    private var wasLocalOnHold = false

    var offerSdp: CallInviteContent.Offer? = null

    var videoCapturerIsInError = false
        set(value) {
            field = value
            listeners.forEach {
                tryOrNull { it.onCaptureStateChanged() }
            }
        }
    private var localSurfaceRenderers: MutableList<WeakReference<SurfaceViewRenderer>> = ArrayList()
    private var remoteSurfaceRenderers: MutableList<WeakReference<SurfaceViewRenderer>> = ArrayList()

    private val iceCandidateSource: PublishSubject<IceCandidate> = PublishSubject.create()
    private val iceCandidateDisposable = iceCandidateSource
            .buffer(300, TimeUnit.MILLISECONDS)
            .subscribe {
                // omit empty :/
                if (it.isNotEmpty()) {
                    Timber.v("## Sending local ice candidates to call")
                    // it.forEach { peerConnection?.addIceCandidate(it) }
                    mxCall.sendLocalCallCandidates(it.mapToCallCandidate())
                }
            }

    private val remoteCandidateSource: ReplaySubject<IceCandidate> = ReplaySubject.create()
    private var remoteIceCandidateDisposable: Disposable? = null

    init {
        mxCall.addListener(this)
    }

    fun onIceCandidate(iceCandidate: IceCandidate) = iceCandidateSource.onNext(iceCandidate)

    fun onRenegotiationNeeded(restartIce: Boolean) {
        sessionScope?.launch(dispatcher) {
            if (mxCall.state != CallState.CreateOffer && mxCall.opponentVersion == 0) {
                Timber.v("Opponent does not support renegotiation: ignoring onRenegotiationNeeded event")
                return@launch
            }
            val constraints = MediaConstraints()
            if (restartIce) {
                constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
            val peerConnection = peerConnection ?: return@launch
            Timber.v("## VOIP creating offer...")
            makingOffer = true
            try {
                val sessionDescription = peerConnection.awaitCreateOffer(constraints) ?: return@launch
                peerConnection.awaitSetLocalDescription(sessionDescription)
                if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.GATHERING) {
                    // Allow a short time for initial candidates to be gathered
                    delay(200)
                }
                if (mxCall.state == CallState.Terminated) {
                    return@launch
                }
                if (mxCall.state == CallState.CreateOffer) {
                    // send offer to peer
                    mxCall.offerSdp(sessionDescription.description)
                } else {
                    mxCall.negotiate(sessionDescription.description, SdpType.OFFER)
                }
            } catch (failure: Throwable) {
                // Need to handle error properly.
                Timber.v("Failure while creating offer")
            } finally {
                makingOffer = false
            }
        }
    }

    fun formattedDuration(): String {
        return formatDuration(
                Duration.ofMillis(timer.elapsedTime())
        )
    }

    private fun createPeerConnection(turnServerResponse: TurnServerResponse?) {
        val peerConnectionFactory = peerConnectionFactoryProvider.get() ?: return
        val iceServers = mutableListOf<PeerConnection.IceServer>().apply {
            turnServerResponse?.let { server ->
                server.uris?.forEach { uri ->
                    add(
                            PeerConnection
                                    .IceServer
                                    .builder(uri)
                                    .setUsername(server.username)
                                    .setPassword(server.password)
                                    .createIceServer()
                    )
                }
            }
        }
        Timber.v("## VOIP creating peer connection...with iceServers $iceServers ")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, PeerConnectionObserver(this))
    }

    fun attachViewRenderers(localViewRenderer: SurfaceViewRenderer?, remoteViewRenderer: SurfaceViewRenderer, mode: String?) {
        Timber.v("## VOIP attachViewRenderers localRendeder $localViewRenderer / $remoteViewRenderer")
        localSurfaceRenderers.addIfNeeded(localViewRenderer)
        remoteSurfaceRenderers.addIfNeeded(remoteViewRenderer)

        sessionScope?.launch(dispatcher) {
            when (mode) {
                VectorCallActivity.INCOMING_ACCEPT -> {
                    internalAcceptIncomingCall()
                }
                VectorCallActivity.INCOMING_RINGING -> {
                    // wait until accepted to create peer connection
                    // TODO eventually we could already display local stream in PIP?
                }
                VectorCallActivity.OUTGOING_CREATED -> {
                    setupOutgoingCall()
                }
                else                                -> {
                    // sink existing tracks (configuration change, e.g screen rotation)
                    attachViewRenderersInternal()
                }
            }
        }
    }

    fun acceptIncomingCall() {
        sessionScope?.launch {
            Timber.v("## VOIP acceptIncomingCall from state ${mxCall.state}")
            if (mxCall.state == CallState.LocalRinging) {
                internalAcceptIncomingCall()
            }
        }
    }

    /**
     * Sends a DTMF digit to the other party
     * @param digit The digit (nb. string - '#' and '*' are dtmf too)
     */
    fun sendDtmfDigit(digit: String) {
        for (sender in peerConnection?.senders.orEmpty()) {
            if (sender.track()?.kind() == "audio" && sender.dtmf()?.canInsertDtmf() == true) {
                try {
                    sender.dtmf()?.insertDtmf(digit, 100, 70)
                    return
                } catch (failure: Throwable) {
                    Timber.v("Fail to send Dtmf digit")
                }
            }
        }
    }

    fun detachRenderers(renderers: List<SurfaceViewRenderer>?) {
        Timber.v("## VOIP detachRenderers")
        if (renderers.isNullOrEmpty()) {
            // remove all sinks
            localSurfaceRenderers.forEach {
                if (it.get() != null) localVideoTrack?.removeSink(it.get())
            }
            remoteSurfaceRenderers.forEach {
                if (it.get() != null) remoteVideoTrack?.removeSink(it.get())
            }
            localSurfaceRenderers.clear()
            remoteSurfaceRenderers.clear()
        } else {
            renderers.forEach {
                localSurfaceRenderers.removeIfNeeded(it)
                remoteSurfaceRenderers.removeIfNeeded(it)
                // no need to check if it's in the track, removeSink is doing it
                localVideoTrack?.removeSink(it)
                remoteVideoTrack?.removeSink(it)
            }
        }
    }

    private suspend fun setupOutgoingCall() = withContext(dispatcher) {
        tryOrNull {
            onCallBecomeActive(this@WebRtcCall)
        }
        val turnServer = getTurnServer()
        mxCall.state = CallState.CreateOffer
        // 1. Create RTCPeerConnection
        createPeerConnection(turnServer)
        // 2. Access camera (if video call) + microphone, create local stream
        createLocalStream()
        attachViewRenderersInternal()
        Timber.v("## VOIP remoteCandidateSource $remoteCandidateSource")
        remoteIceCandidateDisposable = remoteCandidateSource.subscribe({
            Timber.v("## VOIP adding remote ice candidate $it")
            peerConnection?.addIceCandidate(it)
        }, {
            Timber.v("## VOIP failed to add remote ice candidate $it")
        })
        // Now we wait for negotiation callback
    }

    private suspend fun internalAcceptIncomingCall() = withContext(dispatcher) {
        tryOrNull {
            onCallBecomeActive(this@WebRtcCall)
        }
        val turnServerResponse = getTurnServer()
        // Update service state
        withContext(Dispatchers.Main) {
            CallService.onPendingCall(
                    context = context,
                    callId = mxCall.callId
            )
        }
        // 1) create peer connection
        createPeerConnection(turnServerResponse)

        // create sdp using offer, and set remote description
        // the offer has beed stored when invite was received
        val offerSdp = offerSdp?.sdp?.let {
            SessionDescription(SessionDescription.Type.OFFER, it)
        }
        if (offerSdp == null) {
            Timber.v("We don't have any offer to process")
            return@withContext
        }
        Timber.v("Offer sdp for invite: ${offerSdp.description}")
        try {
            peerConnection?.awaitSetRemoteDescription(offerSdp)
        } catch (failure: Throwable) {
            Timber.v("Failure putting remote description")
            endCall(true, CallHangupContent.Reason.UNKWOWN_ERROR)
            return@withContext
        }
        // 2) Access camera + microphone, create local stream
        createLocalStream()
        attachViewRenderersInternal()

        // create a answer, set local description and send via signaling
        createAnswer()?.also {
            mxCall.accept(it.description)
        }
        Timber.v("## VOIP remoteCandidateSource $remoteCandidateSource")
        remoteIceCandidateDisposable = remoteCandidateSource.subscribe({
            Timber.v("## VOIP adding remote ice candidate $it")
            peerConnection?.addIceCandidate(it)
        }, {
            Timber.v("## VOIP failed to add remote ice candidate $it")
        })
    }

    private fun attachViewRenderersInternal() {
        // render local video in pip view
        localSurfaceRenderers.forEach { renderer ->
            renderer.get()?.let { pipSurface ->
                pipSurface.setMirror(this.cameraInUse?.type == CameraType.FRONT)
                // no need to check if already added, addSink is checking that
                localVideoTrack?.addSink(pipSurface)
            }
        }

        // If remote track exists, then sink it to surface
        remoteSurfaceRenderers.forEach { renderer ->
            renderer.get()?.let { participantSurface ->
                remoteVideoTrack?.addSink(participantSurface)
            }
        }
    }

    private suspend fun getTurnServer(): TurnServerResponse? {
        return tryOrNull {
            sessionProvider.get()?.callSignalingService()?.getTurnServer()
        }
    }

    private fun createLocalStream() {
        val peerConnectionFactory = peerConnectionFactoryProvider.get() ?: return
        Timber.v("Create local stream for call ${mxCall.callId}")
        configureAudioTrack(peerConnectionFactory)
        // add video track if needed
        if (mxCall.isVideoCall) {
            configureVideoTrack(peerConnectionFactory)
        }
        updateMuteStatus()
    }

    private fun configureAudioTrack(peerConnectionFactory: PeerConnectionFactory) {
        val audioSource = peerConnectionFactory.createAudioSource(DEFAULT_AUDIO_CONSTRAINTS)
        val audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack.setEnabled(true)
        Timber.v("Add audio track $AUDIO_TRACK_ID to call ${mxCall.callId}")
        peerConnection?.addTrack(audioTrack, listOf(STREAM_ID))
        localAudioSource = audioSource
        localAudioTrack = audioTrack
    }

    private fun configureVideoTrack(peerConnectionFactory: PeerConnectionFactory) {
        val cameraIterator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(false)
        }
        // I don't realy know how that works if there are 2 front or 2 back cameras
        val frontCamera = cameraIterator.deviceNames
                ?.firstOrNull { cameraIterator.isFrontFacing(it) }
                ?.let {
                    CameraProxy(it, CameraType.FRONT).also { availableCamera.add(it) }
                }

        val backCamera = cameraIterator.deviceNames
                ?.firstOrNull { cameraIterator.isBackFacing(it) }
                ?.let {
                    CameraProxy(it, CameraType.BACK).also { availableCamera.add(it) }
                }

        val camera = frontCamera?.also { cameraInUse = frontCamera }
                ?: backCamera?.also { cameraInUse = backCamera }
                ?: null.also { cameraInUse = null }

        listeners.forEach {
            tryOrNull { it.onCameraChanged() }
        }

        if (camera != null) {
            val videoCapturer = cameraIterator.createCapturer(camera.name, object : CameraEventsHandlerAdapter() {
                override fun onFirstFrameAvailable() {
                    super.onFirstFrameAvailable()
                    videoCapturerIsInError = false
                }

                override fun onCameraClosed() {
                    super.onCameraClosed()
                    Timber.v("onCameraClosed")
                    // This could happen if you open the camera app in chat
                    // We then register in order to restart capture as soon as the camera is available again
                    videoCapturerIsInError = true
                    val cameraManager = context.getSystemService<CameraManager>()
                    cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
                        override fun onCameraUnavailable(cameraId: String) {
                            super.onCameraUnavailable(cameraId)
                            Timber.v("On camera unavailable: $cameraId")
                        }

                        override fun onCameraAccessPrioritiesChanged() {
                            super.onCameraAccessPrioritiesChanged()
                            Timber.v("onCameraAccessPrioritiesChanged")
                        }

                        override fun onCameraAvailable(cameraId: String) {
                            Timber.v("On camera available: $cameraId")
                            if (cameraId == camera.name) {
                                videoCapturer?.startCapture(currentCaptureFormat.width, currentCaptureFormat.height, currentCaptureFormat.fps)
                                cameraManager?.unregisterAvailabilityCallback(this)
                            }
                        }
                    }
                    cameraManager?.registerAvailabilityCallback(cameraAvailabilityCallback!!, null)
                }
            })

            val videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
            Timber.v("## VOIP Local video source created")

            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            // HD
            videoCapturer.startCapture(currentCaptureFormat.width, currentCaptureFormat.height, currentCaptureFormat.fps)
            this.videoCapturer = videoCapturer

            val videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
            Timber.v("Add video track $VIDEO_TRACK_ID to call ${mxCall.callId}")
            videoTrack.setEnabled(true)
            peerConnection?.addTrack(videoTrack, listOf(STREAM_ID))
            localVideoSource = videoSource
            localVideoTrack = videoTrack
        }
    }

    fun setCaptureFormat(format: CaptureFormat) {
        Timber.v("## VOIP setCaptureFormat $format")
        videoCapturer?.changeCaptureFormat(format.width, format.height, format.fps)
        currentCaptureFormat = format
    }

    private fun updateMuteStatus() {
        val micShouldBeMuted = micMuted || remoteOnHold
        localAudioTrack?.setEnabled(!micShouldBeMuted)
        remoteAudioTrack?.setEnabled(!remoteOnHold)
        val vidShouldBeMuted = videoMuted || remoteOnHold
        localVideoTrack?.setEnabled(!vidShouldBeMuted)
        remoteVideoTrack?.setEnabled(!remoteOnHold)
    }

    /**
     * Indicates whether we are 'on hold' to the remote party (ie. if true,
     * they cannot hear us). Note that this will return true when we put the
     * remote on hold too due to the way hold is implemented (since we don't
     * wish to play hold music when we put a call on hold, we use 'inactive'
     * rather than 'sendonly')
     * @returns true if the other party has put us on hold
     */
    private fun computeIsLocalOnHold(): Boolean {
        if (mxCall.state !is CallState.Connected) return false
        var callOnHold = true
        // We consider a call to be on hold only if *all* the tracks are on hold
        // (is this the right thing to do?)
        for (transceiver in peerConnection?.transceivers ?: emptyList()) {
            val trackOnHold = transceiver.currentDirection == RtpTransceiver.RtpTransceiverDirection.INACTIVE
                    || transceiver.currentDirection == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            if (!trackOnHold) callOnHold = false
        }
        return callOnHold
    }

    fun updateRemoteOnHold(onHold: Boolean) {
        sessionScope?.launch(dispatcher) {
            if (remoteOnHold == onHold) return@launch
            val direction: RtpTransceiver.RtpTransceiverDirection
            if (onHold) {
                wasLocalOnHold = isLocalOnHold
                remoteOnHold = true
                isLocalOnHold = true
                direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
                timer.pause()
            } else {
                remoteOnHold = false
                isLocalOnHold = wasLocalOnHold
                onCallBecomeActive(this@WebRtcCall)
                direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                if (!isLocalOnHold) {
                    timer.resume()
                }
            }
            for (transceiver in peerConnection?.transceivers ?: emptyList()) {
                transceiver.direction = direction
            }
            updateMuteStatus()
            listeners.forEach {
                tryOrNull { it.onHoldUnhold() }
            }
        }
    }

    fun muteCall(muted: Boolean) {
        micMuted = muted
        updateMuteStatus()
    }

    fun enableVideo(enabled: Boolean) {
        videoMuted = !enabled
        updateMuteStatus()
    }

    fun canSwitchCamera(): Boolean {
        return availableCamera.size > 1
    }

    private fun getOppositeCameraIfAny(): CameraProxy? {
        val currentCamera = cameraInUse ?: return null
        return if (currentCamera.type == CameraType.FRONT) {
            availableCamera.firstOrNull { it.type == CameraType.BACK }
        } else {
            availableCamera.firstOrNull { it.type == CameraType.FRONT }
        }
    }

    fun switchCamera() {
        Timber.v("## VOIP switchCamera")
        if (mxCall.state is CallState.Connected && mxCall.isVideoCall) {
            val oppositeCamera = getOppositeCameraIfAny() ?: return
            videoCapturer?.switchCamera(
                    object : CameraVideoCapturer.CameraSwitchHandler {
                        // Invoked on success. |isFrontCamera| is true if the new camera is front facing.
                        override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                            Timber.v("## VOIP onCameraSwitchDone isFront $isFrontCamera")
                            cameraInUse = oppositeCamera
                            localSurfaceRenderers.forEach {
                                it.get()?.setMirror(isFrontCamera)
                            }
                            listeners.forEach {
                                tryOrNull { it.onCameraChanged() }
                            }
                        }

                        override fun onCameraSwitchError(errorDescription: String?) {
                            Timber.v("## VOIP onCameraSwitchError isFront $errorDescription")
                        }
                    }, oppositeCamera.name
            )
        }
    }

    private suspend fun createAnswer(): SessionDescription? {
        Timber.w("## VOIP createAnswer")
        val peerConnection = peerConnection ?: return null
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (mxCall.isVideoCall) "true" else "false"))
        }
        return try {
            val localDescription = peerConnection.awaitCreateAnswer(constraints) ?: return null
            peerConnection.awaitSetLocalDescription(localDescription)
            localDescription
        } catch (failure: Throwable) {
            Timber.v("Fail to create answer")
            null
        }
    }

    fun currentCameraType(): CameraType? {
        return cameraInUse?.type
    }

    fun currentCaptureFormat(): CaptureFormat {
        return currentCaptureFormat
    }

    private fun release() {
        listeners.clear()
        mxCall.removeListener(this)
        timer.stop()
        timer.tickListener = null
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        remoteIceCandidateDisposable?.dispose()
        iceCandidateDisposable?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        localAudioSource?.dispose()
        localVideoSource?.dispose()
        localAudioSource = null
        localAudioTrack = null
        localVideoSource = null
        localVideoTrack = null
        cameraAvailabilityCallback = null
    }

    fun onAddStream(stream: MediaStream) {
        sessionScope?.launch(dispatcher) {
            // reportError("Weird-looking stream: " + stream);
            if (stream.audioTracks.size > 1 || stream.videoTracks.size > 1) {
                Timber.e("## VOIP StreamObserver weird looking stream: $stream")
                // TODO maybe do something more??
                mxCall.hangUp()
                return@launch
            }
            if (stream.audioTracks.size == 1) {
                val remoteAudioTrack = stream.audioTracks.first()
                remoteAudioTrack.setEnabled(true)
                this@WebRtcCall.remoteAudioTrack = remoteAudioTrack
            }
            if (stream.videoTracks.size == 1) {
                val remoteVideoTrack = stream.videoTracks.first()
                remoteVideoTrack.setEnabled(true)
                this@WebRtcCall.remoteVideoTrack = remoteVideoTrack
                // sink to renderer if attached
                remoteSurfaceRenderers.forEach { it.get()?.let { remoteVideoTrack.addSink(it) } }
            }
        }
    }

    fun onRemoveStream() {
        sessionScope?.launch(dispatcher) {
            remoteSurfaceRenderers
                    .mapNotNull { it.get() }
                    .forEach { remoteVideoTrack?.removeSink(it) }
            remoteVideoTrack = null
            remoteAudioTrack = null
        }
    }

    fun endCall(originatedByMe: Boolean = true, reason: CallHangupContent.Reason? = null) {
        if (mxCall.state == CallState.Terminated) {
            return
        }
        // Close tracks ASAP
        localVideoTrack?.setEnabled(false)
        localVideoTrack?.setEnabled(false)
        cameraAvailabilityCallback?.let { cameraAvailabilityCallback ->
            val cameraManager = context.getSystemService<CameraManager>()!!
            cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        }
        val wasRinging = mxCall.state is CallState.LocalRinging
        mxCall.state = CallState.Terminated
        sessionScope?.launch(dispatcher) {
            release()
        }
        onCallEnded(callId)
        if (originatedByMe) {
            if (wasRinging) {
                mxCall.reject()
            } else {
                mxCall.hangUp(reason)
            }
        }
    }

    // Call listener

    fun onCallIceCandidateReceived(iceCandidatesContent: CallCandidatesContent) {
        sessionScope?.launch(dispatcher) {
            iceCandidatesContent.candidates.forEach {
                if (it.sdpMid.isNullOrEmpty() || it.candidate.isNullOrEmpty()) {
                    return@forEach
                }
                Timber.v("## VOIP onCallIceCandidateReceived for call ${mxCall.callId} sdp: ${it.candidate}")
                val iceCandidate = IceCandidate(it.sdpMid, it.sdpMLineIndex, it.candidate)
                remoteCandidateSource.onNext(iceCandidate)
            }
        }
    }

    fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        sessionScope?.launch(dispatcher) {
            Timber.v("## VOIP onCallAnswerReceived ${callAnswerContent.callId}")
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, callAnswerContent.answer.sdp)
            try {
                peerConnection?.awaitSetRemoteDescription(sdp)
            } catch (failure: Throwable) {
                endCall(true, CallHangupContent.Reason.UNKWOWN_ERROR)
                return@launch
            }
            if (mxCall.opponentPartyId?.hasValue().orFalse()) {
                mxCall.selectAnswer()
            }
        }
    }

    fun onCallNegotiateReceived(callNegotiateContent: CallNegotiateContent) {
        sessionScope?.launch(dispatcher) {
            val description = callNegotiateContent.description
            val type = description?.type
            val sdpText = description?.sdp
            if (type == null || sdpText == null) {
                Timber.i("Ignoring invalid m.call.negotiate event")
                return@launch
            }
            val peerConnection = peerConnection ?: return@launch
            // Politeness always follows the direction of the call: in a glare situation,
            // we pick either the inbound or outbound call, so one side will always be
            // inbound and one outbound
            val polite = !mxCall.isOutgoing
            // Here we follow the perfect negotiation logic from
            // https://developer.mozilla.org/en-US/docs/Web/API/WebRTC_API/Perfect_negotiation
            val offerCollision = description.type == SdpType.OFFER
                    && (makingOffer || peerConnection.signalingState() != PeerConnection.SignalingState.STABLE)

            ignoreOffer = !polite && offerCollision
            if (ignoreOffer) {
                Timber.i("Ignoring colliding negotiate event because we're impolite")
                return@launch
            }
            val prevOnHold = computeIsLocalOnHold()
            try {
                val sdp = SessionDescription(type.asWebRTC(), sdpText)
                peerConnection.awaitSetRemoteDescription(sdp)
                if (type == SdpType.OFFER) {
                    createAnswer()?.also {
                        mxCall.negotiate(it.description, SdpType.ANSWER)
                    }
                }
            } catch (failure: Throwable) {
                Timber.e(failure, "Failed to complete negotiation")
            }
            val nowOnHold = computeIsLocalOnHold()
            wasLocalOnHold = nowOnHold
            if (prevOnHold != nowOnHold) {
                isLocalOnHold = nowOnHold
                if (nowOnHold) {
                    timer.pause()
                } else {
                    timer.resume()
                }
                listeners.forEach {
                    tryOrNull { it.onHoldUnhold() }
                }
            }
        }
    }

    // MxCall.StateListener

    override fun onStateUpdate(call: MxCall) {
        val state = call.state
        if (state is CallState.Connected && state.iceConnectionState == MxPeerConnectionState.CONNECTED) {
            timer.resume()
        } else {
            timer.pause()
        }
        listeners.forEach {
            tryOrNull { it.onStateUpdate(call) }
        }
    }
}

private fun MutableList<WeakReference<SurfaceViewRenderer>>.addIfNeeded(renderer: SurfaceViewRenderer?) {
    if (renderer == null) return
    val exists = any {
        it.get() == renderer
    }
    if (!exists) {
        add(WeakReference(renderer))
    }
}

private fun MutableList<WeakReference<SurfaceViewRenderer>>.removeIfNeeded(renderer: SurfaceViewRenderer?) {
    if (renderer == null) return
    removeAll {
        it.get() == renderer
    }
}

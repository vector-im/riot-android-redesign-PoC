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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.getSystemService
import im.vector.app.core.services.WiredHeadsetStateReceiver
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import timber.log.Timber
import java.util.concurrent.Executors

class CallAudioManager(
        val applicationContext: Context,
        val configChange: (() -> Unit)?
) {

    enum class SoundDevice {
        PHONE,
        SPEAKER,
        HEADSET,
        WIRELESS_HEADSET
    }

    // if all calls to audio manager not in the same thread it's not working well.
    private val executor = Executors.newSingleThreadExecutor()

    private var audioManager: AudioManager? = null

    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var savedAudioMode = AudioManager.MODE_INVALID

    private var connectedBlueToothHeadset: BluetoothProfile? = null
    private var wantsBluetoothConnection = false

    private var bluetoothAdapter: BluetoothAdapter? = null

    private var currentCall: MxCall? = null

    init {
        executor.execute {
            audioManager = applicationContext.getSystemService()
        }
        val bm = applicationContext.getSystemService<BluetoothManager>()
        val adapter = bm?.adapter
        Timber.d("## VOIP Bluetooth adapter $adapter")
        bluetoothAdapter = adapter
        adapter?.getProfileProxy(applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceDisconnected(profile: Int) {
                Timber.d("## VOIP onServiceDisconnected $profile")
                if (profile == BluetoothProfile.HEADSET) {
                    connectedBlueToothHeadset = null
                    configChange?.invoke()
                }
            }

            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                Timber.d("## VOIP onServiceConnected $profile , proxy:$proxy")
                if (profile == BluetoothProfile.HEADSET) {
                    connectedBlueToothHeadset = proxy
                    configChange?.invoke()
                }
            }
        }, BluetoothProfile.HEADSET)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->

        // Called on the listener to notify if the audio focus for this listener has been changed.
        // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
        // and whether that loss is transient, or whether the new focus holder will hold it for an
        // unknown amount of time.
        Timber.v("## VOIP: Audio focus change $focusChange")
    }

    fun startForCall(mxCall: MxCall) {
        currentCall = mxCall
        Timber.v("## VOIP: AudioManager startForCall ${mxCall.callId}")
        val audioManager = audioManager ?: return
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
        savedAudioMode = audioManager.mode

        // Request audio playout focus (without ducking) and install listener for changes in focus.

        // Remove the deprecation forces us to use 2 different method depending on API level
        @Suppress("DEPRECATION") val result = audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.d("## VOIP Audio focus request granted for VOICE_CALL streams")
        } else {
            Timber.d("## VOIP Audio focus request failed")
        }

        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false)

        adjustCurrentSoundDevice(mxCall)
    }

    private fun adjustCurrentSoundDevice(mxCall: MxCall) {
        currentCall = mxCall
        val audioManager = audioManager ?: return
        executor.execute {
            if (mxCall.state == CallState.LocalRinging && !isHeadsetOn()) {
                // Always use speaker if incoming call is in ringing state and a headset is not connected
                Timber.v("##VOIP: AudioManager default to SPEAKER (it is ringing)")
                setCurrentSoundDevice(SoundDevice.SPEAKER)
            } else if (mxCall.isVideoCall && !isHeadsetOn()) {
                // If there are no headset, start video output in speaker
                // (you can't watch the video and have the phone close to your ear)
                Timber.v("##VOIP: AudioManager default to speaker ")
                setCurrentSoundDevice(SoundDevice.SPEAKER)
            } else {
                // if a wired headset is plugged, sound will be directed to it
                // (can't really force earpiece when headset is plugged)
                if (isBluetoothHeadsetConnected(audioManager)) {
                    Timber.v("##VOIP: AudioManager default to WIRELESS_HEADSET ")
                    setCurrentSoundDevice(SoundDevice.WIRELESS_HEADSET)
                    // try now in case already connected?
                    audioManager.isBluetoothScoOn = true
                } else {
                    Timber.v("##VOIP: AudioManager default to PHONE/HEADSET ")
                    setCurrentSoundDevice(if (isWiredHeadsetOn()) SoundDevice.HEADSET else SoundDevice.PHONE)
                }
            }
        }
    }

    fun onCallConnected(mxCall: MxCall) {
        Timber.v("##VOIP: AudioManager call answered, adjusting current sound device")
        adjustCurrentSoundDevice(mxCall)
    }

    fun getAvailableSoundDevices(): List<SoundDevice> {
        return ArrayList<SoundDevice>().apply {
            if (isBluetoothHeadsetOn()) add(SoundDevice.WIRELESS_HEADSET)
            add(if (isWiredHeadsetOn()) SoundDevice.HEADSET else SoundDevice.PHONE)
            add(SoundDevice.SPEAKER)
        }
    }

    fun stop() {
        Timber.v("## VOIP: AudioManager stopCall")
        executor.execute {
            // Restore previously stored audio states.
            setSpeakerphoneOn(savedIsSpeakerPhoneOn)
            setMicrophoneMute(savedIsMicrophoneMute)
            audioManager?.mode = savedAudioMode

            connectedBlueToothHeadset?.let {
                if (audioManager != null && isBluetoothHeadsetConnected(audioManager!!)) {
                    audioManager?.stopBluetoothSco()
                    audioManager?.isBluetoothScoOn = false
                    audioManager?.isSpeakerphoneOn = false
                }
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
            }

            audioManager?.mode = AudioManager.MODE_NORMAL

            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun getCurrentSoundDevice(): SoundDevice {
        val audioManager = audioManager ?: return SoundDevice.PHONE
        if (audioManager.isSpeakerphoneOn) {
            return SoundDevice.SPEAKER
        } else {
            if (isBluetoothHeadsetConnected(audioManager)) return SoundDevice.WIRELESS_HEADSET
            return if (isHeadsetOn()) SoundDevice.HEADSET else SoundDevice.PHONE
        }
    }

    private fun isBluetoothHeadsetConnected(audioManager: AudioManager) =
            isBluetoothHeadsetOn()
                    && !connectedBlueToothHeadset?.connectedDevices.isNullOrEmpty()
                    && (wantsBluetoothConnection || audioManager.isBluetoothScoOn)

    fun setCurrentSoundDevice(device: SoundDevice) {
        executor.execute {
            Timber.v("## VOIP setCurrentSoundDevice $device")
            when (device) {
                SoundDevice.HEADSET,
                SoundDevice.PHONE            -> {
                    wantsBluetoothConnection = false
                    if (isBluetoothHeadsetOn()) {
                        audioManager?.stopBluetoothSco()
                        audioManager?.isBluetoothScoOn = false
                    }
                    setSpeakerphoneOn(false)
                }
                SoundDevice.SPEAKER          -> {
                    setSpeakerphoneOn(true)
                    wantsBluetoothConnection = false
                    audioManager?.stopBluetoothSco()
                    audioManager?.isBluetoothScoOn = false
                }
                SoundDevice.WIRELESS_HEADSET -> {
                    setSpeakerphoneOn(false)
                    // I cannot directly do it, i have to start then wait that it's connected
                    // to route to bt
                    audioManager?.startBluetoothSco()
                    wantsBluetoothConnection = true
                }
            }

            configChange?.invoke()
        }
    }

    fun bluetoothStateChange(plugged: Boolean) {
        executor.execute {
            if (plugged && wantsBluetoothConnection) {
                audioManager?.isBluetoothScoOn = true
            } else if (!plugged && !wantsBluetoothConnection) {
                audioManager?.stopBluetoothSco()
            }

            configChange?.invoke()
        }
    }

    fun wiredStateChange(event: WiredHeadsetStateReceiver.HeadsetPlugEvent) {
        executor.execute {
            // if it's plugged and speaker is on we should route to headset
            if (event.plugged && getCurrentSoundDevice() == SoundDevice.SPEAKER) {
                setCurrentSoundDevice(CallAudioManager.SoundDevice.HEADSET)
            } else if (!event.plugged) {
                if (!wantsBluetoothConnection) {
                    if (currentCall?.isVideoCall == true || currentCall?.state == CallState.LocalRinging) {
                        setCurrentSoundDevice(SoundDevice.SPEAKER)
                    } else {
                        setCurrentSoundDevice(SoundDevice.PHONE)
                    }
                }
            }
            configChange?.invoke()
        }
    }

    private fun isHeadsetOn(): Boolean {
        return isWiredHeadsetOn() || (audioManager?.let { isBluetoothHeadsetConnected(it) } ?: false)
    }

    private fun isWiredHeadsetOn(): Boolean {
        @Suppress("DEPRECATION")
        return audioManager?.isWiredHeadsetOn ?: false
    }

    private fun isBluetoothHeadsetOn(): Boolean {
        Timber.v("## VOIP: AudioManager isBluetoothHeadsetOn")
        try {
            if (connectedBlueToothHeadset == null) return false.also {
                Timber.v("## VOIP: AudioManager no connected bluetooth headset")
            }
            if (audioManager?.isBluetoothScoAvailableOffCall == false) return false.also {
                Timber.v("## VOIP: AudioManager isBluetoothScoAvailableOffCall false")
            }
            return true
        } catch (failure: Throwable) {
            Timber.e("## VOIP: AudioManager isBluetoothHeadsetOn failure ${failure.localizedMessage}")
            return false
        }
    }

    /** Sets the speaker phone mode.  */
    private fun setSpeakerphoneOn(on: Boolean) {
        Timber.v("## VOIP: AudioManager setSpeakerphoneOn $on")
        val wasOn = audioManager?.isSpeakerphoneOn ?: false
        if (wasOn == on) {
            return
        }
        audioManager?.isSpeakerphoneOn = on
    }

    /** Sets the microphone mute state.  */
    private fun setMicrophoneMute(on: Boolean) {
        Timber.v("## VOIP: AudioManager setMicrophoneMute $on")
        val wasMuted = audioManager?.isMicrophoneMute ?: false
        if (wasMuted == on) {
            return
        }
        audioManager?.isMicrophoneMute = on
    }

    /** true if the device has a telephony radio with data
     * communication support.   */
    private fun isThisPhone(): Boolean {
        return applicationContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY)
    }
}

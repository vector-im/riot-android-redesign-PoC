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

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.core.hardware.vibrate
import im.vector.app.core.utils.CountUpTimer
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.ViewVoiceMessageRecorderBinding
import im.vector.app.features.home.room.detail.timeline.helper.VoiceMessagePlaybackTracker
import org.matrix.android.sdk.api.extensions.orFalse
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.floor

/**
 * Encapsulates the voice message recording view and animations.
 */
class VoiceMessageRecorderView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), VoiceMessagePlaybackTracker.Listener {

    interface Callback {
        // Return true if the recording is started
        fun onVoiceRecordingStarted(): Boolean
        fun onVoiceRecordingEnded(isCancelled: Boolean)
        fun onVoiceRecordingPlaybackModeOn()
        fun onVoicePlaybackButtonClicked()
    }

    private val views: ViewVoiceMessageRecorderBinding

    var callback: Callback? = null
    var voiceMessagePlaybackTracker: VoiceMessagePlaybackTracker? = null
        set(value) {
            field = value
            value?.track(VoiceMessagePlaybackTracker.RECORDING_ID, this)
        }

    private var recordingState: RecordingState = RecordingState.NONE

    private var firstX: Float = 0f
    private var firstY: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastDistanceX: Float = 0f
    private var lastDistanceY: Float = 0f

    private var recordingTicker: CountUpTimer? = null

    private val dimensionConverter = DimensionConverter(context.resources)
    private val minimumMove = dimensionConverter.dpToPx(10)
    private val distanceToLock = dimensionConverter.dpToPx(34).toFloat()
    private val distanceToCancel = dimensionConverter.dpToPx(120).toFloat()
    private val rtlXMultiplier = context.resources.getInteger(R.integer.rtl_x_multiplier)

    init {
        inflate(context, R.layout.view_voice_message_recorder, this)
        views = ViewVoiceMessageRecorderBinding.bind(this)

        initVoiceRecordingViews()
        initListeners()
    }

    fun initVoiceRecordingViews() {
        hideRecordingViews()
        stopRecordingTicker()

        views.voiceMessageMicButton.isVisible = true
        views.voiceMessageSendButton.isVisible = false
    }

    private fun initListeners() {
        views.voiceMessageSendButton.setOnClickListener {
            stopRecordingTicker()
            hideRecordingViews()
            views.voiceMessageSendButton.isVisible = false
            recordingState = RecordingState.NONE
            callback?.onVoiceRecordingEnded(isCancelled = false)
        }

        views.voiceMessageDeletePlayback.setOnClickListener {
            stopRecordingTicker()
            hideRecordingViews()
            views.voiceMessageSendButton.isVisible = false
            recordingState = RecordingState.NONE
            callback?.onVoiceRecordingEnded(isCancelled = true)
        }

        views.voicePlaybackWaveform.setOnClickListener {
            if (recordingState != RecordingState.PLAYBACK) {
                recordingState = RecordingState.PLAYBACK
                showPlaybackViews()
            }
        }

        views.voicePlaybackControlButton.setOnClickListener {
            callback?.onVoicePlaybackButtonClicked()
        }

        views.voiceMessageMicButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleMicActionDown(event)
                    true
                }
                MotionEvent.ACTION_UP   -> {
                    handleMicActionUp()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleMicActionMove(event)
                    true
                }
                else                    ->
                    false
            }
        }
    }

    private fun handleMicActionDown(event: MotionEvent) {
        val recordingStarted = callback?.onVoiceRecordingStarted().orFalse()
        if (recordingStarted) {
            startRecordingTicker()
            renderToast(context.getString(R.string.voice_message_release_to_send_toast))
            recordingState = RecordingState.STARTED
            showRecordingViews()

            firstX = event.rawX
            firstY = event.rawY
            lastX = firstX
            lastY = firstY
            lastDistanceX = 0F
            lastDistanceY = 0F
        }
    }

    private fun handleMicActionUp() {
        if (recordingState != RecordingState.LOCKED) {
            stopRecordingTicker()
            val isCancelled = recordingState == RecordingState.NONE || recordingState == RecordingState.CANCELLED
            callback?.onVoiceRecordingEnded(isCancelled)
            recordingState = RecordingState.NONE
            hideRecordingViews()
        }
    }

    private fun handleMicActionMove(event: MotionEvent) {
        val currentX = event.rawX
        val currentY = event.rawY

        val distanceX = abs(firstX - currentX)
        val distanceY = abs(firstY - currentY)

        val isRecordingStateChanged = updateRecordingState(currentX, currentY, distanceX, distanceY)

        when (recordingState) {
            RecordingState.CANCELLING -> {
                val translationAmount = distanceX.coerceAtMost(distanceToCancel)
                views.voiceMessageMicButton.translationX = -translationAmount * rtlXMultiplier
                views.voiceMessageSlideToCancel.translationX = -translationAmount / 2 * rtlXMultiplier
                views.voiceMessageSlideToCancel.alpha = 1 - translationAmount / distanceToCancel / 3
                views.voiceMessageLockBackground.isVisible = false
                views.voiceMessageLockImage.isVisible = false
                views.voiceMessageLockArrow.isVisible = false
                // Reset Y translations
                views.voiceMessageMicButton.translationY = 0F
                views.voiceMessageLockArrow.translationY = 0F
            }
            RecordingState.LOCKING    -> {
                views.voiceMessageLockImage.setImageResource(R.drawable.ic_voice_message_unlocked)
                val translationAmount = -distanceY.coerceIn(0F, distanceToLock)
                views.voiceMessageMicButton.translationY = translationAmount
                views.voiceMessageLockArrow.translationY = translationAmount
                // Reset X translations
                views.voiceMessageMicButton.translationX = 0F
                views.voiceMessageSlideToCancel.translationX = 0F
            }
            RecordingState.CANCELLED  -> {
                callback?.onVoiceRecordingEnded(true)
                hideRecordingViews()
            }
            RecordingState.LOCKED     -> {
                if (isRecordingStateChanged) { // Do not update views if it was already in locked state.
                    views.voiceMessageLockImage.setImageResource(R.drawable.ic_voice_message_locked)
                    views.voiceMessageLockImage.postDelayed({
                        showRecordingLockedViews()
                    }, 500)
                }
            }
            RecordingState.STARTED    -> {
                showRecordingViews()
            }
            RecordingState.NONE       -> Timber.d("VoiceMessageRecorderView shouldn't be in NONE state while moving.")
            RecordingState.PLAYBACK   -> Timber.d("VoiceMessageRecorderView shouldn't be in PLAYBACK state while moving.")
        }
        lastX = currentX
        lastY = currentY
        lastDistanceX = distanceX
        lastDistanceY = distanceY
    }

    private fun updateRecordingState(currentX: Float, currentY: Float, distanceX: Float, distanceY: Float): Boolean {
        val previousRecordingState = recordingState
        if (recordingState == RecordingState.STARTED) {
            // Determine if cancelling or locking for the first move action.
            if (((currentX < firstX && rtlXMultiplier == 1) || (currentX > firstX && rtlXMultiplier == -1))
                    && distanceX > distanceY) {
                recordingState = RecordingState.CANCELLING
            } else if (currentY < firstY && distanceY > distanceX) {
                recordingState = RecordingState.LOCKING
            }
        } else if (recordingState == RecordingState.CANCELLING) {
            // Check if cancelling conditions met, also check if it should be initial state
            if (distanceX < minimumMove && distanceX < lastDistanceX) {
                recordingState = RecordingState.STARTED
            } else if (shouldCancelRecording(distanceX)) {
                recordingState = RecordingState.CANCELLED
            }
        } else if (recordingState == RecordingState.LOCKING) {
            // Check if locking conditions met, also check if it should be initial state
            if (distanceY < minimumMove && distanceY < lastDistanceY) {
                recordingState = RecordingState.STARTED
            } else if (shouldLockRecording(distanceY)) {
                recordingState = RecordingState.LOCKED
            }
        }
        return previousRecordingState != recordingState
    }

    private fun shouldCancelRecording(distanceX: Float): Boolean {
        return distanceX >= distanceToCancel
    }

    private fun shouldLockRecording(distanceY: Float): Boolean {
        return distanceY >= distanceToLock
    }

    private fun startRecordingTicker() {
        recordingTicker?.stop()
        recordingTicker = CountUpTimer().apply {
            tickListener = object : CountUpTimer.TickListener {
                override fun onTick(milliseconds: Long) {
                    onRecordingTick(milliseconds)
                }
            }
            resume()
        }
        onRecordingTick(0L)
    }

    private fun onRecordingTick(milliseconds: Long) {
        renderRecordingTimer(milliseconds / 1_000)
        val timeDiffToRecordingLimit = BuildConfig.VOICE_MESSAGE_DURATION_LIMIT_MS - milliseconds
        if (timeDiffToRecordingLimit <= 0) {
            views.voiceMessageRecordingLayout.post {
                recordingState = RecordingState.PLAYBACK
                showPlaybackViews()
                stopRecordingTicker()
            }
        } else if (timeDiffToRecordingLimit in 10_000..10_999) {
            views.voiceMessageRecordingLayout.post {
                renderToast(context.getString(R.string.voice_message_n_seconds_warning_toast, floor(timeDiffToRecordingLimit / 1000f).toInt()))
                vibrate(context)
            }
        }
    }

    private fun renderToast(message: String) {
        views.voiceMessageToast.removeCallbacks(hideToastRunnable)
        views.voiceMessageToast.text = message
        views.voiceMessageToast.isVisible = true
        views.voiceMessageToast.postDelayed(hideToastRunnable, 2_000)
    }

    private val hideToastRunnable = Runnable {
        views.voiceMessageToast.isVisible = false
    }

    private fun renderRecordingTimer(recordingTimeMillis: Long) {
        val formattedTimerText = DateUtils.formatElapsedTime(recordingTimeMillis)
        if (recordingState == RecordingState.LOCKED) {
            views.voicePlaybackTime.apply {
                post {
                    text = formattedTimerText
                }
            }
        } else {
            views.voiceMessageTimer.post {
                views.voiceMessageTimer.text = formattedTimerText
            }
        }
    }

    private fun renderRecordingWaveform(amplitudeList: List<Int>) {
        views.voicePlaybackWaveform.apply {
            post {
                // TODO We could avoid recreating the whole view here and just call update() with the new value(s).
                recreate()
                amplitudeList.forEach { amplitude ->
                    update(amplitude)
                }
            }
        }
    }

    private fun stopRecordingTicker() {
        recordingTicker?.stop()
        recordingTicker = null
    }

    private fun showRecordingViews() {
        views.voiceMessageMicButton.setImageResource(R.drawable.ic_voice_mic_recording)
        views.voiceMessageMicButton.updateLayoutParams<MarginLayoutParams> {
            setMargins(0, 0, 0, 0)
        }
        views.voiceMessageLockBackground.isVisible = true
        views.voiceMessageLockBackground.animate().setDuration(300).translationY(-dimensionConverter.dpToPx(148).toFloat()).start()
        views.voiceMessageLockImage.isVisible = true
        views.voiceMessageLockImage.setImageResource(R.drawable.ic_voice_message_unlocked)
        views.voiceMessageLockImage.animate().setDuration(500).translationY(-dimensionConverter.dpToPx(148).toFloat()).start()
        views.voiceMessageLockArrow.isVisible = true
        views.voiceMessageSlideToCancel.isVisible = true
        views.voiceMessageTimerIndicator.isVisible = true
        views.voiceMessageTimer.isVisible = true
        views.voiceMessageSlideToCancel.alpha = 1f
        views.voiceMessageSendButton.isVisible = false
    }

    private fun hideRecordingViews() {
        views.voiceMessageMicButton.setImageResource(R.drawable.ic_voice_mic)
        views.voiceMessageMicButton.animate().translationX(0f).translationY(0f).setDuration(0).start()
        views.voiceMessageMicButton.updateLayoutParams<MarginLayoutParams> {
            if (rtlXMultiplier == -1) {
                // RTL
                setMargins(dimensionConverter.dpToPx(10), 0, 0, dimensionConverter.dpToPx(12))
            } else {
                setMargins(0, 0, dimensionConverter.dpToPx(12), dimensionConverter.dpToPx(10))
            }
        }
        views.voiceMessageLockBackground.isVisible = false
        views.voiceMessageLockBackground.animate().translationY(0f).start()
        views.voiceMessageLockImage.isVisible = false
        views.voiceMessageLockImage.animate().translationY(0f).start()
        views.voiceMessageLockArrow.isVisible = false
        views.voiceMessageLockArrow.animate().translationY(0f).start()
        views.voiceMessageSlideToCancel.isVisible = false
        views.voiceMessageSlideToCancel.animate().translationX(0f).translationY(0f).start()
        views.voiceMessageTimerIndicator.isVisible = false
        views.voiceMessageTimer.isVisible = false
        views.voiceMessagePlaybackLayout.isVisible = false
    }

    private fun showRecordingLockedViews() {
        hideRecordingViews()
        views.voiceMessagePlaybackLayout.isVisible = true
        views.voiceMessagePlaybackTimerIndicator.isVisible = true
        views.voicePlaybackControlButton.isVisible = false
        views.voiceMessageSendButton.isVisible = true
        renderToast(context.getString(R.string.voice_message_tap_to_stop_toast))
    }

    private fun showPlaybackViews() {
        views.voiceMessagePlaybackTimerIndicator.isVisible = false
        views.voicePlaybackControlButton.isVisible = true
        callback?.onVoiceRecordingPlaybackModeOn()
    }

    private enum class RecordingState {
        NONE,
        STARTED,
        CANCELLING,
        CANCELLED,
        LOCKING,
        LOCKED,
        PLAYBACK
    }

    override fun onUpdate(state: VoiceMessagePlaybackTracker.Listener.State) {
        when (state) {
            is VoiceMessagePlaybackTracker.Listener.State.Recording -> {
                renderRecordingWaveform(state.amplitudeList)
            }
            is VoiceMessagePlaybackTracker.Listener.State.Playing   -> {
                views.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_pause)
                val formattedTimerText = DateUtils.formatElapsedTime((state.playbackTime / 1000).toLong())
                views.voicePlaybackTime.text = formattedTimerText
            }
            is VoiceMessagePlaybackTracker.Listener.State.Idle      -> {
                views.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_play)
            }
        }
    }
}

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

package im.vector.app.features.home.room.detail.timeline.item

import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.visualizer.amplitude.AudioRecordView
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.VoiceMessagePlaybackTracker

@EpoxyModelClass(layout = R.layout.item_timeline_event_base)
abstract class MessageVoiceItem : AbsMessageItem<MessageVoiceItem.Holder>() {

    init {
        ignoreSendStatusVisibility = true
    }

    @EpoxyAttribute
    var mxcUrl: String = ""

    @EpoxyAttribute
    var duration: Int = 0

    @EpoxyAttribute
    var waveform: List<Int> = emptyList()

    @EpoxyAttribute
    var izLocalFile = false

    @EpoxyAttribute
    var izDownloaded = false

    @EpoxyAttribute
    lateinit var contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder

    @EpoxyAttribute
    lateinit var contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var playbackControlButtonClickListener: ClickListener? = null

    @EpoxyAttribute
    lateinit var voiceMessagePlaybackTracker: VoiceMessagePlaybackTracker

    override fun bind(holder: Holder) {
        super.bind(holder)
        renderSendState(holder.voiceLayout, null)
        if (!attributes.informationData.sendState.hasFailed()) {
            contentUploadStateTrackerBinder.bind(attributes.informationData.eventId, izLocalFile, holder.progressLayout)
        } else {
            holder.voicePlaybackControlButton.setImageResource(R.drawable.ic_cross)
            holder.progressLayout.isVisible = false
        }

        holder.voicePlaybackWaveform.post {
            holder.voicePlaybackWaveform.recreate()
            waveform.forEach { amplitude ->
                holder.voicePlaybackWaveform.update(amplitude)
            }
        }

        holder.voicePlaybackControlButton.setOnClickListener { playbackControlButtonClickListener?.invoke(it) }

        voiceMessagePlaybackTracker.track(attributes.informationData.eventId, object : VoiceMessagePlaybackTracker.Listener {
            override fun onUpdate(state: VoiceMessagePlaybackTracker.Listener.State) {
                when (state) {
                    is VoiceMessagePlaybackTracker.Listener.State.Idle    -> renderIdleState(holder)
                    is VoiceMessagePlaybackTracker.Listener.State.Playing -> renderPlayingState(holder, state)
                    is VoiceMessagePlaybackTracker.Listener.State.Paused  -> renderPausedState(holder, state)
                }
            }
        })
    }

    private fun renderIdleState(holder: Holder) {
        holder.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_play)
        holder.voicePlaybackTime.text = formatPlaybackTime(duration)
    }

    private fun renderPlayingState(holder: Holder, state: VoiceMessagePlaybackTracker.Listener.State.Playing) {
        holder.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_pause)
        holder.voicePlaybackTime.text = formatPlaybackTime(state.playbackTime)
    }

    private fun renderPausedState(holder: Holder, state: VoiceMessagePlaybackTracker.Listener.State.Paused) {
        holder.voicePlaybackControlButton.setImageResource(R.drawable.ic_play_pause_play)
        holder.voicePlaybackTime.text = formatPlaybackTime(state.playbackTime)
    }

    private fun formatPlaybackTime(time: Int) = DateUtils.formatElapsedTime((time / 1000).toLong())

    override fun unbind(holder: Holder) {
        super.unbind(holder)
        contentUploadStateTrackerBinder.unbind(attributes.informationData.eventId)
        contentDownloadStateTrackerBinder.unbind(mxcUrl)
        voiceMessagePlaybackTracker.unTrack(attributes.informationData.eventId)
    }

    override fun getViewType() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val voiceLayout by bind<ViewGroup>(R.id.voiceLayout)
        val voicePlaybackControlButton by bind<ImageButton>(R.id.voicePlaybackControlButton)
        val voicePlaybackTime by bind<TextView>(R.id.voicePlaybackTime)
        val voicePlaybackWaveform by bind<AudioRecordView>(R.id.voicePlaybackWaveform)
        val progressLayout by bind<ViewGroup>(R.id.messageFileUploadProgressLayout)
    }

    companion object {
        private const val STUB_ID = R.id.messageContentVoiceStub
    }
}

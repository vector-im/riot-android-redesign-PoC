/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.model.rest.EncryptedFileInfo

@JsonClass(generateAdapter = true)
data class MessageAudioContent(
        /**
         * Required. Must be 'm.audio'.
         */
        @Json(name = "msgtype") override val msgType: String,

        /**
         * Required. A description of the audio e.g. 'Bee Gees - Stayin' Alive', or some kind of content description for accessibility e.g. 'audio attachment'.
         */
        @Json(name = "body") override val body: String,

        /**
         * Metadata for the audio clip referred to in url.
         */
        @Json(name = "info") val audioInfo: AudioInfo? = null,

        /**
         * Required if the file is not encrypted. The URL (typically MXC URI) to the audio clip.
         */
        @Json(name = "url") override val url: String? = null,

        @Json(name = "m.relates_to") override val relatesTo: RelationDefaultContent? = null,
        @Json(name = "m.new_content") override val newContent: Content? = null,

        /**
         * Required if the file is encrypted. Information on the encrypted file, as specified in End-to-end encryption.
         */
        @Json(name = "file") override val encryptedFileInfo: EncryptedFileInfo? = null,

        /**
         * Encapsulates waveform and duration of the audio.
         */
        @Json(name = "org.matrix.msc1767.audio") val audioWaveformInfo: AudioWaveformInfo? = null,

        /**
         * Indicates that is a voice message.
         */
        @Json(name = "org.matrix.msc3245.voice") val voiceMessageIndicator: JsonDict? = null
) : MessageWithAttachmentContent {

    override val mimeType: String?
        get() = audioInfo?.mimeType
}

/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * See https://github.com/matrix-org/matrix-doc/blob/travis/msc/audio-waveform/proposals/3246-audio-waveform.md
 */
@JsonClass(generateAdapter = true)
data class AudioWaveformInfo(
        @Json(name = "duration")
        val duration: Int? = null,

        /**
         * The array should have no less than 30 elements and no more than 120.
         * List of integers between zero and 1024, inclusive.
         */
        @Json(name = "waveform")
        val waveform: List<Int>? = null
)

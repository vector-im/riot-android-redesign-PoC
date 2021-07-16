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

package im.vector.app.features.voice

import java.io.File

interface VoiceRecorder {
    /**
     * Start the recording
     */
    fun startRecord()

    /**
     * Stop the recording
     */
    fun stopRecord()

    /**
     * Remove the file
     */
    fun cancelRecord()

    fun getMaxAmplitude(): Int

    /**
     * Not guaranteed to be a ogg file
     */
    fun getCurrentRecord(): File?

    /**
     * Guaranteed to be a ogg file
     */
    fun getVoiceMessageFile(): File?
}

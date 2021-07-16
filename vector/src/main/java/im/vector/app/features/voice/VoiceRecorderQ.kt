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

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File

@RequiresApi(Build.VERSION_CODES.Q)
class VoiceRecorderQ(context: Context) : AbstractVoiceRecorder(context, "ogg") {
    override fun setOutputFormat(mediaRecorder: MediaRecorder) {
        // We can directly use OGG here
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
    }

    override fun convertFile(recordedFile: File?): File? {
        // Nothing to do here
        return recordedFile
    }
}

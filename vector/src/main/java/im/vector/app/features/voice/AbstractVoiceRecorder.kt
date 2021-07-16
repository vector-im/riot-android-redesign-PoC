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
import java.io.File
import java.io.FileOutputStream

abstract class AbstractVoiceRecorder(
        context: Context,
        private val filenameExt: String
) : VoiceRecorder {
    private val outputDirectory = File(context.cacheDir, "voice_records")

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    init {
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }
    }

    abstract fun setOutputFormat(mediaRecorder: MediaRecorder)
    abstract fun convertFile(recordedFile: File?): File?

    private fun init() {
        MediaRecorder().let {
            it.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            setOutputFormat(it)
            it.setAudioEncodingBitRate(24000)
            it.setAudioSamplingRate(48000)
            mediaRecorder = it
        }
    }

    override fun startRecord() {
        init()
        outputFile = File(outputDirectory, "Voice message.$filenameExt")

        val mr = mediaRecorder ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mr.setOutputFile(outputFile)
        } else {
            mr.setOutputFile(FileOutputStream(outputFile).fd)
        }
        mr.prepare()
        mr.start()
    }

    override fun stopRecord() {
        // Can throw when the record is less than 1 second.
        mediaRecorder?.let {
            it.stop()
            it.reset()
            it.release()
        }
        mediaRecorder = null
    }

    override fun cancelRecord() {
        stopRecord()

        outputFile?.delete()
        outputFile = null
    }

    override fun getMaxAmplitude(): Int {
        return mediaRecorder?.maxAmplitude ?: 0
    }

    override fun getCurrentRecord(): File? {
        return outputFile
    }

    override fun getVoiceMessageFile(): File? {
        return convertFile(outputFile)
    }
}

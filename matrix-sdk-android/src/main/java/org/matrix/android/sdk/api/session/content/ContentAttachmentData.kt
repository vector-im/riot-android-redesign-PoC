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

package org.matrix.android.sdk.api.session.content

import android.net.Uri
import android.os.Parcelable
import androidx.exifinterface.media.ExifInterface
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.util.MimeTypes.normalizeMimeType

@Parcelize
@JsonClass(generateAdapter = true)
data class ContentAttachmentData(
        val size: Long = 0,
        val duration: Long? = 0,
        val date: Long = 0,
        val height: Long? = 0,
        val width: Long? = 0,
        val exifOrientation: Int = ExifInterface.ORIENTATION_UNDEFINED,
        val name: String? = null,
        val queryUri: Uri,
        val mimeType: String?,
        val type: Type,
        val waveform: List<Int>? = null
) : Parcelable {

    @JsonClass(generateAdapter = false)
    enum class Type {
        FILE,
        IMAGE,
        AUDIO,
        VIDEO
    }

    fun getSafeMimeType() = mimeType?.normalizeMimeType()
}

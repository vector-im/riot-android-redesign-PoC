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

package org.matrix.android.sdk.api.util

import org.matrix.android.sdk.api.extensions.orFalse

// The Android SDK does not provide constant for mime type, add some of them here
object MimeTypes {
    const val Any: String = "*/*"
    const val OctetStream = "application/octet-stream"
    const val Apk = "application/vnd.android.package-archive"

    const val Images = "image/*"

    const val Png = "image/png"
    const val BadJpg = "image/jpg"
    const val Jpeg = "image/jpeg"
    const val Gif = "image/gif"

    const val Ogg = "audio/ogg"

    fun String?.normalizeMimeType() = if (this == BadJpg) Jpeg else this

    fun String?.isMimeTypeImage() = this?.startsWith("image/").orFalse()
    fun String?.isMimeTypeVideo() = this?.startsWith("video/").orFalse()
    fun String?.isMimeTypeAudio() = this?.startsWith("audio/").orFalse()
}

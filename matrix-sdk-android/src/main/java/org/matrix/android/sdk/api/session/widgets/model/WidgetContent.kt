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

package org.matrix.android.sdk.api.session.widgets.model

import android.annotation.SuppressLint
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.util.safeCapitalize

/**
 * Ref: https://github.com/matrix-org/matrix-doc/issues/1236
 */
@JsonClass(generateAdapter = true)
data class WidgetContent(
        @Json(name = "creatorUserId") val creatorUserId: String? = null,
        @Json(name = "id") val id: String? = null,
        @Json(name = "type") val type: String? = null,
        @Json(name = "url") val url: String? = null,
        @Json(name = "name") val name: String? = null,
        @Json(name = "data") val data: JsonDict = emptyMap(),
        @Json(name = "waitForIframeLoad") val waitForIframeLoad: Boolean = false
) {

    fun isActive() = type != null && url != null

    @SuppressLint("DefaultLocale")
    fun getHumanName(): String {
        return (name ?: type ?: "").safeCapitalize()
    }
}

/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.core.utils

import java.net.URL

fun String.isValidUrl(): Boolean {
    return try {
        URL(this)
        true
    } catch (t: Throwable) {
        false
    }
}

/**
 * Ensure string starts with "http". If it is not the case, "https://" is added, only if the String is not empty
 */
fun String.ensureProtocol(): String {
    return when {
        isEmpty()           -> this
        !startsWith("http") -> "https://$this"
        else                -> this
    }
}

fun String.ensureTrailingSlash(): String {
    return when {
        isEmpty()      -> this
        !endsWith("/") -> "$this/"
        else           -> this
    }
}

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

package im.vector.app.features.html

import android.os.Build
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import javax.inject.Inject

class SpanUtils @Inject constructor() {
    // Workaround for https://issuetracker.google.com/issues/188454876
    fun canUseTextFuture(charSequence: CharSequence): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // On old devices, it works correctly
            return true
        }

        if (charSequence !is Spanned) {
            return true
        }

        return charSequence
                .getSpans(0, charSequence.length, Any::class.java)
                .all { it !is StrikethroughSpan && it !is UnderlineSpan }
    }
}

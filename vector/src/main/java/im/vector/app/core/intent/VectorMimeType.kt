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

package im.vector.app.core.intent

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import im.vector.app.core.utils.getFileExtension
import timber.log.Timber
import java.util.Locale

/**
 * Returns the mimetype from a uri.
 *
 * @param context the context
 * @return the mimetype
 */
fun getMimeTypeFromUri(context: Context, uri: Uri): String? {
    var mimeType: String? = null

    try {
        mimeType = context.contentResolver.getType(uri)

        // try to find the mimetype from the filename
        if (null == mimeType) {
            val extension = getFileExtension(uri.toString())
            if (extension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            }
        }

        if (null != mimeType) {
            // the mimetype is sometimes in uppercase.
            mimeType = mimeType.lowercase(Locale.ROOT)
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to open resource input stream")
    }

    return mimeType
}

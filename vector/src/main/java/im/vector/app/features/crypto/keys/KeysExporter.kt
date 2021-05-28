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

package im.vector.app.features.crypto.keys

import android.content.Context
import android.net.Uri
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.internal.extensions.foldToCallback
import org.matrix.android.sdk.internal.util.awaitCallback

class KeysExporter(private val session: Session) {

    /**
     * Export keys and return the file path with the callback
     */
    fun export(context: Context, password: String, uri: Uri, callback: MatrixCallback<Boolean>) {
        session.coroutineScope.launch(Dispatchers.Main) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val data = awaitCallback<ByteArray> { session.cryptoService().exportRoomKeys(password, it) }
                    val os = context.contentResolver?.openOutputStream(uri)
                    if (os == null) {
                        false
                    } else {
                        os.write(data)
                        os.flush()
                        true
                    }
                }
            }.foldToCallback(callback)
        }
    }
}

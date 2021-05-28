/*
 * Copyright 2019 New Vector Ltd
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
 *
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.vanniktech.emoji.EmojiEditText
import im.vector.app.core.extensions.ooi
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.features.html.PillImageSpan
import timber.log.Timber

class ComposerEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.editTextStyle)
    : EmojiEditText(context, attrs, defStyleAttr) {

    interface Callback {
        fun onRichContentSelected(contentUri: Uri): Boolean
    }

    var callback: Callback? = null

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo) ?: return null
        EditorInfoCompat.setContentMimeTypes(editorInfo, arrayOf("*/*"))

        val callback =
                InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
                    val lacksPermission = (flags and
                            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && lacksPermission) {
                        try {
                            inputContentInfo.requestPermission()
                        } catch (e: Exception) {
                            return@OnCommitContentListener false
                        }
                    }
                    callback?.onRichContentSelected(inputContentInfo.contentUri) ?: false
                }
        return InputConnectionCompat.createWrapper(ic, editorInfo, callback)
    }

    init {
        addTextChangedListener(
                object : SimpleTextWatcher() {
                    var spanToRemove: PillImageSpan? = null

                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        Timber.v("Pills: beforeTextChanged: start:$start count:$count after:$after")

                        if (count > after) {
                            // A char has been deleted
                            val deleteCharPosition = start + count
                            Timber.v("Pills: beforeTextChanged: deleted char at $deleteCharPosition")

                            // Get the first span at this position
                            spanToRemove = editableText.getSpans(deleteCharPosition, deleteCharPosition, PillImageSpan::class.java)
                                    .ooi { Timber.v("Pills: beforeTextChanged: found ${it.size} span(s)") }
                                    .firstOrNull()
                        }
                    }

                    override fun afterTextChanged(s: Editable) {
                        if (spanToRemove != null) {
                            val start = editableText.getSpanStart(spanToRemove)
                            val end = editableText.getSpanEnd(spanToRemove)
                            Timber.v("Pills: afterTextChanged Removing the span start:$start end:$end")
                            // Must be done before text replacement
                            editableText.removeSpan(spanToRemove)
                            if (start != -1 && end != -1) {
                                editableText.replace(start, end, "")
                            }
                            spanToRemove = null
                        }
                    }
                }
        )
    }
}

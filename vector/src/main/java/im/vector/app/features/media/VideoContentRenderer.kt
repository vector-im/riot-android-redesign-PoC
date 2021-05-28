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
 */

package im.vector.app.features.media

import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.internal.crypto.attachments.ElementToDecrypt
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject

class VideoContentRenderer @Inject constructor(private val localFilesHelper: LocalFilesHelper,
                                               private val activeSessionHolder: ActiveSessionHolder,
                                               private val errorFormatter: ErrorFormatter) {

    private val sessionScope: CoroutineScope
        get() = activeSessionHolder.getActiveSession().coroutineScope

    @Parcelize
    data class Data(
            override val eventId: String,
            override val filename: String,
            override val mimeType: String?,
            override val url: String?,
            override val elementToDecrypt: ElementToDecrypt?,
            val thumbnailMediaData: ImageContentRenderer.Data,
            // If true will load non mxc url, be careful to set it only for video sent by you
            override val allowNonMxcUrls: Boolean = false
    ) : AttachmentData

    fun render(data: Data,
               thumbnailView: ImageView,
               loadingView: ProgressBar,
               videoView: VideoView,
               errorView: TextView) {
        val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()

        if (data.elementToDecrypt != null) {
            Timber.v("Decrypt video")
            videoView.isVisible = false

            if (data.url == null) {
                loadingView.isVisible = false
                errorView.isVisible = true
                errorView.setText(R.string.unknown_error)
            } else if (localFilesHelper.isLocalFile(data.url) && data.allowNonMxcUrls) {
                thumbnailView.isVisible = false
                loadingView.isVisible = false
                videoView.isVisible = true
                videoView.setVideoPath(URLEncoder.encode(data.url, Charsets.US_ASCII.displayName()))
                videoView.start()
            } else {
                thumbnailView.isVisible = true
                loadingView.isVisible = true

                sessionScope.launch {
                    val result = runCatching {
                        activeSessionHolder.getActiveSession().fileService()
                                .downloadFile(
                                        fileName = data.filename,
                                        mimeType = data.mimeType,
                                        url = data.url,
                                        elementToDecrypt = data.elementToDecrypt)
                    }
                    withContext(Dispatchers.Main) {
                        result.fold(
                                { data ->
                                    thumbnailView.isVisible = false
                                    loadingView.isVisible = false
                                    videoView.isVisible = true

                                    videoView.setVideoPath(data.path)
                                    videoView.start()
                                },
                                {
                                    loadingView.isVisible = false
                                    errorView.isVisible = true
                                    errorView.text = errorFormatter.toHumanReadable(it)
                                }
                        )
                    }
                }
            }
        } else {
            val resolvedUrl = contentUrlResolver.resolveFullSize(data.url)
                    ?: data.url?.takeIf { localFilesHelper.isLocalFile(data.url) && data.allowNonMxcUrls }

            if (resolvedUrl == null) {
                thumbnailView.isVisible = false
                loadingView.isVisible = false
                errorView.isVisible = true
                errorView.setText(R.string.unknown_error)
            } else {
                // Temporary code, some remote videos are not played by videoview setVideoUri
                // So for now we download them then play
                thumbnailView.isVisible = true
                loadingView.isVisible = true

                sessionScope.launch {
                    val result = runCatching {
                        activeSessionHolder.getActiveSession().fileService()
                                .downloadFile(
                                        fileName = data.filename,
                                        mimeType = data.mimeType,
                                        url = data.url,
                                        elementToDecrypt = null)
                    }
                    withContext(Dispatchers.Main) {
                        result.fold(
                                { data ->
                                    thumbnailView.isVisible = false
                                    loadingView.isVisible = false
                                    videoView.isVisible = true

                                    videoView.setVideoPath(data.path)
                                    videoView.start()
                                },
                                {
                                    loadingView.isVisible = false
                                    errorView.isVisible = true
                                    errorView.text = errorFormatter.toHumanReadable(it)
                                }
                        )
                    }
                }
            }
        }
    }
}

/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.media

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.lib.attachmentviewer.AttachmentInfo
import im.vector.lib.attachmentviewer.AttachmentSourceProvider
import im.vector.lib.attachmentviewer.ImageLoaderTarget
import im.vector.lib.attachmentviewer.VideoLoaderTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.events.model.isVideoMessage
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import java.io.File

abstract class BaseAttachmentProvider<Type>(
        private val attachments: List<Type>,
        private val imageContentRenderer: ImageContentRenderer,
        protected val fileService: FileService,
        private val coroutineScope: CoroutineScope,
        private val dateFormatter: VectorDateFormatter,
        private val stringProvider: StringProvider
) : AttachmentSourceProvider {

    interface InteractionListener {
        fun onDismissTapped()
        fun onShareTapped()
        fun onPlayPause(play: Boolean)
        fun videoSeekTo(percent: Int)
    }

    var interactionListener: InteractionListener? = null

    private var overlayView: AttachmentOverlayView? = null

    final override fun getItemCount() = attachments.size

    protected fun getItem(position: Int) = attachments[position]

    final override fun overlayViewAtPosition(context: Context, position: Int): View? {
        if (position == -1) return null
        if (overlayView == null) {
            overlayView = AttachmentOverlayView(context)
            overlayView?.onBack = {
                interactionListener?.onDismissTapped()
            }
            overlayView?.onShareCallback = {
                interactionListener?.onShareTapped()
            }
            overlayView?.onPlayPause = { play ->
                interactionListener?.onPlayPause(play)
            }
            overlayView?.videoSeekTo = { percent ->
                interactionListener?.videoSeekTo(percent)
            }
        }

        val timelineEvent = getTimelineEventAtPosition(position)
        if (timelineEvent != null) {
            val dateString = dateFormatter.format(timelineEvent.root.originServerTs, DateFormatKind.DEFAULT_DATE_AND_TIME)
            overlayView?.updateWith(
                    counter = stringProvider.getString(R.string.attachment_viewer_item_x_of_y, position + 1, getItemCount()),
                    senderInfo = "${timelineEvent.senderInfo.disambiguatedDisplayName} $dateString"
            )
            overlayView?.views?.overlayVideoControlsGroup?.isVisible = timelineEvent.root.isVideoMessage()
        } else {
            overlayView?.updateWith("", "")
        }

        return overlayView
    }

    abstract fun getTimelineEventAtPosition(position: Int): TimelineEvent?

    override fun loadImage(target: ImageLoaderTarget, info: AttachmentInfo.Image) {
        (info.data as? ImageContentRenderer.Data)?.let {
            imageContentRenderer.render(it, target.contextView(), object : CustomViewTarget<ImageView, Drawable>(target.contextView()) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    target.onLoadFailed(info.uid, errorDrawable)
                }

                override fun onResourceCleared(placeholder: Drawable?) {
                    target.onResourceCleared(info.uid, placeholder)
                }

                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    target.onResourceReady(info.uid, resource)
                }
            })
        }
    }

    override fun loadImage(target: ImageLoaderTarget, info: AttachmentInfo.AnimatedImage) {
        (info.data as? ImageContentRenderer.Data)?.let {
            imageContentRenderer.render(it, target.contextView(), object : CustomViewTarget<ImageView, Drawable>(target.contextView()) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    target.onLoadFailed(info.uid, errorDrawable)
                }

                override fun onResourceCleared(placeholder: Drawable?) {
                    target.onResourceCleared(info.uid, placeholder)
                }

                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    target.onResourceReady(info.uid, resource)
                }
            })
        }
    }

    override fun loadVideo(target: VideoLoaderTarget, info: AttachmentInfo.Video) {
        val data = info.data as? VideoContentRenderer.Data ?: return

        imageContentRenderer.render(data.thumbnailMediaData, target.contextView(), object : CustomViewTarget<ImageView, Drawable>(target.contextView()) {
            override fun onLoadFailed(errorDrawable: Drawable?) {
                target.onThumbnailLoadFailed(info.uid, errorDrawable)
            }

            override fun onResourceCleared(placeholder: Drawable?) {
                target.onThumbnailResourceCleared(info.uid, placeholder)
            }

            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                target.onThumbnailResourceReady(info.uid, resource)
            }
        })

        if (data.url?.startsWith("content://") == true && data.allowNonMxcUrls) {
            target.onVideoURLReady(info.uid, data.url)
        } else {
            target.onVideoFileLoading(info.uid)
            coroutineScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    fileService.downloadFile(
                            fileName = data.filename,
                            mimeType = data.mimeType,
                            url = data.url,
                            elementToDecrypt = data.elementToDecrypt
                    )
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                            { target.onVideoFileReady(info.uid, it) },
                            { target.onVideoFileLoadFailed(info.uid) }
                    )
                }
            }
        }
    }

    override fun clear(id: String) {
        // TODO("Not yet implemented")
    }

    abstract suspend fun getFileForSharing(position: Int): File?
}

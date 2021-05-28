/*
 * Copyright 2020 New Vector Ltd
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

package im.vector.app.features.attachments.preview

import com.airbnb.epoxy.TypedEpoxyController
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import javax.inject.Inject

class AttachmentBigPreviewController @Inject constructor() : TypedEpoxyController<AttachmentsPreviewViewState>() {

    override fun buildModels(data: AttachmentsPreviewViewState) {
        data.attachments.forEach {
            attachmentBigPreviewItem {
                id(it.queryUri.toString())
                attachment(it)
            }
        }
    }
}

class AttachmentMiniaturePreviewController @Inject constructor() : TypedEpoxyController<AttachmentsPreviewViewState>() {

    interface Callback {
        fun onAttachmentClicked(position: Int, contentAttachmentData: ContentAttachmentData)
    }

    var callback: Callback? = null

    override fun buildModels(data: AttachmentsPreviewViewState) {
        val host = this
        data.attachments.forEachIndexed { index, contentAttachmentData ->
            attachmentMiniaturePreviewItem {
                id(contentAttachmentData.queryUri.toString())
                attachment(contentAttachmentData)
                checked(data.currentAttachmentIndex == index)
                clickListener { _ ->
                    host.callback?.onAttachmentClicked(index, contentAttachmentData)
                }
            }
        }
    }
}

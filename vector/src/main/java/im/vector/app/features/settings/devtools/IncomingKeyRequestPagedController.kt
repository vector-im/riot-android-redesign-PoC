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

package im.vector.app.features.settings.devtools

import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.paging.PagedListEpoxyController
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.ui.list.GenericItem_
import im.vector.app.core.utils.createUIHandler
import me.gujun.android.span.span
import org.matrix.android.sdk.internal.crypto.IncomingRoomKeyRequest
import javax.inject.Inject

class IncomingKeyRequestPagedController @Inject constructor(
        private val vectorDateFormatter: VectorDateFormatter
) : PagedListEpoxyController<IncomingRoomKeyRequest>(
        // Important it must match the PageList builder notify Looper
        modelBuildingHandler = createUIHandler()
) {

    interface InteractionListener {
        // fun didTap(data: UserAccountData)
    }

    var interactionListener: InteractionListener? = null

    override fun buildItemModel(currentPosition: Int, item: IncomingRoomKeyRequest?): EpoxyModel<*> {
        val host = this
        val roomKeyRequest = item ?: return GenericItem_().apply { id(currentPosition) }

        return GenericItem_().apply {
            id(roomKeyRequest.requestId)
            title(roomKeyRequest.requestId)
            description(
                    span {
                        span("From: ") {
                            textStyle = "bold"
                        }
                        span("${roomKeyRequest.userId}")
                        +host.vectorDateFormatter.format(roomKeyRequest.localCreationTimestamp, DateFormatKind.DEFAULT_DATE_AND_TIME)
                        span("\nsessionId:") {
                            textStyle = "bold"
                        }
                        +"${roomKeyRequest.requestBody?.sessionId}"
                        span("\nFrom device:") {
                            textStyle = "bold"
                        }
                        +"${roomKeyRequest.deviceId}"
                        span("\nstate: ") {
                            textStyle = "bold"
                        }
                        +roomKeyRequest.state.name
                    }
            )
        }
    }
}

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
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.GenericItem
import im.vector.app.core.ui.list.GenericItem_
import im.vector.app.core.utils.createUIHandler
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.internal.crypto.model.event.OlmEventContent
import org.matrix.android.sdk.internal.crypto.model.event.SecretSendEventContent
import org.matrix.android.sdk.internal.crypto.model.rest.ForwardedRoomKeyContent
import org.matrix.android.sdk.internal.crypto.model.rest.GossipingToDeviceObject
import org.matrix.android.sdk.internal.crypto.model.rest.RoomKeyShareRequest
import org.matrix.android.sdk.internal.crypto.model.rest.SecretShareRequest
import javax.inject.Inject

class GossipingTrailPagedEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val vectorDateFormatter: VectorDateFormatter,
        private val colorProvider: ColorProvider
) : PagedListEpoxyController<Event>(
        // Important it must match the PageList builder notify Looper
        modelBuildingHandler = createUIHandler()
) {

    interface InteractionListener {
        fun didTap(event: Event)
    }

    var interactionListener: InteractionListener? = null

    override fun buildItemModel(currentPosition: Int, item: Event?): EpoxyModel<*> {
        val host = this
        val event = item ?: return GenericItem_().apply { id(currentPosition) }
        return GenericItem_().apply {
            id(event.hashCode())
            itemClickAction(GenericItem.Action("view").apply { perform = Runnable { host.interactionListener?.didTap(event) } })
            title(
                    if (event.isEncrypted()) {
                        "${event.getClearType()} [encrypted]"
                    } else {
                        event.type
                    }
            )
            description(
                    span {
                        +host.vectorDateFormatter.format(event.ageLocalTs, DateFormatKind.DEFAULT_DATE_AND_TIME)
                        span("\nfrom: ") {
                            textStyle = "bold"
                        }
                        +"${event.senderId}"
                        apply {
                            if (event.getClearType() == EventType.ROOM_KEY_REQUEST) {
                                val content = event.getClearContent().toModel<RoomKeyShareRequest>()
                                span("\nreqId:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.requestId}"
                                span("\naction:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.action}"
                                if (content?.action == GossipingToDeviceObject.ACTION_SHARE_REQUEST) {
                                    span("\nsessionId:") {
                                        textStyle = "bold"
                                    }
                                    +" ${content.body?.sessionId}"
                                }
                                span("\nrequestedBy: ") {
                                    textStyle = "bold"
                                }
                                +"${content?.requestingDeviceId}"
                            } else if (event.getClearType() == EventType.FORWARDED_ROOM_KEY) {
                                val encryptedContent = event.content.toModel<OlmEventContent>()
                                val content = event.getClearContent().toModel<ForwardedRoomKeyContent>()
                                if (event.mxDecryptionResult == null) {
                                    span("**Failed to Decrypt** ${event.mCryptoError}") {
                                        textColor = host.colorProvider.getColor(R.color.vector_error_color)
                                    }
                                }
                                span("\nsessionId:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.sessionId}"
                                span("\nFrom Device (sender key):") {
                                    textStyle = "bold"
                                }
                                +" ${encryptedContent?.senderKey}"
                            } else if (event.getClearType() == EventType.ROOM_KEY) {
                                // it's a bit of a fake event for trail reasons
                                val content = event.getClearContent()
                                span("\nsessionId:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.get("session_id")}"
                                span("\nroomId:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.get("room_id")}"
                                span("\nTo :") {
                                    textStyle = "bold"
                                }
                                +" ${content?.get("_dest") ?: "me"}"
                            } else if (event.getClearType() == EventType.SEND_SECRET) {
                                val content = event.getClearContent().toModel<SecretSendEventContent>()

                                span("\nrequestId:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.requestId}"
                                span("\nFrom Device:") {
                                    textStyle = "bold"
                                }
                                +" ${event.mxDecryptionResult?.payload?.get("sender_device")}"
                            } else if (event.getClearType() == EventType.REQUEST_SECRET) {
                                val content = event.getClearContent().toModel<SecretShareRequest>()
                                span("\nreqId:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.requestId}"
                                span("\naction:") {
                                    textStyle = "bold"
                                }
                                +" ${content?.action}"
                                if (content?.action == GossipingToDeviceObject.ACTION_SHARE_REQUEST) {
                                    span("\nsecretName:") {
                                        textStyle = "bold"
                                    }
                                    +" ${content.secretName}"
                                }
                                span("\nrequestedBy: ") {
                                    textStyle = "bold"
                                }
                                +"${content?.requestingDeviceId}"
                            } else if (event.getClearType() == EventType.ENCRYPTED) {
                                span("**Failed to Decrypt** ${event.mCryptoError}") {
                                        textColor = host.colorProvider.getColor(R.color.vector_error_color)
                                    }
                            }
                        }
                    }
            )
        }
    }
}

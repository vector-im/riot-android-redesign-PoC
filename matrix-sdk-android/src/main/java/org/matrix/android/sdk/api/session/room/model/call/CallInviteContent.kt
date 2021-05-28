/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.room.model.call

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * This event is sent by the caller when they wish to establish a call.
 */
@JsonClass(generateAdapter = true)
data class CallInviteContent(
        /**
         * Required. A unique identifier for the call.
         */
        @Json(name = "call_id") override val callId: String?,
        /**
         * Required. ID to let user identify remote echo of their own events
         */
        @Json(name = "party_id") override val partyId: String? = null,
        /**
         * Required. The session description object
         */
        @Json(name = "offer") val offer: Offer?,
        /**
         * Required. The version of the VoIP specification this message adheres to.
         */
        @Json(name = "version") override val version: String?,
        /**
         * Required. The time in milliseconds that the invite is valid for.
         * Once the invite age exceeds this value, clients should discard it.
         * They should also no longer show the call as awaiting an answer in the UI.
         */
        @Json(name = "lifetime") val lifetime: Int?,
        /**
         * The field should be added for all invites where the target is a specific user
         */
        @Json(name = "invitee") val invitee: String? = null,
        /**
         * Capability advertisement.
         */
        @Json(name = "capabilities") val capabilities: CallCapabilities? = null

): CallSignalingContent  {
    @JsonClass(generateAdapter = true)
    data class Offer(
            /**
             * Required. The type of session description. Must be 'offer'.
             */
            @Json(name = "type") val type: SdpType? = SdpType.OFFER,
            /**
             * Required. The SDP text of the session description.
             */
            @Json(name = "sdp") val sdp: String?
    ) {
        companion object {
            const val SDP_VIDEO = "m=video"
        }
    }

    fun isVideo() = offer?.sdp?.contains(Offer.SDP_VIDEO) == true
}

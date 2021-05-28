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
 * This introduces SDP negotiation semantics for media pause, hold/resume, ICE restarts and voice/video call up/downgrading.
 */
@JsonClass(generateAdapter = true)
data class CallNegotiateContent(
        /**
         * Required. The ID of the call this event relates to.
         */
        @Json(name = "call_id") override val callId: String,
        /**
         * Required. ID to let user identify remote echo of their own events
         */
        @Json(name = "party_id") override val partyId: String? = null,
        /**
         * Required. The time in milliseconds that the negotiation is valid for. Once exceeded the sender
         * of the negotiate event should consider the negotiation failed (timed out) and the recipient should ignore it.
         **/
        @Json(name = "lifetime") val lifetime: Int?,
        /**
         * Required. The session description object
         */
        @Json(name = "description") val description: Description? = null,

        /**
         * Required. The version of the VoIP specification this message adheres to.
         */
        @Json(name = "version") override val version: String?

        ): CallSignalingContent  {
    @JsonClass(generateAdapter = true)
    data class Description(
            /**
             * Required. The type of session description.
             */
            @Json(name = "type") val type: SdpType?,
            /**
             * Required. The SDP text of the session description.
             */
            @Json(name = "sdp") val sdp: String?
    )
}

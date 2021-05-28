/*
 * Copyright (c) 2020 The Matrix.org Foundation C.I.C.
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

interface CallSignalingContent {
    /**
     * Required. A unique identifier for the call.
     */
    val callId: String?

    /**
     * Required. ID to let user identify remote echo of their own events
     */
    val partyId: String?

    /**
     * Required. The version of the VoIP specification this message adheres to. This specification is version 0.
     */
    val version: String?
}

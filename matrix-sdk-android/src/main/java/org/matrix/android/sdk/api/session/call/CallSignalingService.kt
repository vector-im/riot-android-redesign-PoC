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

package org.matrix.android.sdk.api.session.call

interface CallSignalingService {

    suspend fun getTurnServer(): TurnServerResponse

    /**
     * Create an outgoing call
     */
    fun createOutgoingCall(roomId: String, otherUserId: String, isVideoCall: Boolean): MxCall

    fun addCallListener(listener: CallListener)

    fun removeCallListener(listener: CallListener)

    fun getCallWithId(callId: String): MxCall?

    fun isThereAnyActiveCall(): Boolean
}

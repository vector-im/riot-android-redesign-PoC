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

package org.matrix.android.sdk.internal.crypto.algorithms

import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.crypto.IncomingRoomKeyRequest
import org.matrix.android.sdk.internal.crypto.IncomingSecretShareRequest
import org.matrix.android.sdk.internal.crypto.MXEventDecryptionResult

/**
 * An interface for decrypting data
 */
internal interface IMXDecrypting {

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the decryption information, or an error
     */
    @Throws(MXCryptoError::class)
    fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult

    /**
     * Handle a key event.
     *
     * @param event the key event.
     * @return true if the key should be backed up
     */
    fun onRoomKeyEvent(event: Event): Boolean = false

    /**
     * Check if the some messages can be decrypted with a new session
     *
     * @param senderKey the session sender key
     * @param sessionId the session id
     */
    fun onNewSession(senderKey: String, sessionId: String) {}

    /**
     * Determine if we have the keys necessary to respond to a room key request
     *
     * @param request keyRequest
     * @return true if we have the keys and could (theoretically) share
     */
    fun hasKeysForKeyRequest(request: IncomingRoomKeyRequest): Boolean = false

    /**
     * Send the response to a room key request.
     *
     * @param request keyRequest
     */
    fun shareKeysWithDevice(request: IncomingRoomKeyRequest) {}

    fun shareSecretWithDevice(request: IncomingSecretShareRequest, secretValue: String) {}

    fun requestKeysForEvent(event: Event, withHeld: Boolean)
}

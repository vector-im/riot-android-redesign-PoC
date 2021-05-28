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

package org.matrix.android.sdk.internal.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.internal.crypto.actions.EnsureOlmSessionsForDevicesAction
import org.matrix.android.sdk.internal.crypto.actions.MessageEncrypter
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.event.OlmEventContent
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.extensions.foldToCallback
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import timber.log.Timber
import javax.inject.Inject
import kotlin.jvm.Throws

@SessionScope
internal class EventDecryptor @Inject constructor(
        private val cryptoCoroutineScope: CoroutineScope,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val roomDecryptorProvider: RoomDecryptorProvider,
        private val messageEncrypter: MessageEncrypter,
        private val sendToDeviceTask: SendToDeviceTask,
        private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction,
        private val cryptoStore: IMXCryptoStore
) {

    // The date of the last time we forced establishment
    // of a new session for each user:device.
    private val lastNewSessionForcedDates = MXUsersDevicesMap<Long>()

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the MXEventDecryptionResult data, or throw in case of error
     */
    @Throws(MXCryptoError::class)
    fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult {
        return internalDecryptEvent(event, timeline)
    }

    /**
     * Decrypt an event asynchronously
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @param callback the callback to return data or null
     */
    fun decryptEventAsync(event: Event, timeline: String, callback: MatrixCallback<MXEventDecryptionResult>) {
        // is it needed to do that on the crypto scope??
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            runCatching {
                internalDecryptEvent(event, timeline)
            }.foldToCallback(callback)
        }
    }

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the MXEventDecryptionResult data, or null in case of error
     */
    @Throws(MXCryptoError::class)
    private fun internalDecryptEvent(event: Event, timeline: String): MXEventDecryptionResult {
        val eventContent = event.content
        if (eventContent == null) {
            Timber.e("## CRYPTO | decryptEvent : empty event content")
            throw MXCryptoError.Base(MXCryptoError.ErrorType.BAD_ENCRYPTED_MESSAGE, MXCryptoError.BAD_ENCRYPTED_MESSAGE_REASON)
        } else {
            val algorithm = eventContent["algorithm"]?.toString()
            val alg = roomDecryptorProvider.getOrCreateRoomDecryptor(event.roomId, algorithm)
            if (alg == null) {
                val reason = String.format(MXCryptoError.UNABLE_TO_DECRYPT_REASON, event.eventId, algorithm)
                Timber.e("## CRYPTO | decryptEvent() : $reason")
                throw MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_DECRYPT, reason)
            } else {
                try {
                    return alg.decryptEvent(event, timeline)
                } catch (mxCryptoError: MXCryptoError) {
                    Timber.v("## CRYPTO | internalDecryptEvent : Failed to decrypt ${event.eventId} reason: $mxCryptoError")
                    if (algorithm == MXCRYPTO_ALGORITHM_OLM) {
                        if (mxCryptoError is MXCryptoError.Base
                                && mxCryptoError.errorType == MXCryptoError.ErrorType.BAD_ENCRYPTED_MESSAGE) {
                            // need to find sending device
                            cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
                                val olmContent = event.content.toModel<OlmEventContent>()
                                cryptoStore.getUserDevices(event.senderId ?: "")
                                        ?.values
                                        ?.firstOrNull { it.identityKey() == olmContent?.senderKey }
                                        ?.let {
                                            markOlmSessionForUnwedging(event.senderId ?: "", it)
                                        }
                                        ?: run {
                                            Timber.i("## CRYPTO | internalDecryptEvent() : Failed to find sender crypto device for unwedging")
                                        }
                            }
                        }
                    }
                    throw mxCryptoError
                }
            }
        }
    }

    // coroutineDispatchers.crypto scope
    private fun markOlmSessionForUnwedging(senderId: String, deviceInfo: CryptoDeviceInfo) {
        val deviceKey = deviceInfo.identityKey()

        val lastForcedDate = lastNewSessionForcedDates.getObject(senderId, deviceKey) ?: 0
        val now = System.currentTimeMillis()
        if (now - lastForcedDate < DefaultCryptoService.CRYPTO_MIN_FORCE_SESSION_PERIOD_MILLIS) {
            Timber.w("## CRYPTO | markOlmSessionForUnwedging: New session already forced with device at $lastForcedDate. Not forcing another")
            return
        }

        Timber.i("## CRYPTO | markOlmSessionForUnwedging from $senderId:${deviceInfo.deviceId}")
        lastNewSessionForcedDates.setObject(senderId, deviceKey, now)

        // offload this from crypto thread (?)
        cryptoCoroutineScope.launch(coroutineDispatchers.computation) {
            val ensured = ensureOlmSessionsForDevicesAction.handle(mapOf(senderId to listOf(deviceInfo)), force = true)

            Timber.i("## CRYPTO | markOlmSessionForUnwedging() : ensureOlmSessionsForDevicesAction isEmpty:${ensured.isEmpty}")

            // Now send a blank message on that session so the other side knows about it.
            // (The keyshare request is sent in the clear so that won't do)
            // We send this first such that, as long as the toDevice messages arrive in the
            // same order we sent them, the other end will get this first, set up the new session,
            // then get the keyshare request and send the key over this new session (because it
            // is the session it has most recently received a message on).
            val payloadJson = mapOf<String, Any>("type" to EventType.DUMMY)

            val encodedPayload = messageEncrypter.encryptMessage(payloadJson, listOf(deviceInfo))
            val sendToDeviceMap = MXUsersDevicesMap<Any>()
            sendToDeviceMap.setObject(senderId, deviceInfo.deviceId, encodedPayload)
            Timber.i("## CRYPTO | markOlmSessionForUnwedging() : sending dummy to $senderId:${deviceInfo.deviceId}")
            withContext(coroutineDispatchers.io) {
                val sendToDeviceParams = SendToDeviceTask.Params(EventType.ENCRYPTED, sendToDeviceMap)
                try {
                    sendToDeviceTask.execute(sendToDeviceParams)
                } catch (failure: Throwable) {
                    Timber.e(failure, "## CRYPTO | markOlmSessionForUnwedging() : failed to send dummy to $senderId:${deviceInfo.deviceId}")
                }
            }
        }
    }
}

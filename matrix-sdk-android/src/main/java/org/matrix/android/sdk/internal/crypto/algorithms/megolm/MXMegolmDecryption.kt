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

package org.matrix.android.sdk.internal.crypto.algorithms.megolm

import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.internal.crypto.DeviceListManager
import org.matrix.android.sdk.internal.crypto.IncomingRoomKeyRequest
import org.matrix.android.sdk.internal.crypto.MXEventDecryptionResult
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.NewSessionListener
import org.matrix.android.sdk.internal.crypto.OutgoingGossipingRequestManager
import org.matrix.android.sdk.internal.crypto.actions.EnsureOlmSessionsForDevicesAction
import org.matrix.android.sdk.internal.crypto.actions.MessageEncrypter
import org.matrix.android.sdk.internal.crypto.algorithms.IMXDecrypting
import org.matrix.android.sdk.internal.crypto.algorithms.IMXWithHeldExtension
import org.matrix.android.sdk.internal.crypto.keysbackup.DefaultKeysBackupService
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.event.EncryptedEventContent
import org.matrix.android.sdk.internal.crypto.model.event.RoomKeyContent
import org.matrix.android.sdk.internal.crypto.model.event.RoomKeyWithHeldContent
import org.matrix.android.sdk.internal.crypto.model.rest.ForwardedRoomKeyContent
import org.matrix.android.sdk.internal.crypto.model.rest.RoomKeyRequestBody
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

internal class MXMegolmDecryption(private val userId: String,
                                  private val olmDevice: MXOlmDevice,
                                  private val deviceListManager: DeviceListManager,
                                  private val outgoingGossipingRequestManager: OutgoingGossipingRequestManager,
                                  private val messageEncrypter: MessageEncrypter,
                                  private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction,
                                  private val cryptoStore: IMXCryptoStore,
                                  private val sendToDeviceTask: SendToDeviceTask,
                                  private val coroutineDispatchers: MatrixCoroutineDispatchers,
                                  private val cryptoCoroutineScope: CoroutineScope
) : IMXDecrypting, IMXWithHeldExtension {

    var newSessionListener: NewSessionListener? = null

    /**
     * Events which we couldn't decrypt due to unknown sessions / indexes: map from
     * senderKey|sessionId to timelines to list of MatrixEvents.
     */
//    private var pendingEvents: MutableMap<String /* senderKey|sessionId */, MutableMap<String /* timelineId */, MutableList<Event>>> = HashMap()

    @Throws(MXCryptoError::class)
    override fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult {
        // If cross signing is enabled, we don't send request until the keys are trusted
        // There could be a race effect here when xsigning is enabled, we should ensure that keys was downloaded once
        val requestOnFail = cryptoStore.getMyCrossSigningInfo()?.isTrusted() == true
        return decryptEvent(event, timeline, requestOnFail)
    }

    @Throws(MXCryptoError::class)
    private fun decryptEvent(event: Event, timeline: String, requestKeysOnFail: Boolean): MXEventDecryptionResult {
        Timber.v("## CRYPTO | decryptEvent ${event.eventId}, requestKeysOnFail:$requestKeysOnFail")
        if (event.roomId.isNullOrBlank()) {
            throw MXCryptoError.Base(MXCryptoError.ErrorType.MISSING_FIELDS, MXCryptoError.MISSING_FIELDS_REASON)
        }

        val encryptedEventContent = event.content.toModel<EncryptedEventContent>()
                ?: throw MXCryptoError.Base(MXCryptoError.ErrorType.MISSING_FIELDS, MXCryptoError.MISSING_FIELDS_REASON)

        if (encryptedEventContent.senderKey.isNullOrBlank()
                || encryptedEventContent.sessionId.isNullOrBlank()
                || encryptedEventContent.ciphertext.isNullOrBlank()) {
            throw MXCryptoError.Base(MXCryptoError.ErrorType.MISSING_FIELDS, MXCryptoError.MISSING_FIELDS_REASON)
        }

        return runCatching {
            olmDevice.decryptGroupMessage(encryptedEventContent.ciphertext,
                    event.roomId,
                    timeline,
                    encryptedEventContent.sessionId,
                    encryptedEventContent.senderKey)
        }
                .fold(
                        { olmDecryptionResult ->
                            // the decryption succeeds
                            if (olmDecryptionResult.payload != null) {
                                MXEventDecryptionResult(
                                        clearEvent = olmDecryptionResult.payload,
                                        senderCurve25519Key = olmDecryptionResult.senderKey,
                                        claimedEd25519Key = olmDecryptionResult.keysClaimed?.get("ed25519"),
                                        forwardingCurve25519KeyChain = olmDecryptionResult.forwardingCurve25519KeyChain
                                                .orEmpty()
                                )
                            } else {
                                throw MXCryptoError.Base(MXCryptoError.ErrorType.MISSING_FIELDS, MXCryptoError.MISSING_FIELDS_REASON)
                            }
                        },
                        { throwable ->
                            if (throwable is MXCryptoError.OlmError) {
                                // TODO Check the value of .message
                                if (throwable.olmException.message == "UNKNOWN_MESSAGE_INDEX") {
                                    // addEventToPendingList(event, timeline)
                                    // The session might has been partially withheld (and only pass ratcheted)
                                    val withHeldInfo = cryptoStore.getWithHeldMegolmSession(event.roomId, encryptedEventContent.sessionId)
                                    if (withHeldInfo != null) {
                                        if (requestKeysOnFail) {
                                            requestKeysForEvent(event, true)
                                        }
                                        // Encapsulate as withHeld exception
                                        throw MXCryptoError.Base(MXCryptoError.ErrorType.KEYS_WITHHELD,
                                                withHeldInfo.code?.value ?: "",
                                                withHeldInfo.reason)
                                    }

                                    if (requestKeysOnFail) {
                                        requestKeysForEvent(event, false)
                                    }
                                }

                                val reason = String.format(MXCryptoError.OLM_REASON, throwable.olmException.message)
                                val detailedReason = String.format(MXCryptoError.DETAILED_OLM_REASON, encryptedEventContent.ciphertext, reason)

                                throw MXCryptoError.Base(
                                        MXCryptoError.ErrorType.OLM,
                                        reason,
                                        detailedReason)
                            }
                            if (throwable is MXCryptoError.Base) {
                                if (
                                /** if the session is unknown*/
                                        throwable.errorType == MXCryptoError.ErrorType.UNKNOWN_INBOUND_SESSION_ID
                                ) {
                                    val withHeldInfo = cryptoStore.getWithHeldMegolmSession(event.roomId, encryptedEventContent.sessionId)
                                    if (withHeldInfo != null) {
                                        if (requestKeysOnFail) {
                                            requestKeysForEvent(event, true)
                                        }
                                        // Encapsulate as withHeld exception
                                        throw MXCryptoError.Base(MXCryptoError.ErrorType.KEYS_WITHHELD,
                                                withHeldInfo.code?.value ?: "",
                                                withHeldInfo.reason)
                                    } else {
                                        // This is un-used in riotX SDK, not sure if needed
                                        // addEventToPendingList(event, timeline)
                                        if (requestKeysOnFail) {
                                            requestKeysForEvent(event, false)
                                        }
                                    }
                                }
                            }
                            throw throwable
                        }
                )
    }

    /**
     * Helper for the real decryptEvent and for _retryDecryption. If
     * requestKeysOnFail is true, we'll send an m.room_key_request when we fail
     * to decrypt the event due to missing megolm keys.
     *
     * @param event the event
     */
    override fun requestKeysForEvent(event: Event, withHeld: Boolean) {
        val sender = event.senderId ?: return
        val encryptedEventContent = event.content.toModel<EncryptedEventContent>()
        val senderDevice = encryptedEventContent?.deviceId ?: return

        val recipients = if (event.senderId == userId || withHeld) {
            mapOf(
                    userId to listOf("*")
            )
        } else {
            // for the case where you share the key with a device that has a broken olm session
            // The other user might Re-shares a megolm session key with devices if the key has already been
            // sent to them.
            mapOf(
                    userId to listOf("*"),
                    sender to listOf(senderDevice)
            )
        }

        val requestBody = RoomKeyRequestBody(
                roomId = event.roomId,
                algorithm = encryptedEventContent.algorithm,
                senderKey = encryptedEventContent.senderKey,
                sessionId = encryptedEventContent.sessionId
        )

        outgoingGossipingRequestManager.sendRoomKeyRequest(requestBody, recipients)
    }

//    /**
//     * Add an event to the list of those we couldn't decrypt the first time we
//     * saw them.
//     *
//     * @param event      the event to try to decrypt later
//     * @param timelineId the timeline identifier
//     */
//    private fun addEventToPendingList(event: Event, timelineId: String) {
//        val encryptedEventContent = event.content.toModel<EncryptedEventContent>() ?: return
//        val pendingEventsKey = "${encryptedEventContent.senderKey}|${encryptedEventContent.sessionId}"
//
//        val timeline = pendingEvents.getOrPut(pendingEventsKey) { HashMap() }
//        val events = timeline.getOrPut(timelineId) { ArrayList() }
//
//        if (event !in events) {
//            Timber.v("## CRYPTO | addEventToPendingList() : add Event ${event.eventId} in room id ${event.roomId}")
//            events.add(event)
//        }
//    }

    /**
     * Handle a key event.
     *
     * @param event the key event.
     */
    override fun onRoomKeyEvent(event: Event, defaultKeysBackupService: DefaultKeysBackupService) {
        Timber.v("## CRYPTO | onRoomKeyEvent()")
        var exportFormat = false
        val roomKeyContent = event.getClearContent().toModel<RoomKeyContent>() ?: return

        var senderKey: String? = event.getSenderKey()
        var keysClaimed: MutableMap<String, String> = HashMap()
        val forwardingCurve25519KeyChain: MutableList<String> = ArrayList()

        if (roomKeyContent.roomId.isNullOrEmpty() || roomKeyContent.sessionId.isNullOrEmpty() || roomKeyContent.sessionKey.isNullOrEmpty()) {
            Timber.e("## CRYPTO | onRoomKeyEvent() :  Key event is missing fields")
            return
        }
        if (event.getClearType() == EventType.FORWARDED_ROOM_KEY) {
            Timber.i("## CRYPTO | onRoomKeyEvent(), forward adding key : ${roomKeyContent.roomId}|${roomKeyContent.sessionId}")
            val forwardedRoomKeyContent = event.getClearContent().toModel<ForwardedRoomKeyContent>()
                    ?: return

            forwardedRoomKeyContent.forwardingCurve25519KeyChain?.let {
                forwardingCurve25519KeyChain.addAll(it)
            }

            if (senderKey == null) {
                Timber.e("## CRYPTO | onRoomKeyEvent() : event is missing sender_key field")
                return
            }

            forwardingCurve25519KeyChain.add(senderKey)

            exportFormat = true
            senderKey = forwardedRoomKeyContent.senderKey
            if (null == senderKey) {
                Timber.e("## CRYPTO | onRoomKeyEvent() : forwarded_room_key event is missing sender_key field")
                return
            }

            if (null == forwardedRoomKeyContent.senderClaimedEd25519Key) {
                Timber.e("## CRYPTO | forwarded_room_key_event is missing sender_claimed_ed25519_key field")
                return
            }

            keysClaimed["ed25519"] = forwardedRoomKeyContent.senderClaimedEd25519Key
        } else {
            Timber.i("## CRYPTO | onRoomKeyEvent(), Adding key : ${roomKeyContent.roomId}|${roomKeyContent.sessionId}")
            if (null == senderKey) {
                Timber.e("## onRoomKeyEvent() : key event has no sender key (not encrypted?)")
                return
            }

            // inherit the claimed ed25519 key from the setup message
            keysClaimed = event.getKeysClaimed().toMutableMap()
        }

        Timber.i("## CRYPTO | onRoomKeyEvent addInboundGroupSession ${roomKeyContent.sessionId}")
        val added = olmDevice.addInboundGroupSession(roomKeyContent.sessionId,
                roomKeyContent.sessionKey,
                roomKeyContent.roomId,
                senderKey,
                forwardingCurve25519KeyChain,
                keysClaimed,
                exportFormat)

        if (added) {
            defaultKeysBackupService.maybeBackupKeys()

            val content = RoomKeyRequestBody(
                    algorithm = roomKeyContent.algorithm,
                    roomId = roomKeyContent.roomId,
                    sessionId = roomKeyContent.sessionId,
                    senderKey = senderKey
            )

            outgoingGossipingRequestManager.cancelRoomKeyRequest(content)

            onNewSession(senderKey, roomKeyContent.sessionId)
        }
    }

    /**
     * Check if the some messages can be decrypted with a new session
     *
     * @param senderKey the session sender key
     * @param sessionId the session id
     */
    override fun onNewSession(senderKey: String, sessionId: String) {
        Timber.v(" CRYPTO | ON NEW SESSION $sessionId - $senderKey")
        newSessionListener?.onNewSession(null, senderKey, sessionId)
    }

    override fun hasKeysForKeyRequest(request: IncomingRoomKeyRequest): Boolean {
        val roomId = request.requestBody?.roomId ?: return false
        val senderKey = request.requestBody.senderKey ?: return false
        val sessionId = request.requestBody.sessionId ?: return false
        return olmDevice.hasInboundSessionKeys(roomId, senderKey, sessionId)
    }

    override fun shareKeysWithDevice(request: IncomingRoomKeyRequest) {
        // sanity checks
        if (request.requestBody == null) {
            return
        }
        val userId = request.userId ?: return
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            runCatching { deviceListManager.downloadKeys(listOf(userId), false) }
                    .mapCatching {
                        val deviceId = request.deviceId
                        val deviceInfo = cryptoStore.getUserDevice(userId, deviceId ?: "")
                        if (deviceInfo == null) {
                            throw RuntimeException()
                        } else {
                            val devicesByUser = mapOf(userId to listOf(deviceInfo))
                            val usersDeviceMap = ensureOlmSessionsForDevicesAction.handle(devicesByUser)
                            val body = request.requestBody
                            val olmSessionResult = usersDeviceMap.getObject(userId, deviceId)
                            if (olmSessionResult?.sessionId == null) {
                                // no session with this device, probably because there
                                // were no one-time keys.
                                Timber.e("no session with this device $deviceId, probably because there were no one-time keys.")
                                return@mapCatching
                            }
                            Timber.i("## CRYPTO | shareKeysWithDevice() : sharing session ${body.sessionId} with device $userId:$deviceId")

                            val payloadJson = mutableMapOf<String, Any>("type" to EventType.FORWARDED_ROOM_KEY)
                            runCatching { olmDevice.getInboundGroupSession(body.sessionId, body.senderKey, body.roomId) }
                                    .fold(
                                            {
                                                // TODO
                                                payloadJson["content"] = it.exportKeys() ?: ""
                                            },
                                            {
                                                // TODO
                                                Timber.e(it, "## CRYPTO | shareKeysWithDevice: failed to get session for request $body")
                                            }

                                    )

                            val encodedPayload = messageEncrypter.encryptMessage(payloadJson, listOf(deviceInfo))
                            val sendToDeviceMap = MXUsersDevicesMap<Any>()
                            sendToDeviceMap.setObject(userId, deviceId, encodedPayload)
                            Timber.i("## CRYPTO | shareKeysWithDevice() : sending ${body.sessionId} to $userId:$deviceId")
                            val sendToDeviceParams = SendToDeviceTask.Params(EventType.ENCRYPTED, sendToDeviceMap)
                            try {
                                sendToDeviceTask.execute(sendToDeviceParams)
                            } catch (failure: Throwable) {
                                Timber.e(failure, "## CRYPTO | shareKeysWithDevice() : Failed to send ${body.sessionId} to $userId:$deviceId")
                            }
                        }
                    }
        }
    }

    override fun onRoomKeyWithHeldEvent(withHeldInfo: RoomKeyWithHeldContent) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            cryptoStore.addWithHeldMegolmSession(withHeldInfo)
        }
    }
}

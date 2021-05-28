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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.crypto.DeviceListManager
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.actions.EnsureOlmSessionsForDevicesAction
import org.matrix.android.sdk.internal.crypto.actions.MessageEncrypter
import org.matrix.android.sdk.internal.crypto.algorithms.IMXEncrypting
import org.matrix.android.sdk.internal.crypto.algorithms.IMXGroupEncryption
import org.matrix.android.sdk.internal.crypto.keysbackup.DefaultKeysBackupService
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.event.RoomKeyWithHeldContent
import org.matrix.android.sdk.internal.crypto.model.event.WithHeldCode
import org.matrix.android.sdk.internal.crypto.model.forEach
import org.matrix.android.sdk.internal.crypto.repository.WarnOnUnknownDeviceRepository
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.util.JsonCanonicalizer
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.convertToUTF8
import timber.log.Timber

internal class MXMegolmEncryption(
        // The id of the room we will be sending to.
        private val roomId: String,
        private val olmDevice: MXOlmDevice,
        private val defaultKeysBackupService: DefaultKeysBackupService,
        private val cryptoStore: IMXCryptoStore,
        private val deviceListManager: DeviceListManager,
        private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction,
        private val userId: String,
        private val deviceId: String,
        private val sendToDeviceTask: SendToDeviceTask,
        private val messageEncrypter: MessageEncrypter,
        private val warnOnUnknownDevicesRepository: WarnOnUnknownDeviceRepository,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val cryptoCoroutineScope: CoroutineScope
) : IMXEncrypting, IMXGroupEncryption {

    // OutboundSessionInfo. Null if we haven't yet started setting one up. Note
    // that even if this is non-null, it may not be ready for use (in which
    // case outboundSession.shareOperation will be non-null.)
    private var outboundSession: MXOutboundSessionInfo? = null

    init {
        // restore existing outbound session if any
        outboundSession = olmDevice.restoreOutboundGroupSessionForRoom(roomId)
    }

    // Default rotation periods
    // TODO: Make it configurable via parameters
    // Session rotation periods
    private var sessionRotationPeriodMsgs: Int = 100
    private var sessionRotationPeriodMs: Int = 7 * 24 * 3600 * 1000

    override suspend fun encryptEventContent(eventContent: Content,
                                             eventType: String,
                                             userIds: List<String>): Content {
        val ts = System.currentTimeMillis()
        Timber.v("## CRYPTO | encryptEventContent : getDevicesInRoom")
        val devices = getDevicesInRoom(userIds)
        Timber.v("## CRYPTO | encryptEventContent ${System.currentTimeMillis() - ts}: getDevicesInRoom ${devices.allowedDevices.map}")
        val outboundSession = ensureOutboundSession(devices.allowedDevices)

        return encryptContent(outboundSession, eventType, eventContent)
                .also {
                    notifyWithheldForSession(devices.withHeldDevices, outboundSession)
                    // annoyingly we have to serialize again the saved outbound session to store message index :/
                    // if not we would see duplicate message index errors
                    olmDevice.storeOutboundGroupSessionForRoom(roomId, outboundSession.sessionId)
                    Timber.v("## CRYPTO | encryptEventContent: Finished in ${System.currentTimeMillis() - ts} millis")
                }
    }

    private fun notifyWithheldForSession(devices: MXUsersDevicesMap<WithHeldCode>, outboundSession: MXOutboundSessionInfo) {
        // offload to computation thread
        cryptoCoroutineScope.launch(coroutineDispatchers.computation) {
            mutableListOf<Pair<UserDevice, WithHeldCode>>().apply {
                devices.forEach { userId, deviceId, withheldCode ->
                    this.add(UserDevice(userId, deviceId) to withheldCode)
                }
            }.groupBy(
                    { it.second },
                    { it.first }
            ).forEach { (code, targets) ->
                notifyKeyWithHeld(targets, outboundSession.sessionId, olmDevice.deviceCurve25519Key, code)
            }
        }
    }

    override fun discardSessionKey() {
        outboundSession = null
        olmDevice.discardOutboundGroupSessionForRoom(roomId)
    }

    override suspend fun preshareKey(userIds: List<String>) {
        val ts = System.currentTimeMillis()
        Timber.v("## CRYPTO | preshareKey : getDevicesInRoom")
        val devices = getDevicesInRoom(userIds)
        val outboundSession = ensureOutboundSession(devices.allowedDevices)

        notifyWithheldForSession(devices.withHeldDevices, outboundSession)

        Timber.v("## CRYPTO | preshareKey ${System.currentTimeMillis() - ts} millis")
    }

    /**
     * Prepare a new session.
     *
     * @return the session description
     */
    private fun prepareNewSessionInRoom(): MXOutboundSessionInfo {
        Timber.v("## CRYPTO | prepareNewSessionInRoom() ")
        val sessionId = olmDevice.createOutboundGroupSessionForRoom(roomId)

        val keysClaimedMap = HashMap<String, String>()
        keysClaimedMap["ed25519"] = olmDevice.deviceEd25519Key!!

        olmDevice.addInboundGroupSession(sessionId!!, olmDevice.getSessionKey(sessionId)!!, roomId, olmDevice.deviceCurve25519Key!!,
                emptyList(), keysClaimedMap, false)

        defaultKeysBackupService.maybeBackupKeys()

        return MXOutboundSessionInfo(sessionId, SharedWithHelper(roomId, sessionId, cryptoStore))
    }

    /**
     * Ensure the outbound session
     *
     * @param devicesInRoom the devices list
     */
    private suspend fun ensureOutboundSession(devicesInRoom: MXUsersDevicesMap<CryptoDeviceInfo>): MXOutboundSessionInfo {
        Timber.v("## CRYPTO | ensureOutboundSession start")
        var session = outboundSession
        if (session == null
                // Need to make a brand new session?
                || session.needsRotation(sessionRotationPeriodMsgs, sessionRotationPeriodMs)
                // Determine if we have shared with anyone we shouldn't have
                || session.sharedWithTooManyDevices(devicesInRoom)) {
            session = prepareNewSessionInRoom()
            outboundSession = session
        }
        val safeSession = session
        val shareMap = HashMap<String, MutableList<CryptoDeviceInfo>>()/* userId */
        val userIds = devicesInRoom.userIds
        for (userId in userIds) {
            val deviceIds = devicesInRoom.getUserDeviceIds(userId)
            for (deviceId in deviceIds!!) {
                val deviceInfo = devicesInRoom.getObject(userId, deviceId)
                if (deviceInfo != null && !cryptoStore.getSharedSessionInfo(roomId, safeSession.sessionId, userId, deviceId).found) {
                    val devices = shareMap.getOrPut(userId) { ArrayList() }
                    devices.add(deviceInfo)
                }
            }
        }
        shareKey(safeSession, shareMap)
        return safeSession
    }

    /**
     * Share the device key to a list of users
     *
     * @param session        the session info
     * @param devicesByUsers the devices map
     */
    private suspend fun shareKey(session: MXOutboundSessionInfo,
                                 devicesByUsers: Map<String, List<CryptoDeviceInfo>>) {
        // nothing to send, the task is done
        if (devicesByUsers.isEmpty()) {
            Timber.v("## CRYPTO | shareKey() : nothing more to do")
            return
        }
        // reduce the map size to avoid request timeout when there are too many devices (Users size  * devices per user)
        val subMap = HashMap<String, List<CryptoDeviceInfo>>()
        var devicesCount = 0
        for ((userId, devices) in devicesByUsers) {
            subMap[userId] = devices
            devicesCount += devices.size
            if (devicesCount > 100) {
                break
            }
        }
        Timber.v("## CRYPTO | shareKey() ; sessionId<${session.sessionId}> userId ${subMap.keys}")
        shareUserDevicesKey(session, subMap)
        val remainingDevices = devicesByUsers - subMap.keys
        shareKey(session, remainingDevices)
    }

    /**
     * Share the device keys of a an user
     *
     * @param session       the session info
     * @param devicesByUser the devices map
     */
    private suspend fun shareUserDevicesKey(session: MXOutboundSessionInfo,
                                            devicesByUser: Map<String, List<CryptoDeviceInfo>>) {
        val sessionKey = olmDevice.getSessionKey(session.sessionId)
        val chainIndex = olmDevice.getMessageIndex(session.sessionId)

        val submap = HashMap<String, Any>()
        submap["algorithm"] = MXCRYPTO_ALGORITHM_MEGOLM
        submap["room_id"] = roomId
        submap["session_id"] = session.sessionId
        submap["session_key"] = sessionKey!!
        submap["chain_index"] = chainIndex

        val payload = HashMap<String, Any>()
        payload["type"] = EventType.ROOM_KEY
        payload["content"] = submap

        var t0 = System.currentTimeMillis()
        Timber.v("## CRYPTO | shareUserDevicesKey() : starts")

        val results = ensureOlmSessionsForDevicesAction.handle(devicesByUser)
        Timber.v(
                """## CRYPTO | shareUserDevicesKey(): ensureOlmSessionsForDevices succeeds after ${System.currentTimeMillis() - t0} ms"""
                        .trimMargin()
        )
        val contentMap = MXUsersDevicesMap<Any>()
        var haveTargets = false
        val userIds = results.userIds
        val noOlmToNotify = mutableListOf<UserDevice>()
        for (userId in userIds) {
            val devicesToShareWith = devicesByUser[userId]
            for ((deviceID) in devicesToShareWith!!) {
                val sessionResult = results.getObject(userId, deviceID)
                if (sessionResult?.sessionId == null) {
                    // no session with this device, probably because there
                    // were no one-time keys.

                    // MSC 2399
                    // send withheld m.no_olm: an olm session could not be established.
                    // This may happen, for example, if the sender was unable to obtain a one-time key from the recipient.
                    noOlmToNotify.add(UserDevice(userId, deviceID))
                    continue
                }
                Timber.i("## CRYPTO | shareUserDevicesKey() : Add to share keys contentMap for $userId:$deviceID")
                contentMap.setObject(userId, deviceID, messageEncrypter.encryptMessage(payload, listOf(sessionResult.deviceInfo)))
                haveTargets = true
            }
        }

        // Add the devices we have shared with to session.sharedWithDevices.
        // we deliberately iterate over devicesByUser (ie, the devices we
        // attempted to share with) rather than the contentMap (those we did
        // share with), because we don't want to try to claim a one-time-key
        // for dead devices on every message.
        val gossipingEventBuffer = arrayListOf<Event>()
        for ((userId, devicesToShareWith) in devicesByUser) {
            for ((deviceId) in devicesToShareWith) {
                session.sharedWithHelper.markedSessionAsShared(userId, deviceId, chainIndex)
                gossipingEventBuffer.add(
                        Event(
                                type = EventType.ROOM_KEY,
                                senderId = this.userId,
                                content = submap.apply {
                                    this["session_key"] = ""
                                    // we add a fake key for trail
                                    this["_dest"] = "$userId|$deviceId"
                                }
                        ))
            }
        }

        cryptoStore.saveGossipingEvents(gossipingEventBuffer)

        if (haveTargets) {
            t0 = System.currentTimeMillis()
            Timber.i("## CRYPTO | shareUserDevicesKey() ${session.sessionId} : has target")
            val sendToDeviceParams = SendToDeviceTask.Params(EventType.ENCRYPTED, contentMap)
            try {
                sendToDeviceTask.execute(sendToDeviceParams)
                Timber.i("## CRYPTO | shareUserDevicesKey() : sendToDevice succeeds after ${System.currentTimeMillis() - t0} ms")
            } catch (failure: Throwable) {
                // What to do here...
                Timber.e("## CRYPTO | shareUserDevicesKey() : Failed to share session <${session.sessionId}> with $devicesByUser ")
            }
        } else {
            Timber.i("## CRYPTO | shareUserDevicesKey() : no need to sharekey")
        }

        if (noOlmToNotify.isNotEmpty()) {
            // XXX offload?, as they won't read the message anyhow?
            notifyKeyWithHeld(
                    noOlmToNotify,
                    session.sessionId,
                    olmDevice.deviceCurve25519Key,
                    WithHeldCode.NO_OLM
            )
        }
    }

    private suspend fun notifyKeyWithHeld(targets: List<UserDevice>,
                                          sessionId: String,
                                          senderKey: String?,
                                          code: WithHeldCode) {
        Timber.i("## CRYPTO | notifyKeyWithHeld() :sending withheld key for $targets session:$sessionId and code $code")
        val withHeldContent = RoomKeyWithHeldContent(
                roomId = roomId,
                senderKey = senderKey,
                algorithm = MXCRYPTO_ALGORITHM_MEGOLM,
                sessionId = sessionId,
                codeString = code.value
        )
        val params = SendToDeviceTask.Params(
                EventType.ROOM_KEY_WITHHELD,
                MXUsersDevicesMap<Any>().apply {
                    targets.forEach {
                        setObject(it.userId, it.deviceId, withHeldContent)
                    }
                }
        )
        try {
            sendToDeviceTask.execute(params)
        } catch (failure: Throwable) {
            Timber.e("## CRYPTO | notifyKeyWithHeld() : Failed to notify withheld key for $targets session: $sessionId ")
        }
    }

    /**
     * process the pending encryptions
     */
    private fun encryptContent(session: MXOutboundSessionInfo, eventType: String, eventContent: Content): Content {
        // Everything is in place, encrypt all pending events
        val payloadJson = HashMap<String, Any>()
        payloadJson["room_id"] = roomId
        payloadJson["type"] = eventType
        payloadJson["content"] = eventContent

        // Get canonical Json from

        val payloadString = convertToUTF8(JsonCanonicalizer.getCanonicalJson(Map::class.java, payloadJson))
        val ciphertext = olmDevice.encryptGroupMessage(session.sessionId, payloadString)

        val map = HashMap<String, Any>()
        map["algorithm"] = MXCRYPTO_ALGORITHM_MEGOLM
        map["sender_key"] = olmDevice.deviceCurve25519Key!!
        map["ciphertext"] = ciphertext!!
        map["session_id"] = session.sessionId

        // Include our device ID so that recipients can send us a
        // m.new_device message if they don't have our session key.
        map["device_id"] = deviceId
        session.useCount++
        return map
    }

    /**
     * Get the list of devices which can encrypt data to.
     * This method must be called in getDecryptingThreadHandler() thread.
     *
     * @param userIds  the user ids whose devices must be checked.
     */
    private suspend fun getDevicesInRoom(userIds: List<String>): DeviceInRoomInfo {
        // We are happy to use a cached version here: we assume that if we already
        // have a list of the user's devices, then we already share an e2e room
        // with them, which means that they will have announced any new devices via
        // an m.new_device.
        val keys = deviceListManager.downloadKeys(userIds, false)
        val encryptToVerifiedDevicesOnly = cryptoStore.getGlobalBlacklistUnverifiedDevices()
                || cryptoStore.getRoomsListBlacklistUnverifiedDevices().contains(roomId)

        val devicesInRoom = DeviceInRoomInfo()
        val unknownDevices = MXUsersDevicesMap<CryptoDeviceInfo>()

        for (userId in keys.userIds) {
            val deviceIds = keys.getUserDeviceIds(userId) ?: continue
            for (deviceId in deviceIds) {
                val deviceInfo = keys.getObject(userId, deviceId) ?: continue
                if (warnOnUnknownDevicesRepository.warnOnUnknownDevices() && deviceInfo.isUnknown) {
                    // The device is not yet known by the user
                    unknownDevices.setObject(userId, deviceId, deviceInfo)
                    continue
                }
                if (deviceInfo.isBlocked) {
                    // Remove any blocked devices
                    devicesInRoom.withHeldDevices.setObject(userId, deviceId, WithHeldCode.BLACKLISTED)
                    continue
                }

                if (!deviceInfo.isVerified && encryptToVerifiedDevicesOnly) {
                    devicesInRoom.withHeldDevices.setObject(userId, deviceId, WithHeldCode.UNVERIFIED)
                    continue
                }

                if (deviceInfo.identityKey() == olmDevice.deviceCurve25519Key) {
                    // Don't bother sending to ourself
                    continue
                }
                devicesInRoom.allowedDevices.setObject(userId, deviceId, deviceInfo)
            }
        }
        if (unknownDevices.isEmpty) {
            return devicesInRoom
        } else {
            throw MXCryptoError.UnknownDevice(unknownDevices)
        }
    }

    override suspend fun reshareKey(sessionId: String,
                                    userId: String,
                                    deviceId: String,
                                    senderKey: String): Boolean {
        Timber.i("## Crypto process reshareKey for $sessionId to $userId:$deviceId")
        val deviceInfo = cryptoStore.getUserDevice(userId, deviceId) ?: return false
                .also { Timber.w("## Crypto reshareKey: Device not found") }

        // Get the chain index of the key we previously sent this device
        val wasSessionSharedWithUser = cryptoStore.getSharedSessionInfo(roomId, sessionId, userId, deviceId)
        if (!wasSessionSharedWithUser.found) {
            // This session was never shared with this user
            // Send a room key with held
            notifyKeyWithHeld(listOf(UserDevice(userId, deviceId)), sessionId, senderKey, WithHeldCode.UNAUTHORISED)
            Timber.w("## Crypto reshareKey: ERROR : Never shared megolm with this device")
            return false
        }
        // if found chain index should not be null
        val chainIndex = wasSessionSharedWithUser.chainIndex ?: return false
                .also {
                    Timber.w("## Crypto reshareKey: Null chain index")
                }

        val devicesByUser = mapOf(userId to listOf(deviceInfo))
        val usersDeviceMap = ensureOlmSessionsForDevicesAction.handle(devicesByUser)
        val olmSessionResult = usersDeviceMap.getObject(userId, deviceId)
        olmSessionResult?.sessionId
                ?: // no session with this device, probably because there were no one-time keys.
                // ensureOlmSessionsForDevicesAction has already done the logging, so just skip it.
                return false.also {
                    Timber.w("## Crypto reshareKey: no session with this device, probably because there were no one-time keys")
                }

        Timber.i("[MXMegolmEncryption] reshareKey: sharing keys for session $senderKey|$sessionId:$chainIndex with device $userId:$deviceId")

        val payloadJson = mutableMapOf<String, Any>("type" to EventType.FORWARDED_ROOM_KEY)

        runCatching { olmDevice.getInboundGroupSession(sessionId, senderKey, roomId) }
                .fold(
                        {
                            // TODO
                            payloadJson["content"] = it.exportKeys(chainIndex.toLong()) ?: ""
                        },
                        {
                            // TODO
                            Timber.e(it, "[MXMegolmEncryption] reshareKey: failed to get session $sessionId|$senderKey|$roomId")
                        }

                )

        val encodedPayload = messageEncrypter.encryptMessage(payloadJson, listOf(deviceInfo))
        val sendToDeviceMap = MXUsersDevicesMap<Any>()
        sendToDeviceMap.setObject(userId, deviceId, encodedPayload)
        Timber.i("## CRYPTO | reshareKey() : sending session $sessionId to $userId:$deviceId")
        val sendToDeviceParams = SendToDeviceTask.Params(EventType.ENCRYPTED, sendToDeviceMap)
        return try {
            sendToDeviceTask.execute(sendToDeviceParams)
            Timber.i("## CRYPTO reshareKey() : successfully send <$sessionId> to $userId:$deviceId")
            true
        } catch (failure: Throwable) {
            Timber.e(failure, "## CRYPTO reshareKey() : fail to send <$sessionId> to $userId:$deviceId")
            false
        }
    }

    data class DeviceInRoomInfo(
            val allowedDevices: MXUsersDevicesMap<CryptoDeviceInfo> = MXUsersDevicesMap(),
            val withHeldDevices: MXUsersDevicesMap<WithHeldCode> = MXUsersDevicesMap()
    )

    data class UserDevice(
            val userId: String,
            val deviceId: String
    )
}

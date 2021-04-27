/*
 * Copyright (c) 2021 New Vector Ltd
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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.rest.UnsignedDeviceInfo
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.sync.model.DeviceListResponse
import org.matrix.android.sdk.internal.session.sync.model.DeviceOneTimeKeysCountSyncResponse
import org.matrix.android.sdk.internal.session.sync.model.ToDeviceSyncResponse
import timber.log.Timber
import uniffi.olm.CryptoStoreErrorException
import uniffi.olm.DecryptionErrorException
import uniffi.olm.Device
import uniffi.olm.DeviceLists
import uniffi.olm.KeyRequestPair
import uniffi.olm.Logger
import uniffi.olm.OlmMachine as InnerMachine
import uniffi.olm.ProgressListener as RustProgressListener
import uniffi.olm.Request
import uniffi.olm.RequestType
import uniffi.olm.setLogger

class CryptoLogger() : Logger {
    override fun log(logLine: String) {
        Timber.d(logLine)
    }
}

private class CryptoProgressListener(listener: ProgressListener?) : RustProgressListener {
    private val inner: ProgressListener? = listener

    override fun onProgress(progress: Int, total: Int) {
        if (this.inner != null) {
            this.inner.onProgress(progress, total)
        }
    }
}

internal class LiveDevice(
    userIds: List<String>,
    observer: DeviceUpdateObserver
) : MutableLiveData<List<CryptoDeviceInfo>>() {
    internal var userIds: List<String> = userIds
    private var observer: DeviceUpdateObserver = observer

    override fun onActive() {
        observer.addDeviceUpdateListener(this)
    }

    override fun onInactive() {
        observer.removeDeviceUpdateListener(this)
    }
}

fun setRustLogger() {
    setLogger(CryptoLogger() as Logger)
}

/**
 * Convert a Rust Device into a Kotlin CryptoDeviceInfo
 */
private fun toCryptoDeviceInfo(device: Device): CryptoDeviceInfo {
    val keys = device.keys.map { (keyId, key) ->
        "$keyId:$device.deviceId" to key
    }.toMap()

    return CryptoDeviceInfo(
            device.deviceId,
            device.userId,
            device.algorithms,
            keys,
            // TODO pass the signatures here, do we need this, why should the
            // Kotlin side care about signatures?
            mapOf(),
            UnsignedDeviceInfo(device.displayName),
            // TODO pass trust levels here
            DeviceTrustLevel(false, false),
            device.isBlocked,
            // TODO
            null
        )
}

internal class DeviceUpdateObserver() {
    internal val listeners = ConcurrentHashMap<LiveDevice, List<String>>()

    fun addDeviceUpdateListener(device: LiveDevice) {
        listeners.set(device, device.userIds)
    }

    fun removeDeviceUpdateListener(device: LiveDevice) {
        listeners.remove(device)
    }
}

internal class OlmMachine(user_id: String, device_id: String, path: File, deviceObserver: DeviceUpdateObserver) {
    private val inner: InnerMachine = InnerMachine(user_id, device_id, path.toString())
    private val deviceUpdateObserver = deviceObserver

    /**
     * Get our own user ID.
     */
    fun userId(): String {
        return this.inner.userId()
    }

    /**
     * Get our own device ID.
     */
    fun deviceId(): String {
        return this.inner.deviceId()
    }

    /**
     * Get our own public identity keys ID.
     */
    fun identityKeys(): Map<String, String> {
        return this.inner.identityKeys()
    }

    fun ownDevice(): CryptoDeviceInfo {
        val deviceId = this.deviceId()

        val keys = this.identityKeys().map { (keyId, key) ->
            "$keyId:$deviceId" to key
        }.toMap()

        return CryptoDeviceInfo(
            this.deviceId(),
            this.userId(),
            // TODO pass the algorithms here.
            listOf(),
            keys,
            mapOf(),
            UnsignedDeviceInfo(),
            DeviceTrustLevel(false, true),
            false,
            null
        )
    }

    /**
     * Get the list of outgoing requests that need to be sent to the homeserver.
     *
     * After the request was sent out and a successful response was received
     * the response body should be passed back to the state machine using the
     * markRequestAsSent() method.
     *
     * @return the list of requests that needs to be sent to the homeserver
     */
    suspend fun outgoingRequests(): List<Request> = withContext(Dispatchers.IO) {
        inner.outgoingRequests()
    }

    /**
     * Mark a request that was sent to the server as sent.
     *
     * @param requestId The unique ID of the request that was sent out. This needs to be an UUID.
     *
     * @param requestType The type of the request that was sent out.
     *
     * @param responseBody The body of the response that was received.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun markRequestAsSent(
        requestId: String,
        requestType: RequestType,
        responseBody: String
    ) = withContext(Dispatchers.IO) {
        inner.markRequestAsSent(requestId, requestType, responseBody)

        if (requestType == RequestType.KEYS_QUERY) {
            updateLiveDevices()
        }
    }

    /**
     * Let the state machine know about E2EE related sync changes that we
     * received from the server.
     *
     * This needs to be called after every sync, ideally before processing
     * any other sync changes.
     *
     * @param toDevice A serialized array of to-device events we received in the
     * current sync resposne.
     *
     * @param deviceChanges The list of devices that have changed in some way
     * since the previous sync.
     *
     * @param keyCounts The map of uploaded one-time key types and counts.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun receiveSyncChanges(
        toDevice: ToDeviceSyncResponse?,
        deviceChanges: DeviceListResponse?,
        keyCounts: DeviceOneTimeKeysCountSyncResponse?
    ): ToDeviceSyncResponse = withContext(Dispatchers.IO) {
            var counts: MutableMap<String, Int> = mutableMapOf()

            if (keyCounts?.signedCurve25519 != null) {
                counts.put("signed_curve25519", keyCounts.signedCurve25519)
            }

            val devices = DeviceLists(deviceChanges?.changed ?: listOf(), deviceChanges?.left ?: listOf())
            val adapter = MoshiProvider.providesMoshi().adapter<ToDeviceSyncResponse>(ToDeviceSyncResponse::class.java)
            val events = adapter.toJson(toDevice ?: ToDeviceSyncResponse())!!

            adapter.fromJson(inner.receiveSyncChanges(events, devices, counts))!!
    }

    /**
     * Mark the given list of users to be tracked, triggering a key query request
     * for them.
     *
     * *Note*: Only users that aren't already tracked will be considered for an
     * update. It's safe to call this with already tracked users, it won't
     * result in excessive keys query requests.
     *
     * @param users The users that should be queued up for a key query.
     */
    suspend fun updateTrackedUsers(users: List<String>) = withContext(Dispatchers.IO) {
        inner.updateTrackedUsers(users)
    }

    /**
     * Generate one-time key claiming requests for all the users we are missing
     * sessions for.
     *
     * After the request was sent out and a successful response was received
     * the response body should be passed back to the state machine using the
     * markRequestAsSent() method.
     *
     * This method should be called every time before a call to
     * shareRoomKey() is made.
     *
     * @param users The list of users for which we would like to establish 1:1
     * Olm sessions for.
     *
     * @return A keys claim request that needs to be sent out to the server.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun getMissingSessions(users: List<String>): Request? = withContext(Dispatchers.IO) {
        inner.getMissingSessions(users)
    }

    /**
     * Share a room key with the given list of users for the given room.
     *
     * After the request was sent out and a successful response was received
     * the response body should be passed back to the state machine using the
     * markRequestAsSent() method.
     *
     * This method should be called every time before a call to
     * `encrypt()` with the given `room_id` is made.
     *
     * @param roomId The unique id of the room, note that this doesn't strictly
     * need to be a Matrix room, it just needs to be an unique identifier for
     * the group that will participate in the conversation.
     *
     * @param users The list of users which are considered to be members of the
     * room and should receive the room key.
     *
     * @return The list of requests that need to be sent out.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun shareRoomKey(roomId: String, users: List<String>): List<Request> = withContext(Dispatchers.IO) {
        inner.shareRoomKey(roomId, users)
    }

    /**
     * Encrypt the given event with the given type and content for the given
     * room.
     *
     * **Note**: A room key needs to be shared with the group of users that are
     * members in the given room. If this is not done this method will panic.
     *
     * The usual flow to encrypt an evnet using this state machine is as
     * follows:
     *
     * 1. Get the one-time key claim request to establish 1:1 Olm sessions for
     *    the room members of the room we wish to participate in. This is done
     *    using the [`get_missing_sessions()`](#method.get_missing_sessions)
     *    method. This method call should be locked per call.
     *
     * 2. Share a room key with all the room members using the shareRoomKey().
     *    This method call should be locked per room.
     *
     * 3. Encrypt the event using this method.
     *
     * 4. Send the encrypted event to the server.
     *
     * After the room key is shared steps 1 and 2 will become noops, unless
     * there's some changes in the room membership or in the list of devices a
     * member has.
     *
     * @param roomId the ID of the room where the encrypted event will be sent to
     *
     * @param eventType the type of the event
     *
     * @param content the JSON content of the event
     *
     * @return The encrypted version of the content
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun encrypt(roomId: String, eventType: String, content: Content): Content = withContext(Dispatchers.IO) {
        val adapter = MoshiProvider.providesMoshi().adapter<Content>(Map::class.java)
        val contentString = adapter.toJson(content)
        val encrypted = inner.encrypt(roomId, eventType, contentString)
        adapter.fromJson(encrypted)!!
    }

    /**
     * Decrypt the given event that was sent in the given room.
     *
     * # Arguments
     *
     * @param event The serialized encrypted version of the event.
     *
     * @return the decrypted version of the event.
     */
    @Throws(MXCryptoError::class)
    suspend fun decryptRoomEvent(event: Event): MXEventDecryptionResult = withContext(Dispatchers.IO) {
        val adapter = MoshiProvider.providesMoshi().adapter<Event>(Event::class.java)
        val serializedEvent = adapter.toJson(event)

        try {
            val decrypted = inner.decryptRoomEvent(serializedEvent, event.roomId!!)

            val deserializationAdapter = MoshiProvider.providesMoshi().adapter<JsonDict>(Map::class.java)
            val clearEvent = deserializationAdapter.fromJson(decrypted.clearEvent)!!

            MXEventDecryptionResult(
                clearEvent,
                decrypted.senderCurve25519Key,
                decrypted.claimedEd25519Key,
                decrypted.forwardingCurve25519Chain
            )
        } catch (throwable: Throwable) {
            val reason = String.format(MXCryptoError.UNABLE_TO_DECRYPT_REASON, throwable.message, "m.megolm.v1.aes-sha2")
            throw MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_DECRYPT, reason)
        }
    }

    /**
     * Request the room key that was used to encrypt the given undecrypted
     * event.
     *
     * @param event The that we're not able to decrypt and want to request a
     * room key for.
     *
     * @return a key request pair, consisting of an optional key request
     * cancellation and the key request itself. The cancellation *must* be sent
     * out before the request, otherwise devices will ignore the key request.
     */
    @Throws(DecryptionErrorException::class)
    suspend fun requestRoomKey(event: Event): KeyRequestPair = withContext(Dispatchers.IO) {
        val adapter = MoshiProvider.providesMoshi().adapter<Event>(Event::class.java)
        val serializedEvent = adapter.toJson(event)

        inner.requestRoomKey(serializedEvent, event.roomId!!)
    }

    /**
     * Export all of our room keys.
     *
     * @param passphrase The passphrase that should be used to encrypt the key
     * export.
     *
     * @param rounds The number of rounds that should be used when expanding the
     * passphrase into an key.
     *
     * @return the encrypted key export as a bytearray.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun exportKeys(passphrase: String, rounds: Int): ByteArray = withContext(Dispatchers.IO) {
        inner.exportKeys(passphrase, rounds).toByteArray()
    }

    /**
     * Import room keys from the given serialized key export.
     *
     * @param keys The serialized version of the key export.
     *
     * @param passphrase The passphrase that was used to encrypt the key export.
     *
     * @param listener A callback that can be used to introspect the
     * progress of the key import.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun importKeys(keys: ByteArray, passphrase: String, listener: ProgressListener?): ImportRoomKeysResult = withContext(Dispatchers.IO) {
        val decodedKeys = String(keys, Charset.defaultCharset())

        var rustListener = CryptoProgressListener(listener)

        var result = inner.importKeys(decodedKeys, passphrase, rustListener)

        ImportRoomKeysResult(result.total, result.imported)
    }

    /**
     * Get a `Device` from the store.
     *
     * @param userId The id of the device owner.
     *
     * @param deviceId The id of the device itself.
     *
     * @return The Device if it found one.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun getDevice(userId: String, deviceId: String): CryptoDeviceInfo? = withContext(Dispatchers.IO) {
        // Our own device isn't part of our store on the rust side, return it
        // using our ownDevice method
        if (userId == userId() && deviceId == deviceId()) {
            ownDevice()
        } else {
            val device = inner.getDevice(userId, deviceId)
            if (device != null) toCryptoDeviceInfo(device) else null
        }
    }

    /**
     * Get all devices of an user.
     *
     * @param userId The id of the device owner.
     *
     * @return The list of Devices or an empty list if there aren't any.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun getUserDevices(userId: String): List<CryptoDeviceInfo> {
        val ownDevice = ownDevice()
        var devices = inner.getUserDevices(userId).map { toCryptoDeviceInfo(it) }.toMutableList()

        // EA doesn't differentiate much between our own and other devices of
        // while the rust-sdk does, append our own device here.
        devices.add(ownDevice)

        return devices
    }

    suspend fun getUserDevicesMap(userIds: List<String>): MXUsersDevicesMap<CryptoDeviceInfo> {
        val userMap = MXUsersDevicesMap<CryptoDeviceInfo>()

        for (user in userIds) {
            val devices = getUserDevices(user)

            for (device in devices) {
                userMap.setObject(user, device.deviceId, device)
            }
        }

        return userMap
    }

    /**
     * Get all the devices of multiple users.
     *
     * @param userId The ids of the device owners.
     *
     * @return The list of Devices or an empty list if there aren't any.
     */
    suspend fun getUserDevices(userIds: List<String>): List<CryptoDeviceInfo> {
        val plainDevices: ArrayList<CryptoDeviceInfo> = arrayListOf()

        for (user in userIds) {
            val devices = getUserDevices(user)
            plainDevices.addAll(devices)
        }

        return plainDevices
    }

    /**
     * Update all of our live device listeners.
     */
    private suspend fun updateLiveDevices() {
        for ((liveDevice, users) in deviceUpdateObserver.listeners) {
            val devices = getUserDevices(users)
            liveDevice.postValue(devices)
        }
    }

    /**
     * Get all the devices of multiple users as a live version.
     *
     * The live version will update the list of devices if some of the data
     * changes, or if new devices arrive for a certain user.
     *
     * @param userId The ids of the device owners.
     *
     * @return The list of Devices or an empty list if there aren't any.
     */
    suspend fun getLiveDevices(userIds: List<String>): LiveData<List<CryptoDeviceInfo>> {
        val plainDevices = getUserDevices(userIds)
        val devices = LiveDevice(userIds, deviceUpdateObserver)
        devices.setValue(plainDevices)

        return devices
    }

    /**
     * Discard the currently active room key for the given room if there is one.
     */
    @Throws(CryptoStoreErrorException::class)
    fun discardRoomKey(roomId: String) {
        runBlocking {
            inner.discardRoomKey(roomId)
        }
    }
}

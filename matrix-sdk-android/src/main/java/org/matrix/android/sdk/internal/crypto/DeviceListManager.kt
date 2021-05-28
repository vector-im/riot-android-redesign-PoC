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

import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.CryptoInfoMapper
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.DownloadKeysForUsersTask
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.logLimit
import timber.log.Timber
import javax.inject.Inject

// Legacy name: MXDeviceList
@SessionScope
internal class DeviceListManager @Inject constructor(private val cryptoStore: IMXCryptoStore,
                                                     private val olmDevice: MXOlmDevice,
                                                     private val syncTokenStore: SyncTokenStore,
                                                     private val credentials: Credentials,
                                                     private val downloadKeysForUsersTask: DownloadKeysForUsersTask,
                                                     private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
                                                     coroutineDispatchers: MatrixCoroutineDispatchers,
                                                     private val taskExecutor: TaskExecutor) {

    interface UserDevicesUpdateListener {
        fun onUsersDeviceUpdate(userIds: List<String>)
    }

    private val deviceChangeListeners = mutableListOf<UserDevicesUpdateListener>()

    fun addListener(listener: UserDevicesUpdateListener) {
        synchronized(deviceChangeListeners) {
            deviceChangeListeners.add(listener)
        }
    }

    fun removeListener(listener: UserDevicesUpdateListener) {
        synchronized(deviceChangeListeners) {
            deviceChangeListeners.remove(listener)
        }
    }

    private fun dispatchDeviceChange(users: List<String>) {
        synchronized(deviceChangeListeners) {
            deviceChangeListeners.forEach {
                try {
                    it.onUsersDeviceUpdate(users)
                } catch (failure: Throwable) {
                    Timber.e(failure, "Failed to dispatch device change")
                }
            }
        }
    }

    // HS not ready for retry
    private val notReadyToRetryHS = mutableSetOf<String>()

    private val cryptoCoroutineContext = coroutineDispatchers.crypto

    init {
        taskExecutor.executorScope.launch(cryptoCoroutineContext) {
            var isUpdated = false
            val deviceTrackingStatuses = cryptoStore.getDeviceTrackingStatuses().toMutableMap()
            for ((userId, status) in deviceTrackingStatuses) {
                if (TRACKING_STATUS_DOWNLOAD_IN_PROGRESS == status || TRACKING_STATUS_UNREACHABLE_SERVER == status) {
                    // if a download was in progress when we got shut down, it isn't any more.
                    deviceTrackingStatuses[userId] = TRACKING_STATUS_PENDING_DOWNLOAD
                    isUpdated = true
                }
            }
            if (isUpdated) {
                cryptoStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)
            }
        }
    }

    /**
     * Tells if the key downloads should be tried
     *
     * @param userId the userId
     * @return true if the keys download can be retrieved
     */
    private fun canRetryKeysDownload(userId: String): Boolean {
        var res = false

        if (':' in userId) {
            try {
                synchronized(notReadyToRetryHS) {
                    res = !notReadyToRetryHS.contains(userId.substringAfter(':'))
                }
            } catch (e: Exception) {
                Timber.e(e, "## CRYPTO | canRetryKeysDownload() failed")
            }
        }

        return res
    }

    /**
     * Clear the unavailable server lists
     */
    private fun clearUnavailableServersList() {
        synchronized(notReadyToRetryHS) {
            notReadyToRetryHS.clear()
        }
    }

    fun onRoomMembersLoadedFor(roomId: String) {
        taskExecutor.executorScope.launch(cryptoCoroutineContext) {
            if (cryptoSessionInfoProvider.isRoomEncrypted(roomId)) {
                // It's OK to track also device for invited users
                val userIds = cryptoSessionInfoProvider.getRoomUserIds(roomId, true)
                startTrackingDeviceList(userIds)
                refreshOutdatedDeviceLists()
            }
        }
    }

    /**
     * Mark the cached device list for the given user outdated
     * flag the given user for device-list tracking, if they are not already.
     *
     * @param userIds the user ids list
     */
    fun startTrackingDeviceList(userIds: List<String>) {
        var isUpdated = false
        val deviceTrackingStatuses = cryptoStore.getDeviceTrackingStatuses().toMutableMap()

        for (userId in userIds) {
            if (!deviceTrackingStatuses.containsKey(userId) || TRACKING_STATUS_NOT_TRACKED == deviceTrackingStatuses[userId]) {
                Timber.v("## CRYPTO | startTrackingDeviceList() : Now tracking device list for $userId")
                deviceTrackingStatuses[userId] = TRACKING_STATUS_PENDING_DOWNLOAD
                isUpdated = true
            }
        }

        if (isUpdated) {
            cryptoStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)
        }
    }

    /**
     * Update the devices list statuses
     *
     * @param changed the user ids list which have new devices
     * @param left    the user ids list which left a room
     */
    fun handleDeviceListsChanges(changed: Collection<String>, left: Collection<String>) {
        Timber.v("## CRYPTO: handleDeviceListsChanges changed: ${changed.logLimit()} / left: ${left.logLimit()}")
        var isUpdated = false
        val deviceTrackingStatuses = cryptoStore.getDeviceTrackingStatuses().toMutableMap()

        if (changed.isNotEmpty() || left.isNotEmpty()) {
            clearUnavailableServersList()
        }

        for (userId in changed) {
            if (deviceTrackingStatuses.containsKey(userId)) {
                Timber.v("## CRYPTO | handleDeviceListsChanges() : Marking device list outdated for $userId")
                deviceTrackingStatuses[userId] = TRACKING_STATUS_PENDING_DOWNLOAD
                isUpdated = true
            }
        }

        for (userId in left) {
            if (deviceTrackingStatuses.containsKey(userId)) {
                Timber.v("## CRYPTO | handleDeviceListsChanges() : No longer tracking device list for $userId")
                deviceTrackingStatuses[userId] = TRACKING_STATUS_NOT_TRACKED
                isUpdated = true
            }
        }

        if (isUpdated) {
            cryptoStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)
        }
    }

    /**
     * This will flag each user whose devices we are tracking as in need of an
     * + update
     */
    fun invalidateAllDeviceLists() {
        handleDeviceListsChanges(cryptoStore.getDeviceTrackingStatuses().keys, emptyList())
    }

    /**
     * The keys download failed
     *
     * @param userIds the user ids list
     */
    private fun onKeysDownloadFailed(userIds: List<String>) {
        val deviceTrackingStatuses = cryptoStore.getDeviceTrackingStatuses().toMutableMap()
        userIds.associateWithTo(deviceTrackingStatuses) { TRACKING_STATUS_PENDING_DOWNLOAD }
        cryptoStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)
    }

    /**
     * The keys download succeeded.
     *
     * @param userIds  the userIds list
     * @param failures the failure map.
     */
    private fun onKeysDownloadSucceed(userIds: List<String>, failures: Map<String, Map<String, Any>>?): MXUsersDevicesMap<CryptoDeviceInfo> {
        if (failures != null) {
            for ((k, value) in failures) {
                val statusCode = when (val status = value["status"]) {
                    is Double -> status.toInt()
                    is Int    -> status.toInt()
                    else      -> 0
                }
                if (statusCode == 503) {
                    synchronized(notReadyToRetryHS) {
                        notReadyToRetryHS.add(k)
                    }
                }
            }
        }
        val deviceTrackingStatuses = cryptoStore.getDeviceTrackingStatuses().toMutableMap()
        val usersDevicesInfoMap = MXUsersDevicesMap<CryptoDeviceInfo>()
        for (userId in userIds) {
            val devices = cryptoStore.getUserDevices(userId)
            if (null == devices) {
                if (canRetryKeysDownload(userId)) {
                    deviceTrackingStatuses[userId] = TRACKING_STATUS_PENDING_DOWNLOAD
                    Timber.e("failed to retry the devices of $userId : retry later")
                } else {
                    if (deviceTrackingStatuses.containsKey(userId) && TRACKING_STATUS_DOWNLOAD_IN_PROGRESS == deviceTrackingStatuses[userId]) {
                        deviceTrackingStatuses[userId] = TRACKING_STATUS_UNREACHABLE_SERVER
                        Timber.e("failed to retry the devices of $userId : the HS is not available")
                    }
                }
            } else {
                if (deviceTrackingStatuses.containsKey(userId) && TRACKING_STATUS_DOWNLOAD_IN_PROGRESS == deviceTrackingStatuses[userId]) {
                    // we didn't get any new invalidations since this download started:
                    //  this user's device list is now up to date.
                    deviceTrackingStatuses[userId] = TRACKING_STATUS_UP_TO_DATE
                    Timber.v("Device list for $userId now up to date")
                }
                // And the response result
                usersDevicesInfoMap.setObjects(userId, devices)
            }
        }
        cryptoStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)

        dispatchDeviceChange(userIds)
        return usersDevicesInfoMap
    }

    /**
     * Download the device keys for a list of users and stores the keys in the MXStore.
     * It must be called in getEncryptingThreadHandler() thread.
     *
     * @param userIds       The users to fetch.
     * @param forceDownload Always download the keys even if cached.
     */
    suspend fun downloadKeys(userIds: List<String>?, forceDownload: Boolean): MXUsersDevicesMap<CryptoDeviceInfo> {
        Timber.v("## CRYPTO | downloadKeys() : forceDownload $forceDownload : $userIds")
        // Map from userId -> deviceId -> MXDeviceInfo
        val stored = MXUsersDevicesMap<CryptoDeviceInfo>()

        // List of user ids we need to download keys for
        val downloadUsers = ArrayList<String>()
        if (null != userIds) {
            if (forceDownload) {
                downloadUsers.addAll(userIds)
            } else {
                for (userId in userIds) {
                    val status = cryptoStore.getDeviceTrackingStatus(userId, TRACKING_STATUS_NOT_TRACKED)
                    // downloading keys ->the keys download won't be triggered twice but the callback requires the dedicated keys
                    // not yet retrieved
                    if (TRACKING_STATUS_UP_TO_DATE != status && TRACKING_STATUS_UNREACHABLE_SERVER != status) {
                        downloadUsers.add(userId)
                    } else {
                        val devices = cryptoStore.getUserDevices(userId)
                        // should always be true
                        if (devices != null) {
                            stored.setObjects(userId, devices)
                        } else {
                            downloadUsers.add(userId)
                        }
                    }
                }
            }
        }
        return if (downloadUsers.isEmpty()) {
            Timber.v("## CRYPTO | downloadKeys() : no new user device")
            stored
        } else {
            Timber.v("## CRYPTO | downloadKeys() : starts")
            val t0 = System.currentTimeMillis()
            val result = doKeyDownloadForUsers(downloadUsers)
            Timber.v("## CRYPTO | downloadKeys() : doKeyDownloadForUsers succeeds after ${System.currentTimeMillis() - t0} ms")
            result.also {
                it.addEntriesFromMap(stored)
            }
        }
    }

    /**
     * Download the devices keys for a set of users.
     *
     * @param downloadUsers the user ids list
     */
    private suspend fun doKeyDownloadForUsers(downloadUsers: List<String>): MXUsersDevicesMap<CryptoDeviceInfo> {
        Timber.v("## CRYPTO | doKeyDownloadForUsers() : doKeyDownloadForUsers ${downloadUsers.logLimit()}")
        // get the user ids which did not already trigger a keys download
        val filteredUsers = downloadUsers.filter { MatrixPatterns.isUserId(it) }
        if (filteredUsers.isEmpty()) {
            // trigger nothing
            return MXUsersDevicesMap()
        }
        val params = DownloadKeysForUsersTask.Params(filteredUsers, syncTokenStore.getLastToken())
        val response = try {
            downloadKeysForUsersTask.execute(params)
        } catch (throwable: Throwable) {
            Timber.e(throwable, "## CRYPTO | doKeyDownloadForUsers(): error")
            onKeysDownloadFailed(filteredUsers)
            throw throwable
        }
        Timber.v("## CRYPTO | doKeyDownloadForUsers() : Got keys for " + filteredUsers.size + " users")
        for (userId in filteredUsers) {
            // al devices =
            val models = response.deviceKeys?.get(userId)?.mapValues { entry -> CryptoInfoMapper.map(entry.value) }

            Timber.v("## CRYPTO | doKeyDownloadForUsers() : Got keys for $userId : $models")
            if (!models.isNullOrEmpty()) {
                val workingCopy = models.toMutableMap()
                for ((deviceId, deviceInfo) in models) {
                    // Get the potential previously store device keys for this device
                    val previouslyStoredDeviceKeys = cryptoStore.getUserDevice(userId, deviceId)

                    // in some race conditions (like unit tests)
                    // the self device must be seen as verified
                    if (deviceInfo.deviceId == credentials.deviceId && userId == credentials.userId) {
                        deviceInfo.trustLevel = DeviceTrustLevel(previouslyStoredDeviceKeys?.trustLevel?.crossSigningVerified ?: false, true)
                    }
                    // Validate received keys
                    if (!validateDeviceKeys(deviceInfo, userId, deviceId, previouslyStoredDeviceKeys)) {
                        // New device keys are not valid. Do not store them
                        workingCopy.remove(deviceId)
                        if (null != previouslyStoredDeviceKeys) {
                            // But keep old validated ones if any
                            workingCopy[deviceId] = previouslyStoredDeviceKeys
                        }
                    } else if (null != previouslyStoredDeviceKeys) {
                        // The verified status is not sync'ed with hs.
                        // This is a client side information, valid only for this client.
                        // So, transfer its previous value
                        workingCopy[deviceId]!!.trustLevel = previouslyStoredDeviceKeys.trustLevel
                    }
                }
                // Update the store
                // Note that devices which aren't in the response will be removed from the stores
                cryptoStore.storeUserDevices(userId, workingCopy)
            }

            val masterKey = response.masterKeys?.get(userId)?.toCryptoModel().also {
                Timber.v("## CRYPTO | CrossSigning : Got keys for $userId : MSK ${it?.unpaddedBase64PublicKey}")
            }
            val selfSigningKey = response.selfSigningKeys?.get(userId)?.toCryptoModel()?.also {
                Timber.v("## CRYPTO | CrossSigning : Got keys for $userId : SSK ${it.unpaddedBase64PublicKey}")
            }
            val userSigningKey = response.userSigningKeys?.get(userId)?.toCryptoModel()?.also {
                Timber.v("## CRYPTO | CrossSigning : Got keys for $userId : USK ${it.unpaddedBase64PublicKey}")
            }
            cryptoStore.storeUserCrossSigningKeys(
                    userId,
                    masterKey,
                    selfSigningKey,
                    userSigningKey
            )
        }

        // Update devices trust for these users
        // dispatchDeviceChange(downloadUsers)

        return onKeysDownloadSucceed(filteredUsers, response.failures)
    }

    /**
     * Validate device keys.
     * This method must called on getEncryptingThreadHandler() thread.
     *
     * @param deviceKeys                 the device keys to validate.
     * @param userId                     the id of the user of the device.
     * @param deviceId                   the id of the device.
     * @param previouslyStoredDeviceKeys the device keys we received before for this device
     * @return true if succeeds
     */
    private fun validateDeviceKeys(deviceKeys: CryptoDeviceInfo?, userId: String, deviceId: String, previouslyStoredDeviceKeys: CryptoDeviceInfo?): Boolean {
        if (null == deviceKeys) {
            Timber.e("## CRYPTO | validateDeviceKeys() : deviceKeys is null from $userId:$deviceId")
            return false
        }

        if (null == deviceKeys.keys) {
            Timber.e("## CRYPTO | validateDeviceKeys() : deviceKeys.keys is null from $userId:$deviceId")
            return false
        }

        if (null == deviceKeys.signatures) {
            Timber.e("## CRYPTO | validateDeviceKeys() : deviceKeys.signatures is null from $userId:$deviceId")
            return false
        }

        // Check that the user_id and device_id in the received deviceKeys are correct
        if (deviceKeys.userId != userId) {
            Timber.e("## CRYPTO | validateDeviceKeys() : Mismatched user_id ${deviceKeys.userId} from $userId:$deviceId")
            return false
        }

        if (deviceKeys.deviceId != deviceId) {
            Timber.e("## CRYPTO | validateDeviceKeys() : Mismatched device_id ${deviceKeys.deviceId} from $userId:$deviceId")
            return false
        }

        val signKeyId = "ed25519:" + deviceKeys.deviceId
        val signKey = deviceKeys.keys[signKeyId]

        if (null == signKey) {
            Timber.e("## CRYPTO | validateDeviceKeys() : Device $userId:${deviceKeys.deviceId} has no ed25519 key")
            return false
        }

        val signatureMap = deviceKeys.signatures[userId]

        if (null == signatureMap) {
            Timber.e("## CRYPTO | validateDeviceKeys() : Device $userId:${deviceKeys.deviceId} has no map for $userId")
            return false
        }

        val signature = signatureMap[signKeyId]

        if (null == signature) {
            Timber.e("## CRYPTO | validateDeviceKeys() : Device $userId:${deviceKeys.deviceId} is not signed")
            return false
        }

        var isVerified = false
        var errorMessage: String? = null

        try {
            olmDevice.verifySignature(signKey, deviceKeys.signalableJSONDictionary(), signature)
            isVerified = true
        } catch (e: Exception) {
            errorMessage = e.message
        }

        if (!isVerified) {
            Timber.e("## CRYPTO | validateDeviceKeys() : Unable to verify signature on device " + userId + ":"
                    + deviceKeys.deviceId + " with error " + errorMessage)
            return false
        }

        if (null != previouslyStoredDeviceKeys) {
            if (previouslyStoredDeviceKeys.fingerprint() != signKey) {
                // This should only happen if the list has been MITMed; we are
                // best off sticking with the original keys.
                //
                // Should we warn the user about it somehow?
                Timber.e("## CRYPTO | validateDeviceKeys() : WARNING:Ed25519 key for device " + userId + ":"
                        + deviceKeys.deviceId + " has changed : "
                        + previouslyStoredDeviceKeys.fingerprint() + " -> " + signKey)

                Timber.e("## CRYPTO | validateDeviceKeys() : $previouslyStoredDeviceKeys -> $deviceKeys")
                Timber.e("## CRYPTO | validateDeviceKeys() : ${previouslyStoredDeviceKeys.keys} -> ${deviceKeys.keys}")

                return false
            }
        }

        return true
    }

    /**
     * Start device queries for any users who sent us an m.new_device recently
     * This method must be called on getEncryptingThreadHandler() thread.
     */
    suspend fun refreshOutdatedDeviceLists() {
        Timber.v("## CRYPTO | refreshOutdatedDeviceLists()")
        val deviceTrackingStatuses = cryptoStore.getDeviceTrackingStatuses().toMutableMap()

        val users = deviceTrackingStatuses.keys.filterTo(mutableListOf()) { userId ->
            TRACKING_STATUS_PENDING_DOWNLOAD == deviceTrackingStatuses[userId]
        }

        if (users.isEmpty()) {
            return
        }

        // update the statuses
        users.associateWithTo(deviceTrackingStatuses) { TRACKING_STATUS_DOWNLOAD_IN_PROGRESS }

        cryptoStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)
        runCatching {
            doKeyDownloadForUsers(users)
        }.fold(
                {
                    Timber.v("## CRYPTO | refreshOutdatedDeviceLists() : done")
                },
                {
                    Timber.e(it, "## CRYPTO | refreshOutdatedDeviceLists() : ERROR updating device keys for users $users")
                }
        )
    }

    companion object {

        /**
         * State transition diagram for DeviceList.deviceTrackingStatus
         * <pre>
         *
         *                                   |
         *        stopTrackingDeviceList     V
         *      +---------------------> NOT_TRACKED
         *      |                            |
         *      +<--------------------+      | startTrackingDeviceList
         *      |                     |      V
         *      |   +-------------> PENDING_DOWNLOAD <--------------------+-+
         *      |   |                      ^ |                            | |
         *      |   | restart     download | |  start download            | | invalidateUserDeviceList
         *      |   | client        failed | |                            | |
         *      |   |                      | V                            | |
         *      |   +------------ DOWNLOAD_IN_PROGRESS -------------------+ |
         *      |                    |       |                              |
         *      +<-------------------+       |  download successful         |
         *      ^                            V                              |
         *      +----------------------- UP_TO_DATE ------------------------+
         *
         * </pre>
         */

        const val TRACKING_STATUS_NOT_TRACKED = -1
        const val TRACKING_STATUS_PENDING_DOWNLOAD = 1
        const val TRACKING_STATUS_DOWNLOAD_IN_PROGRESS = 2
        const val TRACKING_STATUS_UP_TO_DATE = 3
        const val TRACKING_STATUS_UNREACHABLE_SERVER = 4
    }
}

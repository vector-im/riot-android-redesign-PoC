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

package org.matrix.android.sdk.internal.crypto.actions

import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.MXKey
import org.matrix.android.sdk.internal.crypto.model.MXOlmSessionResult
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.tasks.ClaimOneTimeKeysForUsersDeviceTask
import timber.log.Timber
import javax.inject.Inject

internal class EnsureOlmSessionsForDevicesAction @Inject constructor(
        private val olmDevice: MXOlmDevice,
        private val oneTimeKeysForUsersDeviceTask: ClaimOneTimeKeysForUsersDeviceTask) {

    suspend fun handle(devicesByUser: Map<String, List<CryptoDeviceInfo>>, force: Boolean = false): MXUsersDevicesMap<MXOlmSessionResult> {
        val devicesWithoutSession = ArrayList<CryptoDeviceInfo>()

        val results = MXUsersDevicesMap<MXOlmSessionResult>()

        for ((userId, deviceInfos) in devicesByUser) {
            for (deviceInfo in deviceInfos) {
                val deviceId = deviceInfo.deviceId
                val key = deviceInfo.identityKey()

                val sessionId = olmDevice.getSessionId(key!!)

                if (sessionId.isNullOrEmpty() || force) {
                    devicesWithoutSession.add(deviceInfo)
                }

                val olmSessionResult = MXOlmSessionResult(deviceInfo, sessionId)
                results.setObject(userId, deviceId, olmSessionResult)
            }
        }

        //if (devicesWithoutSession.size == 0) {
        //    return results
        //}

        //// Prepare the request for claiming one-time keys
        //val usersDevicesToClaim = MXUsersDevicesMap<String>()

        //val oneTimeKeyAlgorithm = MXKey.KEY_SIGNED_CURVE_25519_TYPE

        //for (device in devicesWithoutSession) {
        //    usersDevicesToClaim.setObject(device.userId, device.deviceId, oneTimeKeyAlgorithm)
        //}

        //// TODO: this has a race condition - if we try to send another message
        //// while we are claiming a key, we will end up claiming two and setting up
        //// two sessions.
        ////
        //// That should eventually resolve itself, but it's poor form.

        //Timber.i("## CRYPTO | claimOneTimeKeysForUsersDevices() : $usersDevicesToClaim")

        //val claimParams = ClaimOneTimeKeysForUsersDeviceTask.Params(usersDevicesToClaim)
        //val oneTimeKeys = oneTimeKeysForUsersDeviceTask.execute(claimParams)
        //Timber.v("## CRYPTO | claimOneTimeKeysForUsersDevices() : keysClaimResponse.oneTimeKeys: $oneTimeKeys")
        //for ((userId, deviceInfos) in devicesByUser) {
        //    for (deviceInfo in deviceInfos) {
        //        var oneTimeKey: MXKey? = null
        //        val deviceIds = oneTimeKeys.getUserDeviceIds(userId)
        //        if (null != deviceIds) {
        //            for (deviceId in deviceIds) {
        //                val olmSessionResult = results.getObject(userId, deviceId)
        //                if (olmSessionResult!!.sessionId != null && !force) {
        //                    // We already have a result for this device
        //                    continue
        //                }
        //                val key = oneTimeKeys.getObject(userId, deviceId)
        //                if (key?.type == oneTimeKeyAlgorithm) {
        //                    oneTimeKey = key
        //                }
        //                if (oneTimeKey == null) {
        //                    Timber.w("## CRYPTO | ensureOlmSessionsForDevices() : No one-time keys " + oneTimeKeyAlgorithm
        //                            + " for device " + userId + " : " + deviceId)
        //                    continue
        //                }
        //                // Update the result for this device in results
        //                olmSessionResult.sessionId = verifyKeyAndStartSession(oneTimeKey, userId, deviceInfo)
        //            }
        //        }
        //    }
        //}
        return results
    }

    private fun verifyKeyAndStartSession(oneTimeKey: MXKey, userId: String, deviceInfo: CryptoDeviceInfo): String? {
        var sessionId: String? = null

        val deviceId = deviceInfo.deviceId
        val signKeyId = "ed25519:$deviceId"
        val signature = oneTimeKey.signatureForUserId(userId, signKeyId)

        if (!signature.isNullOrEmpty() && !deviceInfo.fingerprint().isNullOrEmpty()) {
            var isVerified = false
            var errorMessage: String? = null

            try {
                olmDevice.verifySignature(deviceInfo.fingerprint()!!, oneTimeKey.signalableJSONDictionary(), signature)
                isVerified = true
            } catch (e: Exception) {
                errorMessage = e.message
            }

            // Check one-time key signature
            if (isVerified) {
                sessionId = olmDevice.createOutboundSession(deviceInfo.identityKey()!!, oneTimeKey.value)

                if (!sessionId.isNullOrEmpty()) {
                    Timber.v("## CRYPTO | verifyKeyAndStartSession() : Started new sessionid " + sessionId
                            + " for device " + deviceInfo + "(theirOneTimeKey: " + oneTimeKey.value + ")")
                } else {
                    // Possibly a bad key
                    Timber.e("## CRYPTO | verifyKeyAndStartSession() : Error starting session with device $userId:$deviceId")
                }
            } else {
                Timber.e("## CRYPTO | verifyKeyAndStartSession() : Unable to verify signature on one-time key for device " + userId
                        + ":" + deviceId + " Error " + errorMessage)
            }
        }

        return sessionId
    }
}

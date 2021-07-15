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

import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.keysbackup.KeysBackupManager
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.di.UserId
import timber.log.Timber
import javax.inject.Inject

internal class SetDeviceVerificationAction @Inject constructor(
        private val cryptoStore: IMXCryptoStore,
        @UserId private val userId: String,
        private val keysBackupManager: KeysBackupManager
) {

    fun handle(trustLevel: DeviceTrustLevel, userId: String, deviceId: String) {
        val device = cryptoStore.getUserDevice(userId, deviceId)

        // Sanity check
        if (null == device) {
            Timber.w("## setDeviceVerification() : Unknown device $userId:$deviceId")
            return
        }

        if (device.isVerified != trustLevel.isVerified()) {
            if (userId == this.userId) {
                // If one of the user's own devices is being marked as verified / unverified,
                // check the key backup status, since whether or not we use this depends on
                // whether it has a signature from a verified device
                keysBackupManager.checkAndStartKeysBackup()
            }
        }

        if (device.trustLevel != trustLevel) {
            device.trustLevel = trustLevel
            cryptoStore.setDeviceTrust(userId, deviceId, trustLevel.crossSigningVerified, trustLevel.locallyVerified)
        }
    }
}

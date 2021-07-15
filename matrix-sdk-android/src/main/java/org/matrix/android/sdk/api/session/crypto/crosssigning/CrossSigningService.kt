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

package org.matrix.android.sdk.api.session.crypto.crosssigning

import androidx.lifecycle.LiveData
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustResult
import org.matrix.android.sdk.internal.crypto.crosssigning.UserTrustResult
import org.matrix.android.sdk.internal.crypto.store.PrivateKeysInfo

interface CrossSigningService {

    fun isCrossSigningVerified(): Boolean

    fun isUserTrusted(otherUserId: String): Boolean

    /**
     * Will not force a download of the key, but will verify signatures trust chain.
     * Checks that my trusted user key has signed the other user UserKey
     */
    fun checkUserTrust(otherUserId: String): UserTrustResult

    /**
     * Initialize cross signing for this user.
     * Users needs to enter credentials
     */
    fun initializeCrossSigning(uiaInterceptor: UserInteractiveAuthInterceptor?,
                               callback: MatrixCallback<Unit>)

    fun isCrossSigningInitialized(): Boolean = getMyCrossSigningKeys() != null

    fun checkTrustFromPrivateKeys(masterKeyPrivateKey: String?,
                                  uskKeyPrivateKey: String?,
                                  sskPrivateKey: String?): UserTrustResult

    fun getUserCrossSigningKeys(otherUserId: String): MXCrossSigningInfo?

    fun getLiveCrossSigningKeys(userId: String): LiveData<Optional<MXCrossSigningInfo>>

    fun getMyCrossSigningKeys(): MXCrossSigningInfo?

    fun getCrossSigningPrivateKeys(): PrivateKeysInfo?

    fun getLiveCrossSigningPrivateKeys(): LiveData<Optional<PrivateKeysInfo>>

    fun canCrossSign(): Boolean

    fun allPrivateKeysKnown(): Boolean

    fun trustUser(otherUserId: String,
                  callback: MatrixCallback<Unit>)

    fun markMyMasterKeyAsTrusted()

    /**
     * Sign one of your devices and upload the signature
     */
    fun trustDevice(deviceId: String,
                    callback: MatrixCallback<Unit>)

    fun checkDeviceTrust(otherUserId: String,
                         otherDeviceId: String,
                         locallyTrusted: Boolean?): DeviceTrustResult
}

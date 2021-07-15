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

import androidx.annotation.VisibleForTesting
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupService
import org.matrix.android.sdk.api.session.crypto.verification.VerificationService
import javax.inject.Inject

/**
 * A `CryptoService` class instance manages the end-to-end crypto for a session.
 *
 * Messages posted by the user are automatically redirected to CryptoService in order to be encrypted
 * before sending.
 * In the other hand, received events goes through CryptoService for decrypting.
 * CryptoService maintains all necessary keys and their sharing with other devices required for the crypto.
 * Specially, it tracks all room membership changes events in order to do keys updates.
 *
 * Implementation is basically a wrapper for [CryptoManager]
 */
internal class DefaultCryptoService @Inject constructor(
        private val cryptoManager: CryptoManager,
        private val keysBackupService: KeysBackupService,
        private val verificationService: VerificationService,
        private val crossSigningService: CrossSigningService,
) : CryptoService by cryptoManager {

    override fun keysBackupService() = keysBackupService

    override fun verificationService() = verificationService

    override fun crossSigningService() = crossSigningService

    /* ==========================================================================================
     * For test only
     * ========================================================================================== */

    @VisibleForTesting
    val cryptoStoreForTesting = cryptoManager.cryptoStoreForTesting
}

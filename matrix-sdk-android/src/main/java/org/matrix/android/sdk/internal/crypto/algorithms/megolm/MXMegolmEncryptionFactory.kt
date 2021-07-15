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
import org.matrix.android.sdk.internal.crypto.DeviceListManager
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.actions.EnsureOlmSessionsForDevicesAction
import org.matrix.android.sdk.internal.crypto.actions.MessageEncrypter
import org.matrix.android.sdk.internal.crypto.keysbackup.KeysBackupManager
import org.matrix.android.sdk.internal.crypto.repository.WarnOnUnknownDeviceRepository
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import javax.inject.Inject

internal class MXMegolmEncryptionFactory @Inject constructor(
        private val olmDevice: MXOlmDevice,
        private val keysBackupManager: KeysBackupManager,
        private val cryptoStore: IMXCryptoStore,
        private val deviceListManager: DeviceListManager,
        private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction,
        @UserId private val userId: String,
        @DeviceId private val deviceId: String?,
        private val sendToDeviceTask: SendToDeviceTask,
        private val messageEncrypter: MessageEncrypter,
        private val warnOnUnknownDevicesRepository: WarnOnUnknownDeviceRepository,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val cryptoCoroutineScope: CoroutineScope) {

    fun create(roomId: String): MXMegolmEncryption {
        return MXMegolmEncryption(
                roomId = roomId,
                olmDevice = olmDevice,
                keysBackupManager = keysBackupManager,
                cryptoStore = cryptoStore,
                deviceListManager = deviceListManager,
                ensureOlmSessionsForDevicesAction = ensureOlmSessionsForDevicesAction,
                userId = userId,
                deviceId = deviceId!!,
                sendToDeviceTask = sendToDeviceTask,
                messageEncrypter = messageEncrypter,
                warnOnUnknownDevicesRepository = warnOnUnknownDevicesRepository,
                coroutineDispatchers = coroutineDispatchers,
                cryptoCoroutineScope = cryptoCoroutineScope
        )
    }
}

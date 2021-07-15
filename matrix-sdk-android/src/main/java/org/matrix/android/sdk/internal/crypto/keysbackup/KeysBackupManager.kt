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

package org.matrix.android.sdk.internal.crypto.keysbackup

import android.os.Handler
import android.os.Looper
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.listeners.StepProgressListener
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupService
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupStateListener
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.MegolmSessionData
import org.matrix.android.sdk.internal.crypto.ObjectSigner
import org.matrix.android.sdk.internal.crypto.actions.MegolmSessionDataImporter
import org.matrix.android.sdk.internal.crypto.crosssigning.fromBase64
import org.matrix.android.sdk.internal.crypto.keysbackup.model.KeysBackupVersionTrust
import org.matrix.android.sdk.internal.crypto.keysbackup.model.KeysBackupVersionTrustSignature
import org.matrix.android.sdk.internal.crypto.keysbackup.model.MegolmBackupAuthData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.MegolmBackupCreationInfo
import org.matrix.android.sdk.internal.crypto.keysbackup.model.SignalableMegolmBackupAuthData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.BackupKeysResult
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.CreateKeysBackupVersionBody
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.KeyBackupData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.KeysBackupData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.KeysVersion
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.KeysVersionResult
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.RoomKeysBackupData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.UpdateKeysBackupVersionBody
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.CreateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteBackupTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetKeysBackupLastVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.UpdateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.util.computeRecoveryKey
import org.matrix.android.sdk.internal.crypto.keysbackup.util.extractCurveKeyFromRecoveryKey
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.model.OlmInboundGroupSessionWrapper2
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.SavedKeyBackupKeyInfo
import org.matrix.android.sdk.internal.crypto.store.db.model.KeysBackupDataEntity
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.extensions.foldToCallback
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.TaskThread
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.internal.util.JsonCanonicalizer
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.awaitCallback
import org.matrix.olm.OlmException
import org.matrix.olm.OlmPkDecryption
import org.matrix.olm.OlmPkEncryption
import org.matrix.olm.OlmPkMessage
import timber.log.Timber
import java.security.InvalidParameterException
import javax.inject.Inject
import kotlin.random.Random

/**
 *
 */
@SessionScope
internal class KeysBackupManager @Inject constructor(
        @UserId private val userId: String,
        private val credentials: Credentials,
        private val cryptoStore: IMXCryptoStore,
        private val olmDevice: MXOlmDevice,
        private val objectSigner: ObjectSigner,
        // Actions
        private val megolmSessionDataImporter: MegolmSessionDataImporter,
        // Tasks
        private val createKeysBackupVersionTask: CreateKeysBackupVersionTask,
        private val deleteBackupTask: DeleteBackupTask,
        private val deleteRoomSessionDataTask: DeleteRoomSessionDataTask,
        private val deleteRoomSessionsDataTask: DeleteRoomSessionsDataTask,
        private val deleteSessionDataTask: DeleteSessionsDataTask,
        private val getKeysBackupLastVersionTask: GetKeysBackupLastVersionTask,
        private val getKeysBackupVersionTask: GetKeysBackupVersionTask,
        private val getRoomSessionDataTask: GetRoomSessionDataTask,
        private val getRoomSessionsDataTask: GetRoomSessionsDataTask,
        private val getSessionsDataTask: GetSessionsDataTask,
        private val storeRoomSessionDataTask: StoreRoomSessionDataTask,
        private val storeSessionsDataTask: StoreRoomSessionsDataTask,
        private val storeSessionDataTask: StoreSessionsDataTask,
        private val updateKeysBackupVersionTask: UpdateKeysBackupVersionTask,
        // Task executor
        private val taskExecutor: TaskExecutor,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val cryptoCoroutineScope: CoroutineScope
) : KeysBackupService,
        KeysBackupManagerInput {

    private val uiHandler = Handler(Looper.getMainLooper())

    private val keysBackupStateManager = KeysBackupStateManager(uiHandler)

    // The backup version
    override var keysBackupVersion: KeysVersionResult? = null
        private set

    // The backup key being used.
    private var backupOlmPkEncryption: OlmPkEncryption? = null

    private var backupAllGroupSessionsCallback: MatrixCallback<Unit>? = null

    private var keysBackupStateListener: KeysBackupStateListener? = null

    override val isEnabled: Boolean
        get() = keysBackupStateManager.isEnabled

    override val isStucked: Boolean
        get() = keysBackupStateManager.isStucked

    override val state: KeysBackupState
        get() = keysBackupStateManager.state

    override val currentBackupVersion: String?
        get() = keysBackupVersion?.version

    override fun addListener(listener: KeysBackupStateListener) {
        keysBackupStateManager.addListener(listener)
    }

    override fun removeListener(listener: KeysBackupStateListener) {
        keysBackupStateManager.removeListener(listener)
    }

    override fun prepareKeysBackupVersion(password: String?,
                                          progressListener: ProgressListener?,
                                          callback: MatrixCallback<MegolmBackupCreationInfo>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            runCatching {
                withContext(coroutineDispatchers.crypto) {
                    val olmPkDecryption = OlmPkDecryption()
                    val signalableMegolmBackupAuthData = if (password != null) {
                        // Generate a private key from the password
                        val backgroundProgressListener = if (progressListener == null) {
                            null
                        } else {
                            object : ProgressListener {
                                override fun onProgress(progress: Int, total: Int) {
                                    uiHandler.post {
                                        try {
                                            progressListener.onProgress(progress, total)
                                        } catch (e: Exception) {
                                            Timber.e(e, "prepareKeysBackupVersion: onProgress failure")
                                        }
                                    }
                                }
                            }
                        }

                        val generatePrivateKeyResult = generatePrivateKeyWithPassword(password, backgroundProgressListener)
                        SignalableMegolmBackupAuthData(
                                publicKey = olmPkDecryption.setPrivateKey(generatePrivateKeyResult.privateKey),
                                privateKeySalt = generatePrivateKeyResult.salt,
                                privateKeyIterations = generatePrivateKeyResult.iterations
                        )
                    } else {
                        val publicKey = olmPkDecryption.generateKey()

                        SignalableMegolmBackupAuthData(
                                publicKey = publicKey
                        )
                    }

                    val canonicalJson = JsonCanonicalizer.getCanonicalJson(Map::class.java, signalableMegolmBackupAuthData.signalableJSONDictionary())

                    val signedMegolmBackupAuthData = MegolmBackupAuthData(
                            publicKey = signalableMegolmBackupAuthData.publicKey,
                            privateKeySalt = signalableMegolmBackupAuthData.privateKeySalt,
                            privateKeyIterations = signalableMegolmBackupAuthData.privateKeyIterations,
                            signatures = objectSigner.signObject(canonicalJson)
                    )

                    MegolmBackupCreationInfo(
                            algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP,
                            authData = signedMegolmBackupAuthData,
                            recoveryKey = computeRecoveryKey(olmPkDecryption.privateKey())
                    )
                }
            }.foldToCallback(callback)
        }
    }

    override fun createKeysBackupVersion(keysBackupCreationInfo: MegolmBackupCreationInfo,
                                         callback: MatrixCallback<KeysVersion>) {
        @Suppress("UNCHECKED_CAST")
        val createKeysBackupVersionBody = CreateKeysBackupVersionBody(
                algorithm = keysBackupCreationInfo.algorithm,
                authData = keysBackupCreationInfo.authData.toJsonDict()
        )

        keysBackupStateManager.state = KeysBackupState.Enabling

        createKeysBackupVersionTask
                .configureWith(createKeysBackupVersionBody) {
                    this.callback = object : MatrixCallback<KeysVersion> {
                        override fun onSuccess(data: KeysVersion) {
                            // Reset backup markers.
                            cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
                                // move tx out of UI thread
                                cryptoStore.resetBackupMarkers()
                            }

                            val keyBackupVersion = KeysVersionResult(
                                    algorithm = createKeysBackupVersionBody.algorithm,
                                    authData = createKeysBackupVersionBody.authData,
                                    version = data.version,
                                    // We can consider that the server does not have keys yet
                                    count = 0,
                                    hash = ""
                            )

                            enableKeysBackup(keyBackupVersion)

                            callback.onSuccess(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            keysBackupStateManager.state = KeysBackupState.Disabled
                            callback.onFailure(failure)
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun deleteBackup(version: String, callback: MatrixCallback<Unit>?) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            withContext(coroutineDispatchers.crypto) {
                // If we're currently backing up to this backup... stop.
                // (We start using it automatically in createKeysBackupVersion so this is symmetrical).
                if (keysBackupVersion != null && version == keysBackupVersion?.version) {
                    resetKeysBackupData()
                    keysBackupVersion = null
                    keysBackupStateManager.state = KeysBackupState.Unknown
                }

                deleteBackupTask
                        .configureWith(DeleteBackupTask.Params(version)) {
                            this.callback = object : MatrixCallback<Unit> {
                                private fun eventuallyRestartBackup() {
                                    // Do not stay in KeysBackupState.Unknown but check what is available on the homeserver
                                    if (state == KeysBackupState.Unknown) {
                                        checkAndStartKeysBackup()
                                    }
                                }

                                override fun onSuccess(data: Unit) {
                                    eventuallyRestartBackup()

                                    uiHandler.post { callback?.onSuccess(Unit) }
                                }

                                override fun onFailure(failure: Throwable) {
                                    eventuallyRestartBackup()

                                    uiHandler.post { callback?.onFailure(failure) }
                                }
                            }
                        }
                        .executeBy(taskExecutor)
            }
        }
    }

    override fun canRestoreKeys(): Boolean {
        // Server contains more keys than locally
        val totalNumberOfKeysLocally = getTotalNumbersOfKeys()

        val keysBackupData = cryptoStore.getKeysBackupData()

        val totalNumberOfKeysServer = keysBackupData?.backupLastServerNumberOfKeys ?: -1
        // Not used for the moment
        // val hashServer = keysBackupData?.backupLastServerHash

        return when {
            totalNumberOfKeysLocally < totalNumberOfKeysServer  -> {
                // Server contains more keys than this device
                true
            }
            totalNumberOfKeysLocally == totalNumberOfKeysServer -> {
                // Same number, compare hash?
                // TODO We have not found any algorithm to determine if a restore is recommended here. Return false for the moment
                false
            }
            else                                                -> false
        }
    }

    override fun getTotalNumbersOfKeys(): Int {
        return cryptoStore.inboundGroupSessionsCount(false)
    }

    override fun getTotalNumbersOfBackedUpKeys(): Int {
        return cryptoStore.inboundGroupSessionsCount(true)
    }

    override fun backupAllGroupSessions(progressListener: ProgressListener?,
                                        callback: MatrixCallback<Unit>?) {
        // Get a status right now
        getBackupProgress(object : ProgressListener {
            override fun onProgress(progress: Int, total: Int) {
                // Reset previous listeners if any
                resetBackupAllGroupSessionsListeners()
                Timber.v("backupAllGroupSessions: backupProgress: $progress/$total")
                try {
                    progressListener?.onProgress(progress, total)
                } catch (e: Exception) {
                    Timber.e(e, "backupAllGroupSessions: onProgress failure")
                }

                if (progress == total) {
                    Timber.v("backupAllGroupSessions: complete")
                    callback?.onSuccess(Unit)
                    return
                }

                backupAllGroupSessionsCallback = callback

                // Listen to `state` change to determine when to call onBackupProgress and onComplete
                keysBackupStateListener = object : KeysBackupStateListener {
                    override fun onStateChange(newState: KeysBackupState) {
                        getBackupProgress(object : ProgressListener {
                            override fun onProgress(progress: Int, total: Int) {
                                try {
                                    progressListener?.onProgress(progress, total)
                                } catch (e: Exception) {
                                    Timber.e(e, "backupAllGroupSessions: onProgress failure 2")
                                }

                                // If backup is finished, notify the main listener
                                if (state === KeysBackupState.ReadyToBackUp) {
                                    backupAllGroupSessionsCallback?.onSuccess(Unit)
                                    resetBackupAllGroupSessionsListeners()
                                }
                            }
                        })
                    }
                }.also { keysBackupStateManager.addListener(it) }

                backupKeys()
            }
        })
    }

    override fun getKeysBackupTrust(keysBackupVersion: KeysVersionResult,
                                    callback: MatrixCallback<KeysBackupVersionTrust>) {
        // TODO Validate with François that this is correct
        object : Task<KeysVersionResult, KeysBackupVersionTrust> {
            override suspend fun execute(params: KeysVersionResult): KeysBackupVersionTrust {
                return getKeysBackupTrustBg(params)
            }
        }
                .configureWith(keysBackupVersion) {
                    this.callback = callback
                    this.executionThread = TaskThread.COMPUTATION
                }
                .executeBy(taskExecutor)
    }

    /**
     * Check trust on a key backup version.
     * This has to be called on background thread.
     *
     * @param keysBackupVersion the backup version to check.
     * @return a KeysBackupVersionTrust object
     */
    @WorkerThread
    private fun getKeysBackupTrustBg(keysBackupVersion: KeysVersionResult): KeysBackupVersionTrust {
        val keysBackupVersionTrust = KeysBackupVersionTrust()
        val authData = keysBackupVersion.getAuthDataAsMegolmBackupAuthData()

        if (authData == null || authData.publicKey.isEmpty() || authData.signatures.isEmpty()) {
            Timber.v("getKeysBackupTrust: Key backup is absent or missing required data")
            return keysBackupVersionTrust
        }

        val mySigs = authData.signatures[userId]
        if (mySigs.isNullOrEmpty()) {
            Timber.v("getKeysBackupTrust: Ignoring key backup because it lacks any signatures from this user")
            return keysBackupVersionTrust
        }

        for ((keyId, mySignature) in mySigs) {
            // XXX: is this how we're supposed to get the device id?
            var deviceId: String? = null
            val components = keyId.split(":")
            if (components.size == 2) {
                deviceId = components[1]
            }

            if (deviceId != null) {
                val device = cryptoStore.getUserDevice(userId, deviceId)
                var isSignatureValid = false

                if (device == null) {
                    Timber.v("getKeysBackupTrust: Signature from unknown device $deviceId")
                } else {
                    val fingerprint = device.fingerprint()
                    if (fingerprint != null) {
                        try {
                            olmDevice.verifySignature(fingerprint, authData.signalableJSONDictionary(), mySignature)
                            isSignatureValid = true
                        } catch (e: OlmException) {
                            Timber.w(e, "getKeysBackupTrust: Bad signature from device ${device.deviceId}")
                        }
                    }

                    if (isSignatureValid && device.isVerified) {
                        keysBackupVersionTrust.usable = true
                    }
                }

                val signature = KeysBackupVersionTrustSignature()
                signature.device = device
                signature.valid = isSignatureValid
                signature.deviceId = deviceId
                keysBackupVersionTrust.signatures.add(signature)
            }
        }

        return keysBackupVersionTrust
    }

    override fun trustKeysBackupVersion(keysBackupVersion: KeysVersionResult,
                                        trust: Boolean,
                                        callback: MatrixCallback<Unit>) {
        Timber.v("trustKeyBackupVersion: $trust, version ${keysBackupVersion.version}")

        // Get auth data to update it
        val authData = getMegolmBackupAuthData(keysBackupVersion)

        if (authData == null) {
            Timber.w("trustKeyBackupVersion:trust: Key backup is missing required data")

            callback.onFailure(IllegalArgumentException("Missing element"))
        } else {
            cryptoCoroutineScope.launch(coroutineDispatchers.main) {
                val updateKeysBackupVersionBody = withContext(coroutineDispatchers.crypto) {
                    // Get current signatures, or create an empty set
                    val myUserSignatures = authData.signatures[userId].orEmpty().toMutableMap()

                    if (trust) {
                        // Add current device signature
                        val canonicalJson = JsonCanonicalizer.getCanonicalJson(Map::class.java, authData.signalableJSONDictionary())

                        val deviceSignatures = objectSigner.signObject(canonicalJson)

                        deviceSignatures[userId]?.forEach { entry ->
                            myUserSignatures[entry.key] = entry.value
                        }
                    } else {
                        // Remove current device signature
                        myUserSignatures.remove("ed25519:${credentials.deviceId}")
                    }

                    // Create an updated version of KeysVersionResult
                    val newMegolmBackupAuthData = authData.copy()

                    val newSignatures = newMegolmBackupAuthData.signatures.toMutableMap()
                    newSignatures[userId] = myUserSignatures

                    val newMegolmBackupAuthDataWithNewSignature = newMegolmBackupAuthData.copy(
                            signatures = newSignatures
                    )

                    @Suppress("UNCHECKED_CAST")
                    UpdateKeysBackupVersionBody(
                            algorithm = keysBackupVersion.algorithm,
                            authData = newMegolmBackupAuthDataWithNewSignature.toJsonDict(),
                            version = keysBackupVersion.version)
                }

                // And send it to the homeserver
                updateKeysBackupVersionTask
                        .configureWith(UpdateKeysBackupVersionTask.Params(keysBackupVersion.version, updateKeysBackupVersionBody)) {
                            this.callback = object : MatrixCallback<Unit> {
                                override fun onSuccess(data: Unit) {
                                    // Relaunch the state machine on this updated backup version
                                    val newKeysBackupVersion = KeysVersionResult(
                                            algorithm = keysBackupVersion.algorithm,
                                            authData = updateKeysBackupVersionBody.authData,
                                            version = keysBackupVersion.version,
                                            hash = keysBackupVersion.hash,
                                            count = keysBackupVersion.count
                                    )

                                    checkAndStartWithKeysBackupVersion(newKeysBackupVersion)

                                    callback.onSuccess(data)
                                }

                                override fun onFailure(failure: Throwable) {
                                    callback.onFailure(failure)
                                }
                            }
                        }
                        .executeBy(taskExecutor)
            }
        }
    }

    override fun trustKeysBackupVersionWithRecoveryKey(keysBackupVersion: KeysVersionResult,
                                                       recoveryKey: String,
                                                       callback: MatrixCallback<Unit>) {
        Timber.v("trustKeysBackupVersionWithRecoveryKey: version ${keysBackupVersion.version}")

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            val isValid = withContext(coroutineDispatchers.crypto) {
                isValidRecoveryKeyForKeysBackupVersion(recoveryKey, keysBackupVersion)
            }

            if (!isValid) {
                Timber.w("trustKeyBackupVersionWithRecoveryKey: Invalid recovery key.")

                callback.onFailure(IllegalArgumentException("Invalid recovery key or password"))
            } else {
                trustKeysBackupVersion(keysBackupVersion, true, callback)
            }
        }
    }

    override fun trustKeysBackupVersionWithPassphrase(keysBackupVersion: KeysVersionResult,
                                                      password: String,
                                                      callback: MatrixCallback<Unit>) {
        Timber.v("trustKeysBackupVersionWithPassphrase: version ${keysBackupVersion.version}")

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            val recoveryKey = withContext(coroutineDispatchers.crypto) {
                recoveryKeyFromPassword(password, keysBackupVersion, null)
            }

            if (recoveryKey == null) {
                Timber.w("trustKeysBackupVersionWithPassphrase: Key backup is missing required data")

                callback.onFailure(IllegalArgumentException("Missing element"))
            } else {
                // Check trust using the recovery key
                trustKeysBackupVersionWithRecoveryKey(keysBackupVersion, recoveryKey, callback)
            }
        }
    }

    override fun onSecretKeyGossip(secret: String) {
        Timber.i("## CrossSigning - onSecretKeyGossip")

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            try {
                val keysBackupVersion = getKeysBackupLastVersionTask.execute(Unit)
                val recoveryKey = computeRecoveryKey(secret.fromBase64())
                if (isValidRecoveryKeyForKeysBackupVersion(recoveryKey, keysBackupVersion)) {
                    awaitCallback<Unit> {
                        trustKeysBackupVersion(keysBackupVersion, true, it)
                    }
                    val importResult = awaitCallback<ImportRoomKeysResult> {
                        restoreKeysWithRecoveryKey(keysBackupVersion, recoveryKey, null, null, null, it)
                    }
                    withContext(coroutineDispatchers.crypto) {
                        cryptoStore.saveBackupRecoveryKey(recoveryKey, keysBackupVersion.version)
                    }
                    Timber.i("onSecretKeyGossip: Recovered keys ${importResult.successfullyNumberOfImportedKeys} out of ${importResult.totalNumberOfKeys}")
                } else {
                    Timber.e("onSecretKeyGossip: Recovery key is not valid ${keysBackupVersion.version}")
                }
            } catch (failure: Throwable) {
                Timber.e("onSecretKeyGossip: failed to trust key backup version ${keysBackupVersion?.version}")
            }
        }
    }

    /**
     * Get public key from a Recovery key
     *
     * @param recoveryKey the recovery key
     * @return the corresponding public key, from Olm
     */
    @WorkerThread
    private fun pkPublicKeyFromRecoveryKey(recoveryKey: String): String? {
        // Extract the primary key
        val privateKey = extractCurveKeyFromRecoveryKey(recoveryKey)

        if (privateKey == null) {
            Timber.w("pkPublicKeyFromRecoveryKey: private key is null")

            return null
        }

        // Built the PK decryption with it
        val pkPublicKey: String

        try {
            val decryption = OlmPkDecryption()
            pkPublicKey = decryption.setPrivateKey(privateKey)
        } catch (e: OlmException) {
            return null
        }

        return pkPublicKey
    }

    private fun resetBackupAllGroupSessionsListeners() {
        backupAllGroupSessionsCallback = null

        keysBackupStateListener?.let {
            keysBackupStateManager.removeListener(it)
        }

        keysBackupStateListener = null
    }

    override fun getBackupProgress(progressListener: ProgressListener) {
        val backedUpKeys = cryptoStore.inboundGroupSessionsCount(true)
        val total = cryptoStore.inboundGroupSessionsCount(false)

        progressListener.onProgress(backedUpKeys, total)
    }

    override fun restoreKeysWithRecoveryKey(keysVersionResult: KeysVersionResult,
                                            recoveryKey: String,
                                            roomId: String?,
                                            sessionId: String?,
                                            stepProgressListener: StepProgressListener?,
                                            callback: MatrixCallback<ImportRoomKeysResult>) {
        Timber.v("restoreKeysWithRecoveryKey: From backup version: ${keysVersionResult.version}")

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            runCatching {
                val decryption = withContext(coroutineDispatchers.crypto) {
                    // Check if the recovery is valid before going any further
                    if (!isValidRecoveryKeyForKeysBackupVersion(recoveryKey, keysVersionResult)) {
                        Timber.e("restoreKeysWithRecoveryKey: Invalid recovery key for this keys version")
                        throw InvalidParameterException("Invalid recovery key")
                    }

                    // Get a PK decryption instance
                    pkDecryptionFromRecoveryKey(recoveryKey)
                }
                if (decryption == null) {
                    // This should not happen anymore
                    Timber.e("restoreKeysWithRecoveryKey: Invalid recovery key. Error")
                    throw InvalidParameterException("Invalid recovery key")
                }

                stepProgressListener?.onStepProgress(StepProgressListener.Step.DownloadingKey)

                // Get backed up keys from the homeserver
                val data = getKeys(sessionId, roomId, keysVersionResult.version)

                withContext(coroutineDispatchers.computation) {
                    val sessionsData = ArrayList<MegolmSessionData>()
                    // Restore that data
                    var sessionsFromHsCount = 0
                    for ((roomIdLoop, backupData) in data.roomIdToRoomKeysBackupData) {
                        for ((sessionIdLoop, keyBackupData) in backupData.sessionIdToKeyBackupData) {
                            sessionsFromHsCount++

                            val sessionData = decryptKeyBackupData(keyBackupData, sessionIdLoop, roomIdLoop, decryption)

                            sessionData?.let {
                                sessionsData.add(it)
                            }
                        }
                    }
                    Timber.v("restoreKeysWithRecoveryKey: Decrypted ${sessionsData.size} keys out" +
                            " of $sessionsFromHsCount from the backup store on the homeserver")

                    // Do not trigger a backup for them if they come from the backup version we are using
                    val backUp = keysVersionResult.version != keysBackupVersion?.version
                    if (backUp) {
                        Timber.v("restoreKeysWithRecoveryKey: Those keys will be backed up" +
                                " to backup version: ${keysBackupVersion?.version}")
                    }

                    // Import them into the crypto store
                    val progressListener = if (stepProgressListener != null) {
                        object : ProgressListener {
                            override fun onProgress(progress: Int, total: Int) {
                                // Note: no need to post to UI thread, importMegolmSessionsData() will do it
                                stepProgressListener.onStepProgress(StepProgressListener.Step.ImportingKey(progress, total))
                            }
                        }
                    } else {
                        null
                    }

                    val result = megolmSessionDataImporter.handle(sessionsData, !backUp, progressListener)

                    // Do not back up the key if it comes from a backup recovery
                    if (backUp) {
                        maybeBackupKeys()
                    }
                    // Save for next time and for gossiping
                    saveBackupRecoveryKey(recoveryKey, keysVersionResult.version)
                    result
                }
            }.foldToCallback(callback)
        }
    }

    override fun restoreKeyBackupWithPassword(keysBackupVersion: KeysVersionResult,
                                              password: String,
                                              roomId: String?,
                                              sessionId: String?,
                                              stepProgressListener: StepProgressListener?,
                                              callback: MatrixCallback<ImportRoomKeysResult>) {
        Timber.v("[MXKeyBackup] restoreKeyBackup with password: From backup version: ${keysBackupVersion.version}")

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            runCatching {
                val progressListener = if (stepProgressListener != null) {
                    object : ProgressListener {
                        override fun onProgress(progress: Int, total: Int) {
                            uiHandler.post {
                                stepProgressListener.onStepProgress(StepProgressListener.Step.ComputingKey(progress, total))
                            }
                        }
                    }
                } else {
                    null
                }

                val recoveryKey = withContext(coroutineDispatchers.crypto) {
                    recoveryKeyFromPassword(password, keysBackupVersion, progressListener)
                }
                if (recoveryKey == null) {
                    Timber.v("backupKeys: Invalid configuration")
                    throw IllegalStateException("Invalid configuration")
                } else {
                    awaitCallback<ImportRoomKeysResult> {
                        restoreKeysWithRecoveryKey(keysBackupVersion, recoveryKey, roomId, sessionId, stepProgressListener, it)
                    }
                }
            }.foldToCallback(callback)
        }
    }

    /**
     * Same method as [RoomKeysRestClient.getRoomKey] except that it accepts nullable
     * parameters and always returns a KeysBackupData object through the Callback
     */
    private suspend fun getKeys(sessionId: String?,
                                roomId: String?,
                                version: String): KeysBackupData {
        return if (roomId != null && sessionId != null) {
            // Get key for the room and for the session
            val data = getRoomSessionDataTask.execute(GetRoomSessionDataTask.Params(roomId, sessionId, version))
            // Convert to KeysBackupData
            KeysBackupData(mutableMapOf(
                    roomId to RoomKeysBackupData(mutableMapOf(
                            sessionId to data
                    ))
            ))
        } else if (roomId != null) {
            // Get all keys for the room
            val data = getRoomSessionsDataTask.execute(GetRoomSessionsDataTask.Params(roomId, version))
            // Convert to KeysBackupData
            KeysBackupData(mutableMapOf(roomId to data))
        } else {
            // Get all keys
            getSessionsDataTask.execute(GetSessionsDataTask.Params(version))
        }
    }

    @VisibleForTesting
    @WorkerThread
    fun pkDecryptionFromRecoveryKey(recoveryKey: String): OlmPkDecryption? {
        // Extract the primary key
        val privateKey = extractCurveKeyFromRecoveryKey(recoveryKey)

        // Built the PK decryption with it
        var decryption: OlmPkDecryption? = null
        if (privateKey != null) {
            try {
                decryption = OlmPkDecryption()
                decryption.setPrivateKey(privateKey)
            } catch (e: OlmException) {
                Timber.e(e, "OlmException")
            }
        }

        return decryption
    }

    /**
     * Do a backup if there are new keys, with a delay
     */
    fun maybeBackupKeys() {
        when {
            isStucked                              -> {
                // If not already done, or in error case, check for a valid backup version on the homeserver.
                // If there is one, maybeBackupKeys will be called again.
                checkAndStartKeysBackup()
            }
            state == KeysBackupState.ReadyToBackUp -> {
                keysBackupStateManager.state = KeysBackupState.WillBackUp

                // Wait between 0 and 10 seconds, to avoid backup requests from
                // different clients hitting the server all at the same time when a
                // new key is sent
                val delayInMs = Random.nextLong(KEY_BACKUP_WAITING_TIME_TO_SEND_KEY_BACKUP_MILLIS)

                cryptoCoroutineScope.launch {
                    delay(delayInMs)
                    uiHandler.post { backupKeys() }
                }
            }
            else                                   -> {
                Timber.v("maybeBackupKeys: Skip it because state: $state")
            }
        }
    }

    override fun getVersion(version: String,
                            callback: MatrixCallback<KeysVersionResult?>) {
        getKeysBackupVersionTask
                .configureWith(version) {
                    this.callback = object : MatrixCallback<KeysVersionResult> {
                        override fun onSuccess(data: KeysVersionResult) {
                            callback.onSuccess(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            if (failure is Failure.ServerError
                                    && failure.error.code == MatrixError.M_NOT_FOUND) {
                                // Workaround because the homeserver currently returns M_NOT_FOUND when there is no key backup
                                callback.onSuccess(null)
                            } else {
                                // Transmit the error
                                callback.onFailure(failure)
                            }
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun getCurrentVersion(callback: MatrixCallback<KeysVersionResult?>) {
        getKeysBackupLastVersionTask
                .configureWith {
                    this.callback = object : MatrixCallback<KeysVersionResult> {
                        override fun onSuccess(data: KeysVersionResult) {
                            callback.onSuccess(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            if (failure is Failure.ServerError
                                    && failure.error.code == MatrixError.M_NOT_FOUND) {
                                // Workaround because the homeserver currently returns M_NOT_FOUND when there is no key backup
                                callback.onSuccess(null)
                            } else {
                                // Transmit the error
                                callback.onFailure(failure)
                            }
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun forceUsingLastVersion(callback: MatrixCallback<Boolean>) {
        getCurrentVersion(object : MatrixCallback<KeysVersionResult?> {
            override fun onSuccess(data: KeysVersionResult?) {
                val localBackupVersion = keysBackupVersion?.version
                val serverBackupVersion = data?.version

                if (serverBackupVersion == null) {
                    if (localBackupVersion == null) {
                        // No backup on the server, and backup is not active
                        callback.onSuccess(true)
                    } else {
                        // No backup on the server, and we are currently backing up, so stop backing up
                        callback.onSuccess(false)
                        resetKeysBackupData()
                        keysBackupVersion = null
                        keysBackupStateManager.state = KeysBackupState.Disabled
                    }
                } else {
                    if (localBackupVersion == null) {
                        // backup on the server, and backup is not active
                        callback.onSuccess(false)
                        // Do a check
                        checkAndStartWithKeysBackupVersion(data)
                    } else {
                        // Backup on the server, and we are currently backing up, compare version
                        if (localBackupVersion == serverBackupVersion) {
                            // We are already using the last version of the backup
                            callback.onSuccess(true)
                        } else {
                            // We are not using the last version, so delete the current version we are using on the server
                            callback.onSuccess(false)

                            // This will automatically check for the last version then
                            deleteBackup(localBackupVersion, null)
                        }
                    }
                }
            }

            override fun onFailure(failure: Throwable) {
                callback.onFailure(failure)
            }
        })
    }

    override fun checkAndStartKeysBackup() {
        if (!isStucked) {
            // Try to start or restart the backup only if it is in unknown or bad state
            Timber.w("checkAndStartKeysBackup: invalid state: $state")

            return
        }

        keysBackupVersion = null
        keysBackupStateManager.state = KeysBackupState.CheckingBackUpOnHomeserver

        getCurrentVersion(object : MatrixCallback<KeysVersionResult?> {
            override fun onSuccess(data: KeysVersionResult?) {
                checkAndStartWithKeysBackupVersion(data)
            }

            override fun onFailure(failure: Throwable) {
                Timber.e(failure, "checkAndStartKeysBackup: Failed to get current version")
                keysBackupStateManager.state = KeysBackupState.Unknown
            }
        })
    }

    private fun checkAndStartWithKeysBackupVersion(keyBackupVersion: KeysVersionResult?) {
        Timber.v("checkAndStartWithKeyBackupVersion: ${keyBackupVersion?.version}")

        keysBackupVersion = keyBackupVersion

        if (keyBackupVersion == null) {
            Timber.v("checkAndStartWithKeysBackupVersion: Found no key backup version on the homeserver")
            resetKeysBackupData()
            keysBackupStateManager.state = KeysBackupState.Disabled
        } else {
            getKeysBackupTrust(keyBackupVersion, object : MatrixCallback<KeysBackupVersionTrust> {
                override fun onSuccess(data: KeysBackupVersionTrust) {
                    val versionInStore = cryptoStore.getKeyBackupVersion()

                    if (data.usable) {
                        Timber.v("checkAndStartWithKeysBackupVersion: Found usable key backup. version: ${keyBackupVersion.version}")
                        // Check the version we used at the previous app run
                        if (versionInStore != null && versionInStore != keyBackupVersion.version) {
                            Timber.v(" -> clean the previously used version $versionInStore")
                            resetKeysBackupData()
                        }

                        Timber.v("   -> enabling key backups")
                        enableKeysBackup(keyBackupVersion)
                    } else {
                        Timber.v("checkAndStartWithKeysBackupVersion: No usable key backup. version: ${keyBackupVersion.version}")
                        if (versionInStore != null) {
                            Timber.v("   -> disabling key backup")
                            resetKeysBackupData()
                        }

                        keysBackupStateManager.state = KeysBackupState.NotTrusted
                    }
                }

                override fun onFailure(failure: Throwable) {
                    // Cannot happen
                }
            })
        }
    }

/* ==========================================================================================
 * Private
 * ========================================================================================== */

    /**
     * Extract MegolmBackupAuthData data from a backup version.
     *
     * @param keysBackupData the key backup data
     *
     * @return the authentication if found and valid, null in other case
     */
    private fun getMegolmBackupAuthData(keysBackupData: KeysVersionResult): MegolmBackupAuthData? {
        return keysBackupData
                .takeIf { it.version.isNotEmpty() && it.algorithm == MXCRYPTO_ALGORITHM_MEGOLM_BACKUP }
                ?.getAuthDataAsMegolmBackupAuthData()
                ?.takeIf { it.publicKey.isNotEmpty() }
    }

    /**
     * Compute the recovery key from a password and key backup version.
     *
     * @param password the password.
     * @param keysBackupData the backup and its auth data.
     *
     * @return the recovery key if successful, null in other cases
     */
    @WorkerThread
    private fun recoveryKeyFromPassword(password: String, keysBackupData: KeysVersionResult, progressListener: ProgressListener?): String? {
        val authData = getMegolmBackupAuthData(keysBackupData)

        if (authData == null) {
            Timber.w("recoveryKeyFromPassword: invalid parameter")
            return null
        }

        if (authData.privateKeySalt.isNullOrBlank()
                || authData.privateKeyIterations == null) {
            Timber.w("recoveryKeyFromPassword: Salt and/or iterations not found in key backup auth data")

            return null
        }

        // Extract the recovery key from the passphrase
        val data = retrievePrivateKeyWithPassword(password, authData.privateKeySalt, authData.privateKeyIterations, progressListener)

        return computeRecoveryKey(data)
    }

    /**
     * Check if a recovery key matches key backup authentication data.
     *
     * @param recoveryKey the recovery key to challenge.
     * @param keysBackupData the backup and its auth data.
     *
     * @return true if successful.
     */
    @WorkerThread
    private fun isValidRecoveryKeyForKeysBackupVersion(recoveryKey: String, keysBackupData: KeysVersionResult): Boolean {
        // Build PK decryption instance with the recovery key
        val publicKey = pkPublicKeyFromRecoveryKey(recoveryKey)

        if (publicKey == null) {
            Timber.w("isValidRecoveryKeyForKeysBackupVersion: public key is null")

            return false
        }

        val authData = getMegolmBackupAuthData(keysBackupData)

        if (authData == null) {
            Timber.w("isValidRecoveryKeyForKeysBackupVersion: Key backup is missing required data")

            return false
        }

        // Compare both
        if (publicKey != authData.publicKey) {
            Timber.w("isValidRecoveryKeyForKeysBackupVersion: Public keys mismatch")

            return false
        }

        // Public keys match!
        return true
    }

    override fun isValidRecoveryKeyForCurrentVersion(recoveryKey: String, callback: MatrixCallback<Boolean>) {
        val safeKeysBackupVersion = keysBackupVersion ?: return Unit.also { callback.onSuccess(false) }

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            isValidRecoveryKeyForKeysBackupVersion(recoveryKey, safeKeysBackupVersion).let {
                callback.onSuccess(it)
            }
        }
    }

    /**
     * Enable backing up of keys.
     * This method will update the state and will start sending keys in nominal case
     *
     * @param keysVersionResult backup information object as returned by [getCurrentVersion].
     */
    private fun enableKeysBackup(keysVersionResult: KeysVersionResult) {
        val retrievedMegolmBackupAuthData = keysVersionResult.getAuthDataAsMegolmBackupAuthData()

        if (retrievedMegolmBackupAuthData != null) {
            keysBackupVersion = keysVersionResult
            cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
                cryptoStore.setKeyBackupVersion(keysVersionResult.version)
            }

            onServerDataRetrieved(keysVersionResult.count, keysVersionResult.hash)

            try {
                backupOlmPkEncryption = OlmPkEncryption().apply {
                    setRecipientKey(retrievedMegolmBackupAuthData.publicKey)
                }
            } catch (e: OlmException) {
                Timber.e(e, "OlmException")
                keysBackupStateManager.state = KeysBackupState.Disabled
                return
            }

            keysBackupStateManager.state = KeysBackupState.ReadyToBackUp

            maybeBackupKeys()
        } else {
            Timber.e("Invalid authentication data")
            keysBackupStateManager.state = KeysBackupState.Disabled
        }
    }

    /**
     * Update the DB with data fetch from the server
     */
    private fun onServerDataRetrieved(count: Int?, etag: String?) {
        cryptoStore.setKeysBackupData(KeysBackupDataEntity()
                .apply {
                    backupLastServerNumberOfKeys = count
                    backupLastServerHash = etag
                }
        )
    }

    /**
     * Reset all local key backup data.
     *
     * Note: This method does not update the state
     */
    private fun resetKeysBackupData() {
        resetBackupAllGroupSessionsListeners()

        cryptoStore.setKeyBackupVersion(null)
        cryptoStore.setKeysBackupData(null)
        backupOlmPkEncryption?.releaseEncryption()
        backupOlmPkEncryption = null

        // Reset backup markers
        cryptoStore.resetBackupMarkers()
    }

    /**
     * Send a chunk of keys to backup
     */
    @UiThread
    private fun backupKeys() {
        Timber.v("backupKeys")

        // Sanity check, as this method can be called after a delay, the state may have change during the delay
        if (!isEnabled || backupOlmPkEncryption == null || keysBackupVersion == null) {
            Timber.v("backupKeys: Invalid configuration")
            backupAllGroupSessionsCallback?.onFailure(IllegalStateException("Invalid configuration"))
            resetBackupAllGroupSessionsListeners()

            return
        }

        if (state === KeysBackupState.BackingUp) {
            // Do nothing if we are already backing up
            Timber.v("backupKeys: Invalid state: $state")
            return
        }

        // Get a chunk of keys to backup
        val olmInboundGroupSessionWrappers = cryptoStore.inboundGroupSessionsToBackup(KEY_BACKUP_SEND_KEYS_MAX_COUNT)

        Timber.v("backupKeys: 1 - ${olmInboundGroupSessionWrappers.size} sessions to back up")

        if (olmInboundGroupSessionWrappers.isEmpty()) {
            // Backup is up to date
            keysBackupStateManager.state = KeysBackupState.ReadyToBackUp

            backupAllGroupSessionsCallback?.onSuccess(Unit)
            resetBackupAllGroupSessionsListeners()
            return
        }

        keysBackupStateManager.state = KeysBackupState.BackingUp

        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            withContext(coroutineDispatchers.crypto) {
                Timber.v("backupKeys: 2 - Encrypting keys")

                // Gather data to send to the homeserver
                // roomId -> sessionId -> MXKeyBackupData
                val keysBackupData = KeysBackupData()

                olmInboundGroupSessionWrappers.forEach { olmInboundGroupSessionWrapper ->
                    val roomId = olmInboundGroupSessionWrapper.roomId ?: return@forEach
                    val olmInboundGroupSession = olmInboundGroupSessionWrapper.olmInboundGroupSession ?: return@forEach

                    try {
                        encryptGroupSession(olmInboundGroupSessionWrapper)
                                ?.let {
                                    keysBackupData.roomIdToRoomKeysBackupData
                                            .getOrPut(roomId) { RoomKeysBackupData() }
                                            .sessionIdToKeyBackupData[olmInboundGroupSession.sessionIdentifier()] = it
                                }
                    } catch (e: OlmException) {
                        Timber.e(e, "OlmException")
                    }
                }

                Timber.v("backupKeys: 4 - Sending request")

                // Make the request
                val version = keysBackupVersion?.version ?: return@withContext

                storeSessionDataTask
                        .configureWith(StoreSessionsDataTask.Params(version, keysBackupData)) {
                            this.callback = object : MatrixCallback<BackupKeysResult> {
                                override fun onSuccess(data: BackupKeysResult) {
                                    uiHandler.post {
                                        Timber.v("backupKeys: 5a - Request complete")

                                        // Mark keys as backed up
                                        cryptoStore.markBackupDoneForInboundGroupSessions(olmInboundGroupSessionWrappers)

                                        if (olmInboundGroupSessionWrappers.size < KEY_BACKUP_SEND_KEYS_MAX_COUNT) {
                                            Timber.v("backupKeys: All keys have been backed up")
                                            onServerDataRetrieved(data.count, data.hash)

                                            // Note: Changing state will trigger the call to backupAllGroupSessionsCallback.onSuccess()
                                            keysBackupStateManager.state = KeysBackupState.ReadyToBackUp
                                        } else {
                                            Timber.v("backupKeys: Continue to back up keys")
                                            keysBackupStateManager.state = KeysBackupState.WillBackUp

                                            backupKeys()
                                        }
                                    }
                                }

                                override fun onFailure(failure: Throwable) {
                                    if (failure is Failure.ServerError) {
                                        uiHandler.post {
                                            Timber.e(failure, "backupKeys: backupKeys failed.")

                                            when (failure.error.code) {
                                                MatrixError.M_NOT_FOUND,
                                                MatrixError.M_WRONG_ROOM_KEYS_VERSION -> {
                                                    // Backup has been deleted on the server, or we are not using the last backup version
                                                    keysBackupStateManager.state = KeysBackupState.WrongBackUpVersion
                                                    backupAllGroupSessionsCallback?.onFailure(failure)
                                                    resetBackupAllGroupSessionsListeners()
                                                    resetKeysBackupData()
                                                    keysBackupVersion = null

                                                    // Do not stay in KeysBackupState.WrongBackUpVersion but check what is available on the homeserver
                                                    checkAndStartKeysBackup()
                                                }
                                                else                                  ->
                                                    // Come back to the ready state so that we will retry on the next received key
                                                    keysBackupStateManager.state = KeysBackupState.ReadyToBackUp
                                            }
                                        }
                                    } else {
                                        uiHandler.post {
                                            backupAllGroupSessionsCallback?.onFailure(failure)
                                            resetBackupAllGroupSessionsListeners()

                                            Timber.e("backupKeys: backupKeys failed.")

                                            // Retry a bit later
                                            keysBackupStateManager.state = KeysBackupState.ReadyToBackUp
                                            maybeBackupKeys()
                                        }
                                    }
                                }
                            }
                        }
                        .executeBy(taskExecutor)
            }
        }
    }

    @VisibleForTesting
    @WorkerThread
    fun encryptGroupSession(olmInboundGroupSessionWrapper: OlmInboundGroupSessionWrapper2): KeyBackupData? {
        // Gather information for each key
        val device = olmInboundGroupSessionWrapper.senderKey?.let { cryptoStore.deviceWithIdentityKey(it) }

        // Build the m.megolm_backup.v1.curve25519-aes-sha2 data as defined at
        // https://github.com/uhoreg/matrix-doc/blob/e2e_backup/proposals/1219-storing-megolm-keys-serverside.md#mmegolm_backupv1curve25519-aes-sha2-key-format
        val sessionData = olmInboundGroupSessionWrapper.exportKeys() ?: return null
        val sessionBackupData = mapOf(
                "algorithm" to sessionData.algorithm,
                "sender_key" to sessionData.senderKey,
                "sender_claimed_keys" to sessionData.senderClaimedKeys,
                "forwarding_curve25519_key_chain" to (sessionData.forwardingCurve25519KeyChain.orEmpty()),
                "session_key" to sessionData.sessionKey)

        val json = MoshiProvider.providesMoshi()
                .adapter(Map::class.java)
                .toJson(sessionBackupData)

        val encryptedSessionBackupData = try {
            backupOlmPkEncryption?.encrypt(json)
        } catch (e: OlmException) {
            Timber.e(e, "OlmException")
            null
        }
                ?: return null

        // Build backup data for that key
        return KeyBackupData(
                firstMessageIndex = try {
                    olmInboundGroupSessionWrapper.olmInboundGroupSession?.firstKnownIndex ?: 0
                } catch (e: OlmException) {
                    Timber.e(e, "OlmException")
                    0L
                },
                forwardedCount = olmInboundGroupSessionWrapper.forwardingCurve25519KeyChain.orEmpty().size,
                isVerified = device?.isVerified == true,

                sessionData = mapOf(
                        "ciphertext" to encryptedSessionBackupData.mCipherText,
                        "mac" to encryptedSessionBackupData.mMac,
                        "ephemeral" to encryptedSessionBackupData.mEphemeralKey)
        )
    }

    @VisibleForTesting
    @WorkerThread
    fun decryptKeyBackupData(keyBackupData: KeyBackupData, sessionId: String, roomId: String, decryption: OlmPkDecryption): MegolmSessionData? {
        var sessionBackupData: MegolmSessionData? = null

        val jsonObject = keyBackupData.sessionData

        val ciphertext = jsonObject["ciphertext"]?.toString()
        val mac = jsonObject["mac"]?.toString()
        val ephemeralKey = jsonObject["ephemeral"]?.toString()

        if (ciphertext != null && mac != null && ephemeralKey != null) {
            val encrypted = OlmPkMessage()
            encrypted.mCipherText = ciphertext
            encrypted.mMac = mac
            encrypted.mEphemeralKey = ephemeralKey

            try {
                val decrypted = decryption.decrypt(encrypted)

                val moshi = MoshiProvider.providesMoshi()
                val adapter = moshi.adapter(MegolmSessionData::class.java)

                sessionBackupData = adapter.fromJson(decrypted)
            } catch (e: OlmException) {
                Timber.e(e, "OlmException")
            }

            if (sessionBackupData != null) {
                sessionBackupData = sessionBackupData.copy(
                        sessionId = sessionId,
                        roomId = roomId
                )
            }
        }

        return sessionBackupData
    }

    /* ==========================================================================================
     * For test only
     * ========================================================================================== */

    // Direct access for test only
    @VisibleForTesting
    val store
        get() = cryptoStore

    @VisibleForTesting
    fun createFakeKeysBackupVersion(keysBackupCreationInfo: MegolmBackupCreationInfo,
                                    callback: MatrixCallback<KeysVersion>) {
        @Suppress("UNCHECKED_CAST")
        val createKeysBackupVersionBody = CreateKeysBackupVersionBody(
                algorithm = keysBackupCreationInfo.algorithm,
                authData = keysBackupCreationInfo.authData.toJsonDict()
        )

        createKeysBackupVersionTask
                .configureWith(createKeysBackupVersionBody) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getKeyBackupRecoveryKeyInfo(): SavedKeyBackupKeyInfo? {
        return cryptoStore.getKeyBackupRecoveryKeyInfo()
    }

    override fun saveBackupRecoveryKey(recoveryKey: String?, version: String?) {
        cryptoStore.saveBackupRecoveryKey(recoveryKey, version)
    }

    companion object {
        // Maximum delay in ms in {@link maybeBackupKeys}
        private const val KEY_BACKUP_WAITING_TIME_TO_SEND_KEY_BACKUP_MILLIS = 10_000L

        // Maximum number of keys to send at a time to the homeserver.
        private const val KEY_BACKUP_SEND_KEYS_MAX_COUNT = 100
    }

/* ==========================================================================================
 * DEBUG INFO
 * ========================================================================================== */

    override fun toString() = "KeysBackup for $userId"
}

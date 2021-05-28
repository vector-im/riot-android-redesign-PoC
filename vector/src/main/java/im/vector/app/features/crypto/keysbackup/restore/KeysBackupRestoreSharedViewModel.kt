/*
 * Copyright 2019 New Vector Ltd
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
package im.vector.app.features.crypto.keysbackup.restore

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.vector.app.R
import im.vector.app.core.platform.WaitingViewData
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.LiveEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.listeners.StepProgressListener
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupService
import org.matrix.android.sdk.api.session.securestorage.KeyInfoResult
import org.matrix.android.sdk.internal.crypto.crosssigning.fromBase64
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.KeysVersionResult
import org.matrix.android.sdk.internal.crypto.keysbackup.util.computeRecoveryKey
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.util.awaitCallback
import timber.log.Timber
import javax.inject.Inject

class KeysBackupRestoreSharedViewModel @Inject constructor(
        private val stringProvider: StringProvider
) : ViewModel() {

    data class KeySource(
            val isInMemory: Boolean,
            val isInQuadS: Boolean
    )

    companion object {
        const val NAVIGATE_TO_RECOVER_WITH_KEY = "NAVIGATE_TO_RECOVER_WITH_KEY"
        const val NAVIGATE_TO_SUCCESS = "NAVIGATE_TO_SUCCESS"
        const val NAVIGATE_TO_4S = "NAVIGATE_TO_4S"
        const val NAVIGATE_FAILED_TO_LOAD_4S = "NAVIGATE_FAILED_TO_LOAD_4S"
    }

    lateinit var session: Session

    var keyVersionResult: MutableLiveData<KeysVersionResult> = MutableLiveData()

    var keySourceModel: MutableLiveData<KeySource> = MutableLiveData()

    private var _keyVersionResultError: MutableLiveData<LiveEvent<String>> = MutableLiveData()
    val keyVersionResultError: LiveData<LiveEvent<String>>
        get() = _keyVersionResultError

    private var _navigateEvent: MutableLiveData<LiveEvent<String>> = MutableLiveData()
    val navigateEvent: LiveData<LiveEvent<String>>
        get() = _navigateEvent

    var loadingEvent: MutableLiveData<WaitingViewData> = MutableLiveData()

    var importKeyResult: ImportRoomKeysResult? = null
    var importRoomKeysFinishWithResult: MutableLiveData<LiveEvent<ImportRoomKeysResult>> = MutableLiveData()

    init {
        keyVersionResult.value = null
        _keyVersionResultError.value = null
        loadingEvent.value = null
    }

    fun initSession(session: Session) {
        this.session = session
    }

    val progressObserver = object : StepProgressListener {
        override fun onStepProgress(step: StepProgressListener.Step) {
            when (step) {
                is StepProgressListener.Step.ComputingKey   -> {
                    loadingEvent.postValue(WaitingViewData(stringProvider.getString(R.string.keys_backup_restoring_waiting_message)
                            + "\n" + stringProvider.getString(R.string.keys_backup_restoring_computing_key_waiting_message),
                            step.progress,
                            step.total))
                }
                is StepProgressListener.Step.DownloadingKey -> {
                    loadingEvent.postValue(WaitingViewData(stringProvider.getString(R.string.keys_backup_restoring_waiting_message)
                            + "\n" + stringProvider.getString(R.string.keys_backup_restoring_downloading_backup_waiting_message),
                            isIndeterminate = true))
                }
                is StepProgressListener.Step.ImportingKey   -> {
                    Timber.d("backupKeys.ImportingKey.progress: ${step.progress}")
                    // Progress 0 can take a while, display an indeterminate progress in this case
                    if (step.progress == 0) {
                        loadingEvent.postValue(WaitingViewData(stringProvider.getString(R.string.keys_backup_restoring_waiting_message)
                                + "\n" + stringProvider.getString(R.string.keys_backup_restoring_importing_keys_waiting_message),
                                isIndeterminate = true))
                    } else {
                        loadingEvent.postValue(WaitingViewData(stringProvider.getString(R.string.keys_backup_restoring_waiting_message)
                                + "\n" + stringProvider.getString(R.string.keys_backup_restoring_importing_keys_waiting_message),
                                step.progress,
                                step.total))
                    }
                }
            }
        }
    }

    fun getLatestVersion() {
        val keysBackup = session.cryptoService().keysBackupService()

        loadingEvent.value = WaitingViewData(stringProvider.getString(R.string.keys_backup_restore_is_getting_backup_version))

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val version = awaitCallback<KeysVersionResult?> {
                    keysBackup.getCurrentVersion(it)
                }
                if (version?.version == null) {
                    loadingEvent.postValue(null)
                    _keyVersionResultError.postValue(LiveEvent(stringProvider.getString(R.string.keys_backup_get_version_error, "")))
                    return@launch
                }

                keyVersionResult.postValue(version)
                // Let's check if there is quads
                val isBackupKeyInQuadS = isBackupKeyInQuadS()

                val savedSecret = session.cryptoService().keysBackupService().getKeyBackupRecoveryKeyInfo()
                if (savedSecret != null && savedSecret.version == version.version) {
                    // key is in memory!
                    keySourceModel.postValue(
                            KeySource(isInMemory = true, isInQuadS = true)
                    )
                    // Go and use it!!
                    try {
                        recoverUsingBackupRecoveryKey(savedSecret.recoveryKey)
                    } catch (failure: Throwable) {
                        keySourceModel.postValue(
                                KeySource(isInMemory = false, isInQuadS = true)
                        )
                    }
                } else if (isBackupKeyInQuadS) {
                    // key is in QuadS!
                    keySourceModel.postValue(
                            KeySource(isInMemory = false, isInQuadS = true)
                    )
                    _navigateEvent.postValue(LiveEvent(NAVIGATE_TO_4S))
                } else {
                    // we need to restore directly
                    keySourceModel.postValue(
                            KeySource(isInMemory = false, isInQuadS = false)
                    )
                }

                loadingEvent.postValue(null)
            } catch (failure: Throwable) {
                loadingEvent.postValue(null)
                _keyVersionResultError.postValue(LiveEvent(stringProvider.getString(R.string.keys_backup_get_version_error, failure.localizedMessage)))
            }
        }
    }

    fun handleGotSecretFromSSSS(cipherData: String, alias: String) {
        try {
            cipherData.fromBase64().inputStream().use { ins ->
                val res = session.loadSecureSecret<Map<String, String>>(ins, alias)
                val secret = res?.get(KEYBACKUP_SECRET_SSSS_NAME)
                if (secret == null) {
                    _navigateEvent.postValue(
                            LiveEvent(NAVIGATE_FAILED_TO_LOAD_4S)
                    )
                    return
                }
                loadingEvent.value = WaitingViewData(stringProvider.getString(R.string.keys_backup_restore_is_getting_backup_version))

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        recoverUsingBackupRecoveryKey(computeRecoveryKey(secret.fromBase64()))
                    } catch (failure: Throwable) {
                        _navigateEvent.postValue(
                                LiveEvent(NAVIGATE_FAILED_TO_LOAD_4S)
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            _navigateEvent.postValue(
                    LiveEvent(NAVIGATE_FAILED_TO_LOAD_4S)
            )
        }
    }

    suspend fun recoverUsingBackupPass(passphrase: String) {
        val keysBackup = session.cryptoService().keysBackupService()
        val keyVersion = keyVersionResult.value ?: return

        loadingEvent.postValue(WaitingViewData(stringProvider.getString(R.string.loading)))

        try {
            val result = awaitCallback<ImportRoomKeysResult> {
                keysBackup.restoreKeyBackupWithPassword(keyVersion,
                        passphrase,
                        null,
                        session.myUserId,
                        progressObserver,
                        it
                )
            }
            loadingEvent.postValue(null)
            didRecoverSucceed(result)
            trustOnDecrypt(keysBackup, keyVersion)
        } catch (failure: Throwable) {
            loadingEvent.postValue(null)
            throw failure
        }
    }

    suspend fun recoverUsingBackupRecoveryKey(recoveryKey: String) {
        val keysBackup = session.cryptoService().keysBackupService()
        val keyVersion = keyVersionResult.value ?: return

        loadingEvent.postValue(WaitingViewData(stringProvider.getString(R.string.loading)))

        try {
            val result = awaitCallback<ImportRoomKeysResult> {
                keysBackup.restoreKeysWithRecoveryKey(keyVersion,
                        recoveryKey,
                        null,
                        session.myUserId,
                        progressObserver,
                        it
                )
            }
            loadingEvent.postValue(null)
            didRecoverSucceed(result)
            trustOnDecrypt(keysBackup, keyVersion)
        } catch (failure: Throwable) {
            loadingEvent.postValue(null)
            throw failure
        }
    }

    private fun isBackupKeyInQuadS(): Boolean {
        val sssBackupSecret = session.userAccountDataService().getAccountDataEvent(KEYBACKUP_SECRET_SSSS_NAME)
                ?: return false

        // Some sanity ?
        val defaultKeyResult = session.sharedSecretStorageService.getDefaultKey()
        val keyInfo = (defaultKeyResult as? KeyInfoResult.Success)?.keyInfo
                ?: return false

        return (sssBackupSecret.content["encrypted"] as? Map<*, *>)?.containsKey(keyInfo.id) == true
    }

    private fun trustOnDecrypt(keysBackup: KeysBackupService, keysVersionResult: KeysVersionResult) {
        keysBackup.trustKeysBackupVersion(keysVersionResult, true,
                object : MatrixCallback<Unit> {
                    override fun onSuccess(data: Unit) {
                        Timber.v("##### trustKeysBackupVersion onSuccess")
                    }
                })
    }

    fun moveToRecoverWithKey() {
        _navigateEvent.value = LiveEvent(NAVIGATE_TO_RECOVER_WITH_KEY)
    }

    fun didRecoverSucceed(result: ImportRoomKeysResult) {
        importKeyResult = result
        _navigateEvent.postValue(LiveEvent(NAVIGATE_TO_SUCCESS))
    }
}

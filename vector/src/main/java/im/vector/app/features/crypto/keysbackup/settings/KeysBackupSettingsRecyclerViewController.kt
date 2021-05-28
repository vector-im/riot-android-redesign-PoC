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
package im.vector.app.features.crypto.keysbackup.settings

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.ItemStyle
import im.vector.app.core.ui.list.genericItem
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState
import org.matrix.android.sdk.internal.crypto.keysbackup.model.KeysBackupVersionTrust
import java.util.UUID
import javax.inject.Inject

class KeysBackupSettingsRecyclerViewController @Inject constructor(
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
        private val session: Session
) : TypedEpoxyController<KeysBackupSettingViewState>() {

    var listener: Listener? = null

    override fun buildModels(data: KeysBackupSettingViewState?) {
        if (data == null) {
            return
        }

        val host = this
        var isBackupAlreadySetup = false

        val keyBackupState = data.keysBackupState
        val keyVersionResult = data.keysBackupVersion

        when (keyBackupState) {
            KeysBackupState.Unknown                    -> {
                errorWithRetryItem {
                    id("summary")
                    text(host.stringProvider.getString(R.string.keys_backup_unable_to_get_keys_backup_data))
                    listener { host.listener?.loadKeysBackupState() }
                }

                // Nothing else to display
                return
            }
            KeysBackupState.CheckingBackUpOnHomeserver -> {
                loadingItem {
                    id("summary")
                    loadingText(host.stringProvider.getString(R.string.keys_backup_settings_checking_backup_state))
                }
            }
            KeysBackupState.Disabled                   -> {
                genericItem {
                    id("summary")
                    title(host.stringProvider.getString(R.string.keys_backup_settings_status_not_setup))
                    style(ItemStyle.BIG_TEXT)

                    if (data.keysBackupVersionTrust()?.usable == false) {
                        description(host.stringProvider.getString(R.string.keys_backup_settings_untrusted_backup))
                    }
                }

                isBackupAlreadySetup = false
            }
            KeysBackupState.WrongBackUpVersion,
            KeysBackupState.NotTrusted,
            KeysBackupState.Enabling                   -> {
                genericItem {
                    id("summary")
                    title(host.stringProvider.getString(R.string.keys_backup_settings_status_ko))
                    style(ItemStyle.BIG_TEXT)
                    if (data.keysBackupVersionTrust()?.usable == false) {
                        description(host.stringProvider.getString(R.string.keys_backup_settings_untrusted_backup))
                    } else {
                        description(keyBackupState.toString())
                    }
                    endIconResourceId(R.drawable.unit_test_ko)
                }

                isBackupAlreadySetup = true
            }
            KeysBackupState.ReadyToBackUp              -> {
                genericItem {
                    id("summary")
                    title(host.stringProvider.getString(R.string.keys_backup_settings_status_ok))
                    style(ItemStyle.BIG_TEXT)
                    if (data.keysBackupVersionTrust()?.usable == false) {
                        description(host.stringProvider.getString(R.string.keys_backup_settings_untrusted_backup))
                    } else {
                        description(host.stringProvider.getString(R.string.keys_backup_info_keys_all_backup_up))
                    }
                    endIconResourceId(R.drawable.unit_test_ok)
                }

                isBackupAlreadySetup = true
            }
            KeysBackupState.WillBackUp,
            KeysBackupState.BackingUp                  -> {
                genericItem {
                    id("summary")
                    title(host.stringProvider.getString(R.string.keys_backup_settings_status_ok))
                    style(ItemStyle.BIG_TEXT)
                    hasIndeterminateProcess(true)

                    val totalKeys = host.session.cryptoService().inboundGroupSessionsCount(false)
                    val backedUpKeys = host.session.cryptoService().inboundGroupSessionsCount(true)

                    val remainingKeysToBackup = totalKeys - backedUpKeys

                    if (data.keysBackupVersionTrust()?.usable == false) {
                        description(host.stringProvider.getString(R.string.keys_backup_settings_untrusted_backup))
                    } else {
                        description(host.stringProvider
                                .getQuantityString(R.plurals.keys_backup_info_keys_backing_up, remainingKeysToBackup, remainingKeysToBackup))
                    }
                }

                isBackupAlreadySetup = true
            }
        }

        if (isBackupAlreadySetup) {
            // Add infos
            genericItem {
                id("version")
                title(host.stringProvider.getString(R.string.keys_backup_info_title_version))
                description(keyVersionResult?.version ?: "")
            }

            genericItem {
                id("algorithm")
                title(host.stringProvider.getString(R.string.keys_backup_info_title_algorithm))
                description(keyVersionResult?.algorithm ?: "")
            }

            if (vectorPreferences.developerMode()) {
                buildKeysBackupTrust(data.keysBackupVersionTrust)
            }
        }

        // Footer
        keysBackupSettingFooterItem {
            id("footer")

            if (isBackupAlreadySetup) {
                textButton1(host.stringProvider.getString(R.string.keys_backup_settings_restore_backup_button))
                clickOnButton1(View.OnClickListener { host.listener?.didSelectRestoreMessageRecovery() })

                textButton2(host.stringProvider.getString(R.string.keys_backup_settings_delete_backup_button))
                clickOnButton2(View.OnClickListener { host.listener?.didSelectDeleteSetupMessageRecovery() })
            } else {
                textButton1(host.stringProvider.getString(R.string.keys_backup_setup))
                clickOnButton1(View.OnClickListener { host.listener?.didSelectSetupMessageRecovery() })
            }
        }
    }

    private fun buildKeysBackupTrust(keysVersionTrust: Async<KeysBackupVersionTrust>) {
        val host = this
        when (keysVersionTrust) {
            is Uninitialized -> Unit
            is Loading       -> {
                loadingItem {
                    id("trust")
                }
            }
            is Success       -> {
                keysVersionTrust().signatures.forEach {
                    genericItem {
                        id(UUID.randomUUID().toString())
                        title(host.stringProvider.getString(R.string.keys_backup_info_title_signature))

                        val isDeviceKnown = it.device != null
                        val isDeviceVerified = it.device?.isVerified ?: false
                        val isSignatureValid = it.valid
                        val deviceId: String = it.deviceId ?: ""

                        if (!isDeviceKnown) {
                            description(host.stringProvider.getString(R.string.keys_backup_settings_signature_from_unknown_device, deviceId))
                            endIconResourceId(R.drawable.e2e_warning)
                        } else {
                            if (isSignatureValid) {
                                if (host.session.sessionParams.deviceId == it.deviceId) {
                                    description(host.stringProvider.getString(R.string.keys_backup_settings_valid_signature_from_this_device))
                                    endIconResourceId(R.drawable.e2e_verified)
                                } else {
                                    if (isDeviceVerified) {
                                        description(host.stringProvider
                                                .getString(R.string.keys_backup_settings_valid_signature_from_verified_device, deviceId))
                                        endIconResourceId(R.drawable.e2e_verified)
                                    } else {
                                        description(host.stringProvider
                                                .getString(R.string.keys_backup_settings_valid_signature_from_unverified_device, deviceId))
                                        endIconResourceId(R.drawable.e2e_warning)
                                    }
                                }
                            } else {
                                // Invalid signature
                                endIconResourceId(R.drawable.e2e_warning)
                                if (isDeviceVerified) {
                                    description(host.stringProvider
                                            .getString(R.string.keys_backup_settings_invalid_signature_from_verified_device, deviceId))
                                } else {
                                    description(host.stringProvider
                                            .getString(R.string.keys_backup_settings_invalid_signature_from_unverified_device, deviceId))
                                }
                            }
                        }
                    }
                } // end for each
            }
            is Fail          -> {
                errorWithRetryItem {
                    id("trust")
                    text(host.stringProvider.getString(R.string.keys_backup_unable_to_get_trust_info))
                    listener { host.listener?.loadTrustData() }
                }
            }
        }
    }

    interface Listener {
        fun didSelectSetupMessageRecovery()
        fun didSelectRestoreMessageRecovery()
        fun didSelectDeleteSetupMessageRecovery()
        fun loadTrustData()
        fun loadKeysBackupState()
    }
}

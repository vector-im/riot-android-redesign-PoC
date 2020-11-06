/*
 * Copyright 2019 New Vector Ltd
 * Copyright 2020 New Vector Ltd
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

package im.vector.app.features.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.dialogs.ExportKeysDialog
import im.vector.app.core.extensions.queryExportKeys
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.showPassword
import im.vector.app.core.intent.ExternalIntentData
import im.vector.app.core.intent.analyseIntent
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.openFileSelection
import im.vector.app.core.utils.toast
import im.vector.app.features.crypto.keys.KeysExporter
import im.vector.app.features.crypto.keys.KeysImporter
import im.vector.app.features.crypto.keysbackup.settings.KeysBackupManageActivity
import im.vector.app.features.crypto.recover.BootstrapBottomSheet
import im.vector.app.features.crypto.recover.SetupMode
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.pin.PinCodeStore
import im.vector.app.features.pin.PinMode
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.raw.wellknown.isE2EByDefault
import im.vector.app.features.themes.ThemeUtils
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import me.gujun.android.span.span
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.extensions.getFingerprintHumanReadable
import org.matrix.android.sdk.internal.crypto.crosssigning.isVerified
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceInfo
import org.matrix.android.sdk.internal.crypto.model.rest.DevicesListResponse
import org.matrix.android.sdk.rx.SecretsSynchronisationInfo
import org.matrix.android.sdk.rx.rx
import javax.inject.Inject

class VectorSettingsSecurityPrivacyFragment @Inject constructor(
        private val vectorPreferences: VectorPreferences,
        private val activeSessionHolder: ActiveSessionHolder,
        private val pinCodeStore: PinCodeStore,
        private val navigator: Navigator
) : VectorSettingsBaseFragment() {

    override var titleRes = R.string.settings_security_and_privacy
    override val preferenceXmlRes = R.xml.vector_settings_security_privacy
    private var disposables = mutableListOf<Disposable>()

    // cryptography
    private val mCryptographyCategory by lazy {
        findPreference<PreferenceCategory>(VectorPreferences.SETTINGS_CRYPTOGRAPHY_PREFERENCE_KEY)!!
    }

    private val cryptoInfoDeviceNamePreference by lazy {
        findPreference<VectorPreference>("SETTINGS_ENCRYPTION_INFORMATION_DEVICE_NAME_PREFERENCE_KEY")!!
    }

    private val cryptoInfoDeviceIdPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_ENCRYPTION_INFORMATION_DEVICE_ID_PREFERENCE_KEY")!!
    }

    private val cryptoInfoDeviceKeyPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_ENCRYPTION_INFORMATION_DEVICE_KEY_PREFERENCE_KEY")!!
    }

    private val mCrossSigningStatePreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_ENCRYPTION_CROSS_SIGNING_PREFERENCE_KEY)!!
    }

    private val manageBackupPref by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_SECURE_MESSAGE_RECOVERY_PREFERENCE_KEY)!!
    }

    private val exportPref by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_ENCRYPTION_EXPORT_E2E_ROOM_KEYS_PREFERENCE_KEY)!!
    }

    private val importPref by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_ENCRYPTION_IMPORT_E2E_ROOM_KEYS_PREFERENCE_KEY)!!
    }

    private val showDeviceListPref by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_SHOW_DEVICES_LIST_PREFERENCE_KEY)!!
    }

    // encrypt to unverified devices
    private val sendToUnverifiedDevicesPref by lazy {
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_ENCRYPTION_NEVER_SENT_TO_PREFERENCE_KEY)!!
    }

    private val openPinCodeSettingsPref by lazy {
        findPreference<VectorPreference>("SETTINGS_SECURITY_PIN")!!
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater?, parent: ViewGroup?, savedInstanceState: Bundle?): RecyclerView {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState).also {
            // Insert animation are really annoying the first time the list is shown
            // due to the way preference fragment is done, it's not trivial to disable it for first appearance only..
            // And it's not that an issue that this list is not animated, it's pretty static
            it.itemAnimator = null
        }
    }

    override fun onResume() {
        super.onResume()
        // My device name may have been updated
        refreshMyDevice()
        refreshXSigningStatus()
        session.rx().liveSecretSynchronisationInfo()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    refresh4SSection(it)
                    refreshXSigningStatus()
                }.also {
                    disposables.add(it)
                }

        lifecycleScope.launchWhenResumed {
            findPreference<VectorPreference>(VectorPreferences.SETTINGS_CRYPTOGRAPHY_HS_ADMIN_DISABLED_E2E_DEFAULT)?.isVisible =
                    vectorActivity.getVectorComponent()
                            .rawService()
                            .getElementWellknown(session.myUserId)
                            ?.isE2EByDefault() == false
        }

        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SettingsView("privacy"))
    }

    private val secureBackupCategory by lazy {
        findPreference<VectorPreferenceCategory>("SETTINGS_CRYPTOGRAPHY_MANAGE_4S_CATEGORY_KEY")!!
    }
    private val secureBackupPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_SECURE_BACKUP_RECOVERY_PREFERENCE_KEY")!!
    }
//    private val secureBackupResetPreference by lazy {
//        findPreference<VectorPreference>(VectorPreferences.SETTINGS_SECURE_BACKUP_RESET_PREFERENCE_KEY)
//    }

    override fun onPause() {
        super.onPause()
        disposables.forEach {
            it.dispose()
        }
        disposables.clear()
    }

    private fun refresh4SSection(info: SecretsSynchronisationInfo) {
        // it's a lot of if / else if / else
        // But it's not yet clear how to manage all cases
        if (!info.isCrossSigningEnabled) {
            // There is not cross signing, so we can remove the section
            secureBackupCategory.isVisible = false
        } else {
            if (!info.isBackupSetup) {
                if (info.isCrossSigningEnabled && info.allPrivateKeysKnown) {
                    // You can setup recovery!
                    secureBackupCategory.isVisible = true
                    secureBackupPreference.title = getString(R.string.settings_secure_backup_setup)
                    secureBackupPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                        BootstrapBottomSheet.show(parentFragmentManager, SetupMode.NORMAL)
                        true
                    }
                } else {
                    // just hide all, you can't setup from here
                    // you should synchronize to get gossips
                    secureBackupCategory.isVisible = false
                }
            } else {
                // so here we know that 4S is setup
                if (info.isCrossSigningTrusted && info.allPrivateKeysKnown) {
                    // Looks like we have all cross signing secrets and session is trusted
                    // Let's see if there is a megolm backup
                    if (!info.megolmBackupAvailable || info.megolmSecretKnown) {
                        // Only option here is to create a new backup if you want?
                        // aka reset
                        secureBackupCategory.isVisible = true
                        secureBackupPreference.title = getString(R.string.settings_secure_backup_reset)
                        secureBackupPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                            BootstrapBottomSheet.show(parentFragmentManager, SetupMode.PASSPHRASE_RESET)
                            true
                        }
                    } else if (!info.megolmSecretKnown) {
                        // megolm backup is available but we don't have key
                        // you could try to synchronize to get missing megolm key ?
                        secureBackupCategory.isVisible = true
                        secureBackupPreference.title = getString(R.string.settings_secure_backup_enter_to_setup)
                        secureBackupPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                            vectorActivity.let {
                                it.navigator.requestSelfSessionVerification(it)
                            }
                            true
                        }
                    } else {
                        secureBackupCategory.isVisible = false
                    }
                } else {
                    // there is a backup, but this session is not trusted, or is missing some secrets
                    // you should enter passphrase to get them or verify against another session
                    secureBackupCategory.isVisible = true
                    secureBackupPreference.title = getString(R.string.settings_secure_backup_enter_to_setup)
                    secureBackupPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                        vectorActivity.let {
                            it.navigator.requestSelfSessionVerification(it)
                        }
                        true
                    }
                }
            }
        }
    }

    override fun bindPref() {
        // Refresh Key Management section
        refreshKeysManagementSection()

        // Analytics

        // Analytics tracking management
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_USE_ANALYTICS_KEY)!!.let {
            // On if the analytics tracking is activated
            it.isChecked = vectorPreferences.useAnalytics()

            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                vectorPreferences.setUseAnalytics(newValue as Boolean)
                true
            }
        }

        openPinCodeSettingsPref.setOnPreferenceClickListener {
            openPinCodePreferenceScreen()
            true
        }

        refreshXSigningStatus()

        secureBackupPreference.icon = activity?.let {
            ThemeUtils.tintDrawable(it,
                    ContextCompat.getDrawable(it, R.drawable.ic_secure_backup)!!, R.attr.vctr_settings_icon_tint_color)
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CRYPTOGRAPHY_HS_ADMIN_DISABLED_E2E_DEFAULT)?.let {
            it.icon = ThemeUtils.tintDrawableWithColor(
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_notification_privacy_warning)!!,
                    ContextCompat.getColor(requireContext(), R.color.riotx_destructive_accent)
            )
            it.summary = span {
                text = getString(R.string.settings_hs_admin_e2e_disabled)
                textColor = ContextCompat.getColor(requireContext(), R.color.riotx_destructive_accent)
            }
        }
    }

    // Todo this should be refactored and use same state as 4S section
    private fun refreshXSigningStatus() {
        val crossSigningKeys = session.cryptoService().crossSigningService().getMyCrossSigningKeys()
        val xSigningIsEnableInAccount = crossSigningKeys != null
        val xSigningKeysAreTrusted = session.cryptoService().crossSigningService().checkUserTrust(session.myUserId).isVerified()
        val xSigningKeyCanSign = session.cryptoService().crossSigningService().canCrossSign()

        when {
            xSigningKeyCanSign        -> {
                mCrossSigningStatePreference.setIcon(R.drawable.ic_shield_trusted)
                mCrossSigningStatePreference.summary = getString(R.string.encryption_information_dg_xsigning_complete)
            }
            xSigningKeysAreTrusted    -> {
                mCrossSigningStatePreference.setIcon(R.drawable.ic_shield_custom)
                mCrossSigningStatePreference.summary = getString(R.string.encryption_information_dg_xsigning_trusted)
            }
            xSigningIsEnableInAccount -> {
                mCrossSigningStatePreference.setIcon(R.drawable.ic_shield_black)
                mCrossSigningStatePreference.summary = getString(R.string.encryption_information_dg_xsigning_not_trusted)
            }
            else                      -> {
                mCrossSigningStatePreference.setIcon(android.R.color.transparent)
                mCrossSigningStatePreference.summary = getString(R.string.encryption_information_dg_xsigning_disabled)
            }
        }

        mCrossSigningStatePreference.isVisible = true
        if (!vectorPreferences.developerMode()) {
            // When not in developer mode, intercept click on this preference
            mCrossSigningStatePreference.onPreferenceClickListener = Preference.OnPreferenceClickListener { true }
        }
    }

    private val saveMegolmStartForActivityResult = registerStartForActivityResult {
        val uri = it.data?.data ?: return@registerStartForActivityResult
        if (it.resultCode == Activity.RESULT_OK) {
            ExportKeysDialog().show(requireActivity(), object : ExportKeysDialog.ExportKeyDialogListener {
                override fun onPassphrase(passphrase: String) {
                    displayLoadingView()

                    KeysExporter(session)
                            .export(requireContext(),
                                    passphrase,
                                    uri,
                                    object : MatrixCallback<Boolean> {
                                        override fun onSuccess(data: Boolean) {
                                            if (data) {
                                                requireActivity().toast(getString(R.string.encryption_exported_successfully))
                                            } else {
                                                requireActivity().toast(getString(R.string.unexpected_error))
                                            }
                                            hideLoadingView()
                                        }

                                        override fun onFailure(failure: Throwable) {
                                            onCommonDone(failure.localizedMessage)
                                        }
                                    })
                }
            })
        }
    }

    private val pinActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            doOpenPinCodePreferenceScreen()
        }
    }

    private val importKeysActivityResultLauncher = registerStartForActivityResult {
        val data = it.data ?: return@registerStartForActivityResult
        if (it.resultCode == Activity.RESULT_OK) {
            importKeys(data)
        }
    }

    private fun openPinCodePreferenceScreen() {
        lifecycleScope.launchWhenResumed {
            val hasPinCode = pinCodeStore.hasEncodedPin()
            if (hasPinCode) {
                navigator.openPinCode(
                        requireContext(),
                        pinActivityResultLauncher,
                        PinMode.AUTH)
            } else {
                doOpenPinCodePreferenceScreen()
            }
        }
    }

    private fun doOpenPinCodePreferenceScreen() {
        (vectorActivity as? VectorSettingsActivity)?.navigateTo(VectorSettingsPinFragment::class.java)
    }

    private fun refreshKeysManagementSection() {
        // If crypto is not enabled parent section will be removed
        // TODO notice that this will not work when no network
        manageBackupPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            context?.let {
                startActivity(KeysBackupManageActivity.intent(it))
            }
            false
        }

        exportPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            queryExportKeys(activeSessionHolder.getSafeActiveSession()?.myUserId ?: "", saveMegolmStartForActivityResult)
            true
        }

        importPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            importKeys()
            true
        }
    }

    /**
     * Manage the e2e keys import.
     */
    @SuppressLint("NewApi")
    private fun importKeys() {
        openFileSelection(
                requireActivity(),
                importKeysActivityResultLauncher,
                false,
                0
        )
    }

    /**
     * Manage the e2e keys import.
     *
     * @param intent the intent result
     */
    private fun importKeys(intent: Intent) {
        val sharedDataItems = analyseIntent(intent)
        val thisActivity = activity

        if (sharedDataItems.isNotEmpty() && thisActivity != null) {
            val sharedDataItem = sharedDataItems[0]

            val uri = when (sharedDataItem) {
                is ExternalIntentData.IntentDataUri      -> sharedDataItem.uri
                is ExternalIntentData.IntentDataClipData -> sharedDataItem.clipDataItem.uri
                else                                     -> null
            }

            val mimetype = when (sharedDataItem) {
                is ExternalIntentData.IntentDataClipData -> sharedDataItem.mimeType
                else                                     -> null
            }

            if (uri == null) {
                return
            }

            val appContext = thisActivity.applicationContext

            val filename = getFilenameFromUri(appContext, uri)

            val dialogLayout = thisActivity.layoutInflater.inflate(R.layout.dialog_import_e2e_keys, null)

            val textView = dialogLayout.findViewById<TextView>(R.id.dialog_e2e_keys_passphrase_filename)

            if (filename.isNullOrBlank()) {
                textView.isVisible = false
            } else {
                textView.isVisible = true
                textView.text = getString(R.string.import_e2e_keys_from_file, filename)
            }

            val builder = AlertDialog.Builder(thisActivity)
                    .setTitle(R.string.encryption_import_room_keys)
                    .setView(dialogLayout)

            val passPhraseEditText = dialogLayout.findViewById<TextInputEditText>(R.id.dialog_e2e_keys_passphrase_edit_text)
            val importButton = dialogLayout.findViewById<Button>(R.id.dialog_e2e_keys_import_button)

            val showPassword = dialogLayout.findViewById<ImageView>(R.id.importDialogShowPassword)
            var passwordVisible = false

            showPassword.setOnClickListener {
                passwordVisible = !passwordVisible
                passPhraseEditText.showPassword(passwordVisible)
                showPassword.setImageResource(if (passwordVisible) R.drawable.ic_eye_closed else R.drawable.ic_eye)
            }

            passPhraseEditText.addTextChangedListener(object : SimpleTextWatcher() {
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    importButton.isEnabled = !passPhraseEditText.text.isNullOrEmpty()
                }
            })

            val importDialog = builder.show()

            importButton.setOnClickListener {
                val password = passPhraseEditText.text.toString()

                displayLoadingView()

                KeysImporter(session)
                        .import(requireContext(),
                                uri,
                                mimetype,
                                password,
                                object : MatrixCallback<ImportRoomKeysResult> {
                                    override fun onSuccess(data: ImportRoomKeysResult) {
                                        if (!isAdded) {
                                            return
                                        }

                                        hideLoadingView()

                                        AlertDialog.Builder(thisActivity)
                                                .setMessage(resources.getQuantityString(R.plurals.encryption_import_room_keys_success,
                                                        data.successfullyNumberOfImportedKeys,
                                                        data.successfullyNumberOfImportedKeys,
                                                        data.totalNumberOfKeys))
                                                .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
                                                .show()
                                    }

                                    override fun onFailure(failure: Throwable) {
                                        appContext.toast(failure.localizedMessage ?: getString(R.string.unexpected_error))
                                        hideLoadingView()
                                    }
                                })

                importDialog.dismiss()
            }
        }
    }

    // ==============================================================================================================
    // Cryptography
    // ==============================================================================================================

    /**
     * Build the cryptography preference section.
     */
    private fun refreshCryptographyPreference(devices: List<DeviceInfo>) {
        showDeviceListPref.isEnabled = devices.isNotEmpty()
        showDeviceListPref.summary = resources.getQuantityString(R.plurals.settings_active_sessions_count, devices.size, devices.size)

        val userId = session.myUserId
        val deviceId = session.sessionParams.deviceId

        val aMyDeviceInfo = devices.find { it.deviceId == deviceId }

        // crypto section: device name
        if (aMyDeviceInfo != null) {
            cryptoInfoDeviceNamePreference.summary = aMyDeviceInfo.displayName

            cryptoInfoDeviceNamePreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                copyToClipboard(requireActivity(), aMyDeviceInfo.displayName ?: "")
                true
            }
        }

        // crypto section: device ID
        if (!deviceId.isNullOrEmpty()) {
            cryptoInfoDeviceIdPreference.summary = deviceId

            cryptoInfoDeviceIdPreference.setOnPreferenceClickListener {
                copyToClipboard(requireActivity(), deviceId)
                true
            }
        }

        // crypto section: device key (fingerprint)
        val deviceInfo = session.cryptoService().getDeviceInfo(userId, deviceId)

        val fingerprint = deviceInfo?.fingerprint()
        if (fingerprint?.isNotEmpty() == true) {
            cryptoInfoDeviceKeyPreference.summary = deviceInfo.getFingerprintHumanReadable()

            cryptoInfoDeviceKeyPreference.setOnPreferenceClickListener {
                copyToClipboard(requireActivity(), fingerprint)
                true
            }
        }

        sendToUnverifiedDevicesPref.isChecked = session.cryptoService().getGlobalBlacklistUnverifiedDevices()

        sendToUnverifiedDevicesPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            session.cryptoService().setGlobalBlacklistUnverifiedDevices(sendToUnverifiedDevicesPref.isChecked)

            true
        }
    }

    // ==============================================================================================================
    // devices list
    // ==============================================================================================================

    private fun refreshMyDevice() {
        session.cryptoService().getUserDevices(session.myUserId).map {
            DeviceInfo(
                    userId = session.myUserId,
                    deviceId = it.deviceId,
                    displayName = it.displayName()
            )
        }.let {
            refreshCryptographyPreference(it)
        }
        // TODO Move to a ViewModel...
        session.cryptoService().fetchDevicesList(object : MatrixCallback<DevicesListResponse> {
            override fun onSuccess(data: DevicesListResponse) {
                if (isAdded) {
                    refreshCryptographyPreference(data.devices.orEmpty())
                }
            }

            override fun onFailure(failure: Throwable) {
                if (isAdded) {
                    refreshCryptographyPreference(emptyList())
                }
            }
        })
    }
}

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

@file:Suppress("UNUSED_VARIABLE", "UNUSED_ANONYMOUS_PARAMETER", "UNUSED_PARAMETER")

package im.vector.app.features.settings

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.cache.DiskCache
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.showPassword
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.TextUtils
import im.vector.app.core.utils.getSizeOfFiles
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogChangePasswordBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.workers.signout.SignOutUiWorker
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.failure.isInvalidPassword
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerConfig
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.rx.rx
import org.matrix.android.sdk.rx.unwrap
import java.io.File
import java.util.UUID
import javax.inject.Inject

class VectorSettingsGeneralFragment @Inject constructor(
        colorProvider: ColorProvider
):
        VectorSettingsBaseFragment(),
        GalleryOrCameraDialogHelper.Listener {

    override var titleRes = R.string.settings_general_title
    override val preferenceXmlRes = R.xml.vector_settings_general

    private val galleryOrCameraDialogHelper = GalleryOrCameraDialogHelper(this, colorProvider)

    private val mUserSettingsCategory by lazy {
        findPreference<PreferenceCategory>(VectorPreferences.SETTINGS_USER_SETTINGS_PREFERENCE_KEY)!!
    }
    private val mUserAvatarPreference by lazy {
        findPreference<UserAvatarPreference>(VectorPreferences.SETTINGS_PROFILE_PICTURE_PREFERENCE_KEY)!!
    }
    private val mDisplayNamePreference by lazy {
        findPreference<EditTextPreference>("SETTINGS_DISPLAY_NAME_PREFERENCE_KEY")!!
    }
    private val mPasswordPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CHANGE_PASSWORD_PREFERENCE_KEY)!!
    }
    private val mIdentityServerPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_IDENTITY_SERVER_PREFERENCE_KEY)!!
    }

    // Local contacts
    private val mContactSettingsCategory by lazy {
        findPreference<PreferenceCategory>(VectorPreferences.SETTINGS_CONTACT_PREFERENCE_KEYS)!!
    }

    private val mContactPhonebookCountryPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CONTACTS_PHONEBOOK_COUNTRY_PREFERENCE_KEY)!!
    }

    private val integrationServiceListener = object : IntegrationManagerService.Listener {
        override fun onConfigurationChanged(configs: List<IntegrationManagerConfig>) {
            refreshIntegrationManagerSettings()
        }

        override fun onIsEnabledChanged(enabled: Boolean) {
            refreshIntegrationManagerSettings()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeUserAvatar()
        observeUserDisplayName()
    }

    private fun observeUserAvatar() {
        session.rx()
                .liveUser(session.myUserId)
                .unwrap()
                .distinctUntilChanged { user -> user.avatarUrl }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { mUserAvatarPreference.refreshAvatar(it) }
                .disposeOnDestroyView()
    }

    private fun observeUserDisplayName() {
        session.rx()
                .liveUser(session.myUserId)
                .unwrap()
                .map { it.displayName ?: "" }
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { displayName ->
                    mDisplayNamePreference.let {
                        it.summary = displayName
                        it.text = displayName
                    }
                }
                .disposeOnDestroyView()
    }

    override fun bindPref() {
        // Avatar
        mUserAvatarPreference.let {
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                galleryOrCameraDialogHelper.show()
                false
            }
        }

        // Display name
        mDisplayNamePreference.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                newValue
                        ?.let { value -> (value as? String)?.trim() }
                        ?.let { value -> onDisplayNameChanged(value) }
                false
            }
        }

        // Password
        // Hide the preference if password can not be updated
        if (session.getHomeServerCapabilities().canChangePassword) {
            mPasswordPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                onPasswordUpdateClick()
                false
            }
        } else {
            mPasswordPreference.isVisible = false
        }

        // Advanced settings

        // user account
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_LOGGED_IN_PREFERENCE_KEY)!!
                .summary = session.myUserId

        // home server
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_HOME_SERVER_PREFERENCE_KEY)!!
                .summary = session.sessionParams.homeServerUrl

        // Contacts
        setContactsPreferences()

        // clear cache
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CLEAR_CACHE_PREFERENCE_KEY)!!.let {
            /*
            TODO
            MXSession.getApplicationSizeCaches(activity, object : SimpleApiCallback<Long>() {
                override fun onSuccess(size: Long) {
                    if (null != activity) {
                        it.summary = TextUtils.formatFileSize(activity, size)
                    }
                }
            })
             */

            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                displayLoadingView()
                MainActivity.restartApp(requireActivity(), MainActivityArgs(clearCache = true))
                false
            }
        }

        (findPreference(VectorPreferences.SETTINGS_ALLOW_INTEGRATIONS_KEY) as? VectorSwitchPreference)?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                // Disable it while updating the state, will be re-enabled by the account data listener.
                it.isEnabled = false
                lifecycleScope.launch {
                    session.integrationManagerService().setIntegrationEnabled(newValue as Boolean)
                }
                true
            }
        }

        // clear medias cache
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CLEAR_MEDIA_CACHE_PREFERENCE_KEY)!!.let {
            val size = getSizeOfFiles(File(requireContext().cacheDir, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR)) + session.fileService().getCacheSize()

            it.summary = TextUtils.formatFileSize(requireContext(), size.toLong())

            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                lifecycleScope.launch(Dispatchers.Main) {
                    // On UI Thread
                    displayLoadingView()

                    Glide.get(requireContext()).clearMemory()
                    session.fileService().clearCache()

                    var newSize: Int

                    withContext(Dispatchers.IO) {
                        // On BG thread
                        Glide.get(requireContext()).clearDiskCache()

                        newSize = getSizeOfFiles(File(requireContext().cacheDir, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR))
                        newSize += session.fileService().getCacheSize()
                    }

                    it.summary = TextUtils.formatFileSize(requireContext(), newSize.toLong())

                    hideLoadingView()
                }

                false
            }
        }

        // Sign out
        findPreference<VectorPreference>("SETTINGS_SIGN_OUT_KEY")!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity?.let {
                SignOutUiWorker(requireActivity()).perform()
            }

            false
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh identity server summary
        mIdentityServerPreference.summary = session.identityService().getCurrentIdentityServerUrl() ?: getString(R.string.identity_server_not_defined)
        refreshIntegrationManagerSettings()
        session.integrationManagerService().addListener(integrationServiceListener)
    }

    override fun onPause() {
        super.onPause()
        session.integrationManagerService().removeListener(integrationServiceListener)
    }

    private fun refreshIntegrationManagerSettings() {
        val integrationAllowed = session.integrationManagerService().isIntegrationEnabled()
        (findPreference<SwitchPreference>(VectorPreferences.SETTINGS_ALLOW_INTEGRATIONS_KEY))!!.let {
            val savedListener = it.onPreferenceChangeListener
            it.onPreferenceChangeListener = null
            it.isChecked = integrationAllowed
            it.isEnabled = true
            it.onPreferenceChangeListener = savedListener
        }
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTEGRATION_MANAGER_UI_URL_KEY)!!.let {
            if (integrationAllowed) {
                it.summary = session.integrationManagerService().getPreferredConfig().uiUrl
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }
    }

    override fun onImageReady(uri: Uri?) {
        if (uri != null) {
            uploadAvatar(uri)
        } else {
            Toast.makeText(requireContext(), "Cannot retrieve cropped value", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadAvatar(uri: Uri) {
        displayLoadingView()

        lifecycleScope.launch {
            val result = runCatching {
                session.updateAvatar(session.myUserId, uri, getFilenameFromUri(context, uri) ?: UUID.randomUUID().toString())
            }
            if (!isAdded) return@launch
            onCommonDone(result.fold({ null }, { it.localizedMessage }))
        }
    }

    // ==============================================================================================================
    // contacts management
    // ==============================================================================================================

    private fun setContactsPreferences() {
        /* TODO
        // Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // on Android >= 23, use the system one
            mContactSettingsCategory.removePreference(findPreference(ContactsManager.CONTACTS_BOOK_ACCESS_KEY))
        }
        // Phonebook country
        mContactPhonebookCountryPreference.summary = PhoneNumberUtils.getHumanCountryCode(PhoneNumberUtils.getCountryCode(activity))

        mContactPhonebookCountryPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val intent = CountryPickerActivity.getIntent(activity, true)
            startActivityForResult(intent, REQUEST_PHONEBOOK_COUNTRY)
            true
        }
        */
    }

    // ==============================================================================================================
    // Phone number management
    // ==============================================================================================================

    /**
     * Update the password.
     */
    private fun onPasswordUpdateClick() {
        activity?.let { activity ->
            val view: ViewGroup = activity.layoutInflater.inflate(R.layout.dialog_change_password, null) as ViewGroup
            val views = DialogChangePasswordBinding.bind(view)

            var passwordShown = false

            views.changePasswordShowPasswords.setOnClickListener {
                passwordShown = !passwordShown

                views.changePasswordOldPwdText.showPassword(passwordShown)
                views.changePasswordNewPwdText.showPassword(passwordShown)
                views.changePasswordConfirmNewPwdText.showPassword(passwordShown)

                views.changePasswordShowPasswords.render(passwordShown)
            }

            val dialog = AlertDialog.Builder(activity)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(R.string.settings_change_password, null)
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener {
                        view.hideKeyboard()
                    }
                    .create()

            dialog.setOnShowListener {
                val updateButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                updateButton.isEnabled = false

                fun updateUi() {
                    val oldPwd = views.changePasswordOldPwdText.text.toString()
                    val newPwd = views.changePasswordNewPwdText.text.toString()
                    val newConfirmPwd = views.changePasswordConfirmNewPwdText.text.toString()

                    updateButton.isEnabled = oldPwd.isNotEmpty() && newPwd.isNotEmpty() && newPwd == newConfirmPwd

                    if (newPwd.isNotEmpty() && newConfirmPwd.isNotEmpty() && newPwd != newConfirmPwd) {
                        views.changePasswordConfirmNewPwdTil.error = getString(R.string.passwords_do_not_match)
                    }
                }

                views.changePasswordOldPwdText.addTextChangedListener(object : SimpleTextWatcher() {
                    override fun afterTextChanged(s: Editable) {
                        views.changePasswordOldPwdTil.error = null
                        updateUi()
                    }
                })

                views.changePasswordNewPwdText.addTextChangedListener(object : SimpleTextWatcher() {
                    override fun afterTextChanged(s: Editable) {
                        views.changePasswordConfirmNewPwdTil.error = null
                        updateUi()
                    }
                })

                views.changePasswordConfirmNewPwdText.addTextChangedListener(object : SimpleTextWatcher() {
                    override fun afterTextChanged(s: Editable) {
                        views.changePasswordConfirmNewPwdTil.error = null
                        updateUi()
                    }
                })

                fun showPasswordLoadingView(toShow: Boolean) {
                    if (toShow) {
                        views.changePasswordShowPasswords.isEnabled = false
                        views.changePasswordOldPwdText.isEnabled = false
                        views.changePasswordNewPwdText.isEnabled = false
                        views.changePasswordConfirmNewPwdText.isEnabled = false
                        views.changePasswordLoader.isVisible = true
                        updateButton.isEnabled = false
                        cancelButton.isEnabled = false
                    } else {
                        views.changePasswordShowPasswords.isEnabled = true
                        views.changePasswordOldPwdText.isEnabled = true
                        views.changePasswordNewPwdText.isEnabled = true
                        views.changePasswordConfirmNewPwdText.isEnabled = true
                        views.changePasswordLoader.isVisible = false
                        updateButton.isEnabled = true
                        cancelButton.isEnabled = true
                    }
                }

                updateButton.setOnClickListener {
                    if (passwordShown) {
                        // Hide passwords during processing
                        views.changePasswordShowPasswords.performClick()
                    }

                    view.hideKeyboard()

                    val oldPwd = views.changePasswordOldPwdText.text.toString()
                    val newPwd = views.changePasswordNewPwdText.text.toString()

                    showPasswordLoadingView(true)
                    lifecycleScope.launch {
                        val result = runCatching {
                            session.changePassword(oldPwd, newPwd)
                        }
                        if (!isAdded) {
                            return@launch
                        }
                        showPasswordLoadingView(false)
                        result.fold({
                            dialog.dismiss()
                            activity.toast(R.string.settings_password_updated)
                        }, { failure ->
                            if (failure.isInvalidPassword()) {
                                views.changePasswordOldPwdTil.error = getString(R.string.settings_fail_to_update_password_invalid_current_password)
                            } else {
                                views.changePasswordOldPwdTil.error = getString(R.string.settings_fail_to_update_password)
                            }
                        })
                    }
                }
            }
            dialog.show()
        }
    }

    /**
     * Update the displayname.
     */
    private fun onDisplayNameChanged(value: String) {
        val currentDisplayName = session.getUser(session.myUserId)?.displayName ?: ""
        if (currentDisplayName != value) {
            displayLoadingView()

            lifecycleScope.launch {
                val result = runCatching { session.setDisplayName(session.myUserId, value) }
                if (!isAdded) return@launch
                result.fold(
                        {
                            // refresh the settings value
                            mDisplayNamePreference.summary = value
                            mDisplayNamePreference.text = value
                            onCommonDone(null)
                        },
                        {
                            onCommonDone(it.localizedMessage)
                        }
                )
            }
        }
    }
}

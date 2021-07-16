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

package im.vector.app.features.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Parcelable
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.preference.VectorEditTextPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.utils.isIgnoringBatteryOptimizations
import im.vector.app.core.utils.requestDisablingBatteryOptimization
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.core.pushers.UPHelper
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.pushrules.RuleIds
import org.matrix.android.sdk.api.pushrules.RuleKind
import timber.log.Timber
import javax.inject.Inject

// Referenced in vector_settings_preferences_root.xml
class VectorSettingsNotificationPreferenceFragment @Inject constructor(
        private val pushManager: PushersManager,
        private val activeSessionHolder: ActiveSessionHolder,
        private val vectorPreferences: VectorPreferences
) : VectorSettingsBaseFragment(),
        BackgroundSyncModeChooserDialog.InteractionListener {

    override var titleRes: Int = R.string.settings_notifications
    override val preferenceXmlRes = R.xml.vector_settings_notifications

    private var interactionListener: VectorSettingsFragmentInteractionListener? = null

    override fun bindPref() {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_ENABLE_ALL_NOTIF_PREFERENCE_KEY)!!.let { pref ->
            val pushRuleService = session
            val mRuleMaster = pushRuleService.getPushRules().getAllRules()
                    .find { it.ruleId == RuleIds.RULE_ID_DISABLE_ALL }

            if (mRuleMaster == null) {
                // The homeserver does not support RULE_ID_DISABLE_ALL, so hide the preference
                pref.isVisible = false
                return
            }

            val areNotifEnabledAtAccountLevel = !mRuleMaster.enabled
            (pref as SwitchPreference).isChecked = areNotifEnabledAtAccountLevel
        }

        findPreference<VectorPreference>(VectorPreferences.SETTINGS_FDROID_BACKGROUND_SYNC_MODE)?.let {
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val initialMode = vectorPreferences.getFdroidSyncBackgroundMode()
                val dialogFragment = BackgroundSyncModeChooserDialog.newInstance(initialMode)
                dialogFragment.interactionListener = this
                activity?.supportFragmentManager?.let { fm ->
                    dialogFragment.show(fm, "syncDialog")
                }
                true
            }
        }

        findPreference<VectorEditTextPreference>(VectorPreferences.SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY)?.let {
            it.isEnabled = vectorPreferences.isBackgroundSyncEnabled()
            it.summary = secondsToText(vectorPreferences.backgroundSyncTimeOut())
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue is String) {
                    val syncTimeout = tryOrNull { Integer.parseInt(newValue) } ?: BackgroundSyncMode.DEFAULT_SYNC_TIMEOUT_SECONDS
                    vectorPreferences.setBackgroundSyncTimeout(maxOf(0, syncTimeout))
                    refreshBackgroundSyncPrefs()
                }
                true
            }
        }

        findPreference<VectorEditTextPreference>(VectorPreferences.SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY)?.let {
            it.isEnabled = vectorPreferences.isBackgroundSyncEnabled()
            it.summary = secondsToText(vectorPreferences.backgroundSyncDelay())
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue is String) {
                    val syncDelay = tryOrNull { Integer.parseInt(newValue) } ?: BackgroundSyncMode.DEFAULT_SYNC_DELAY_SECONDS
                    vectorPreferences.setBackgroundSyncDelay(maxOf(0, syncDelay))
                    refreshBackgroundSyncPrefs()
                }
                true
            }
        }

        refreshBackgroundSyncPrefs()

        handleSystemPreference()
    }

    private val batteryStartForActivityResult = registerStartForActivityResult {
        // Noop
    }

    // BackgroundSyncModeChooserDialog.InteractionListener
    override fun onOptionSelected(mode: BackgroundSyncMode) {
        // option has change, need to act
        if (mode == BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_REALTIME) {
            // Important, Battery optim white listing is needed in this mode;
            // Even if using foreground service with foreground notif, it stops to work
            // in doze mode for certain devices :/
            if (!isIgnoringBatteryOptimizations(requireContext())) {
                requestDisablingBatteryOptimization(requireActivity(), batteryStartForActivityResult)
            }
        }
        vectorPreferences.setFdroidSyncBackgroundMode(mode)
        refreshBackgroundSyncPrefs()
    }

    private fun refreshBackgroundSyncPrefs() {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_FDROID_BACKGROUND_SYNC_MODE)?.let {
            it.summary = when (vectorPreferences.getFdroidSyncBackgroundMode()) {
                BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_BATTERY  -> getString(R.string.settings_background_fdroid_sync_mode_battery)
                BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_REALTIME -> getString(R.string.settings_background_fdroid_sync_mode_real_time)
                BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_DISABLED     -> getString(R.string.settings_background_fdroid_sync_mode_disabled)
            }
        }

        findPreference<VectorPreferenceCategory>(VectorPreferences.SETTINGS_BACKGROUND_SYNC_PREFERENCE_KEY)?.let {
            it.isVisible = !UPHelper.hasEndpoint(requireContext())
        }

        findPreference<VectorEditTextPreference>(VectorPreferences.SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY)?.let {
            it.isEnabled = vectorPreferences.isBackgroundSyncEnabled()
            it.summary = secondsToText(vectorPreferences.backgroundSyncTimeOut())
        }
        findPreference<VectorEditTextPreference>(VectorPreferences.SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY)?.let {
            it.isEnabled = vectorPreferences.isBackgroundSyncEnabled()
            it.summary = secondsToText(vectorPreferences.backgroundSyncDelay())
        }
    }

    /**
     * Convert a delay in seconds to string
     *
     * @param seconds the delay in seconds
     * @return the text
     */
    private fun secondsToText(seconds: Int): String {
        return resources.getQuantityString(R.plurals.seconds, seconds, seconds)
    }

    private fun handleSystemPreference() {
        val callNotificationsSystemOptions = findPreference<VectorPreference>(VectorPreferences.SETTINGS_SYSTEM_CALL_NOTIFICATION_PREFERENCE_KEY)!!
        if (NotificationUtils.supportNotificationChannels()) {
            callNotificationsSystemOptions.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                NotificationUtils.openSystemSettingsForCallCategory(this)
                false
            }
        } else {
            callNotificationsSystemOptions.isVisible = false
        }

        val noisyNotificationsSystemOptions = findPreference<VectorPreference>(VectorPreferences.SETTINGS_SYSTEM_NOISY_NOTIFICATION_PREFERENCE_KEY)!!
        if (NotificationUtils.supportNotificationChannels()) {
            noisyNotificationsSystemOptions.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                NotificationUtils.openSystemSettingsForNoisyCategory(this)
                false
            }
        } else {
            noisyNotificationsSystemOptions.isVisible = false
        }

        val silentNotificationsSystemOptions = findPreference<VectorPreference>(VectorPreferences.SETTINGS_SYSTEM_SILENT_NOTIFICATION_PREFERENCE_KEY)!!
        if (NotificationUtils.supportNotificationChannels()) {
            silentNotificationsSystemOptions.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                NotificationUtils.openSystemSettingsForSilentCategory(this)
                false
            }
        } else {
            silentNotificationsSystemOptions.isVisible = false
        }

        // Ringtone
        val ringtonePreference = findPreference<VectorPreference>(VectorPreferences.SETTINGS_NOTIFICATION_RINGTONE_SELECTION_PREFERENCE_KEY)!!

        if (NotificationUtils.supportNotificationChannels()) {
            ringtonePreference.isVisible = false
        } else {
            ringtonePreference.summary = vectorPreferences.getNotificationRingToneName()
            ringtonePreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)

                if (null != vectorPreferences.getNotificationRingTone()) {
                    intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, vectorPreferences.getNotificationRingTone())
                }

                ringtoneStartForActivityResult.launch(intent)
                false
            }
        }
    }

    private val ringtoneStartForActivityResult = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            vectorPreferences.setNotificationRingTone(activityResult.data?.getParcelableExtra<Parcelable>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as Uri?)

            // test if the selected ring tone can be played
            val notificationRingToneName = vectorPreferences.getNotificationRingToneName()
            if (null != notificationRingToneName) {
                vectorPreferences.setNotificationRingTone(vectorPreferences.getNotificationRingTone())
                findPreference<VectorPreference>(VectorPreferences.SETTINGS_NOTIFICATION_RINGTONE_SELECTION_PREFERENCE_KEY)!!
                        .summary = notificationRingToneName
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activeSessionHolder.getSafeActiveSession()?.refreshPushers()

        interactionListener?.requestedKeyToHighlight()?.let { key ->
            interactionListener?.requestHighlightPreferenceKeyOnResume(null)
            val preference = findPreference<VectorSwitchPreference>(key)
            preference?.isHighlighted = true
        }

        refreshPref()
    }

    private fun refreshPref() {
        // This pref may have change from troubleshoot pref fragment
        if (!UPHelper.hasEndpoint(requireContext())) {
            findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_START_ON_BOOT_PREFERENCE_KEY)
                    ?.isChecked = vectorPreferences.autoStartOnBoot()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is VectorSettingsFragmentInteractionListener) {
            interactionListener = context
        }
        (activity?.supportFragmentManager
                ?.findFragmentByTag("syncDialog") as BackgroundSyncModeChooserDialog?)
                ?.interactionListener = this
    }

    override fun onDetach() {
        interactionListener = null
        super.onDetach()
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean {
        return when (preference?.key) {
            VectorPreferences.SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY -> {
                updateEnabledForDevice(preference)
                true
            }
            VectorPreferences.SETTINGS_ENABLE_ALL_NOTIF_PREFERENCE_KEY   -> {
                updateEnabledForAccount(preference)
                true
            }
            else                                                         -> {
                return super.onPreferenceTreeClick(preference)
            }
        }
    }

    private fun updateEnabledForDevice(preference: Preference?) {
        val switchPref = preference as SwitchPreference
        if (switchPref.isChecked) {
            UPHelper.registerUnifiedPush(requireContext())
        } else {
            UPHelper.getUpEndpoint(requireContext())?.let {
                lifecycleScope.launch {
                    runCatching {
                        try {
                            pushManager.unregisterPusher(requireContext(), it)
                        } catch (e: Exception) {
                            Timber.d("Probably unregistering a non existant pusher")
                        }
                        try {
                            UPHelper.unregister(requireContext())
                        } catch (e: Exception) {
                            Timber.d("Probably unregistering to a non-saved distributor")
                        }
                    }
                            .fold(
                                    { session.refreshPushers() },
                                    {
                                        if (!isAdded) {
                                            return@fold
                                        }
                                        // revert the check box
                                        switchPref.isChecked = !switchPref.isChecked
                                        Toast.makeText(activity, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                                    }
                            )
                }
            }
        }
    }

    private fun updateEnabledForAccount(preference: Preference?) {
        val pushRuleService = session
        val switchPref = preference as SwitchPreference
        pushRuleService.getPushRules().getAllRules()
                .find { it.ruleId == RuleIds.RULE_ID_DISABLE_ALL }
                ?.let {
                    // Trick, we must enable this room to disable notifications
                    lifecycleScope.launch {
                        try {
                            pushRuleService.updatePushRuleEnableStatus(RuleKind.OVERRIDE,
                                    it,
                                    !switchPref.isChecked)
                            // Push rules will be updated from the sync
                        } catch (failure: Throwable) {
                            if (!isAdded) {
                                return@launch
                            }

                            // revert the check box
                            switchPref.isChecked = !switchPref.isChecked
                            Toast.makeText(activity, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
    }
}

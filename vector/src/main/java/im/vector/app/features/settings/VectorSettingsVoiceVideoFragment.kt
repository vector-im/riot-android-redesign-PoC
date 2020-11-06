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
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import im.vector.app.R
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.core.utils.getCallRingtoneName
import im.vector.app.core.utils.getCallRingtoneUri
import im.vector.app.core.utils.setCallRingtoneUri
import im.vector.app.core.utils.setUseRiotDefaultRingtone

class VectorSettingsVoiceVideoFragment : VectorSettingsBaseFragment() {

    override var titleRes = R.string.preference_voice_and_video
    override val preferenceXmlRes = R.xml.vector_settings_voice_video

    private val mUseRiotCallRingtonePreference by lazy {
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_CALL_RINGTONE_USE_RIOT_PREFERENCE_KEY)!!
    }
    private val mCallRingtonePreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CALL_RINGTONE_URI_PREFERENCE_KEY)!!
    }

    override fun bindPref() {
        // Incoming call sounds
        mUseRiotCallRingtonePreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity?.let { setUseRiotDefaultRingtone(it, mUseRiotCallRingtonePreference.isChecked) }
            false
        }

        mCallRingtonePreference.let {
            activity?.let { activity -> it.summary = getCallRingtoneName(activity) }
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                displayRingtonePicker()
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SettingsView("voice_and_video"))
    }

    private val ringtoneStartForActivityResult = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val callRingtoneUri: Uri? = activityResult.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val thisActivity = activity
            if (callRingtoneUri != null && thisActivity != null) {
                setCallRingtoneUri(thisActivity, callRingtoneUri)
                mCallRingtonePreference.summary = getCallRingtoneName(thisActivity)
            }
        }
    }

    private fun displayRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.settings_call_ringtone_dialog_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            activity?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, getCallRingtoneUri(it)) }
        }
        ringtoneStartForActivityResult.launch(intent)
    }
}

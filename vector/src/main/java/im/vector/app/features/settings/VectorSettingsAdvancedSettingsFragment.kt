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

import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.features.rageshake.RageShake

class VectorSettingsAdvancedSettingsFragment : VectorSettingsBaseFragment() {

    override var titleRes = R.string.settings_advanced_settings
    override val preferenceXmlRes = R.xml.vector_settings_advanced_settings

    private var rageshake: RageShake? = null

    override fun onResume() {
        super.onResume()

        rageshake = (activity as? VectorBaseActivity)?.rageShake
        rageshake?.interceptor = {
            (activity as? VectorBaseActivity)?.showSnackbar(getString(R.string.rageshake_detected))
        }
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SettingsView("advanced"))
    }

    override fun onPause() {
        super.onPause()
        rageshake?.interceptor = null
        rageshake = null
    }

    override fun bindPref() {
        val isRageShakeAvailable = RageShake.isAvailable(requireContext())

        if (isRageShakeAvailable) {
            findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_USE_RAGE_SHAKE_KEY)!!
                    .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->

                if (newValue as? Boolean == true) {
                    rageshake?.start()
                } else {
                    rageshake?.stop()
                }

                true
            }

            findPreference<SeekBarPreference>(VectorPreferences.SETTINGS_RAGE_SHAKE_DETECTION_THRESHOLD_KEY)!!
                    .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                (activity as? VectorBaseActivity)?.let {
                    val newValueAsInt = newValue as? Int ?: return@OnPreferenceChangeListener true

                    rageshake?.setSensitivity(newValueAsInt)
                }

                true
            }
        } else {
            findPreference<VectorSwitchPreference>("SETTINGS_RAGE_SHAKE_CATEGORY_KEY")!!.isVisible = false
        }
    }
}

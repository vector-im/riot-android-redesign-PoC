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
import android.widget.CheckedTextView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import im.vector.app.R
import im.vector.app.core.extensions.restart
import im.vector.app.core.preference.VectorListPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.features.configuration.VectorConfiguration
import im.vector.app.features.themes.ThemeUtils
import javax.inject.Inject

class VectorSettingsPreferencesFragment @Inject constructor(
        private val vectorConfiguration: VectorConfiguration,
        private val vectorPreferences: VectorPreferences
) : VectorSettingsBaseFragment() {

    override var titleRes = R.string.settings_preferences
    override val preferenceXmlRes = R.xml.vector_settings_preferences

    private val selectedLanguagePreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTERFACE_LANGUAGE_PREFERENCE_KEY)!!
    }
    private val textSizePreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTERFACE_TEXT_SIZE_KEY)!!
    }

    override fun onResume() {
        super.onResume()
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SettingsView("preferences"))
    }

    override fun bindPref() {
        // user interface preferences
        setUserInterfacePreferences()

        // Themes
        findPreference<VectorListPreference>(ThemeUtils.APPLICATION_THEME_KEY)!!
                .onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                ThemeUtils.setApplicationTheme(requireContext().applicationContext, newValue)
                // Restart the Activity
                activity?.restart()
                true
            } else {
                false
            }
        }

        // Url preview
        findPreference<SwitchPreference>(VectorPreferences.SETTINGS_SHOW_URL_PREVIEW_KEY)!!.let {
            /*
            TODO
            it.isChecked = session.isURLPreviewEnabled

            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                if (null != newValue && newValue as Boolean != session.isURLPreviewEnabled) {
                    displayLoadingView()
                    session.setURLPreviewStatus(newValue, object : MatrixCallback<Unit> {
                        override fun onSuccess(info: Void?) {
                            it.isChecked = session.isURLPreviewEnabled
                            hideLoadingView()
                        }

                        private fun onError(errorMessage: String) {
                            activity?.toast(errorMessage)

                            onSuccess(null)
                        }

                        override fun onNetworkError(e: Exception) {
                            onError(e.localizedMessage)
                        }

                        override fun onMatrixError(e: MatrixError) {
                            onError(e.localizedMessage)
                        }

                        override fun onUnexpectedError(e: Exception) {
                            onError(e.localizedMessage)
                        }
                    })
                }

                false
            }
            */
        }

        // update keep medias period
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_MEDIA_SAVING_PERIOD_KEY)!!.let {
            it.summary = vectorPreferences.getSelectedMediasSavingPeriodString()

            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                context?.let { context: Context ->
                    AlertDialog.Builder(context)
                            .setSingleChoiceItems(R.array.media_saving_choice,
                                    vectorPreferences.getSelectedMediasSavingPeriod()) { d, n ->
                                vectorPreferences.setSelectedMediasSavingPeriod(n)
                                d.cancel()

                                it.summary = vectorPreferences.getSelectedMediasSavingPeriodString()
                            }
                            .show()
                }

                false
            }
        }
    }

    // ==============================================================================================================
    // user interface management
    // ==============================================================================================================

    private fun setUserInterfacePreferences() {
        // Selected language
        selectedLanguagePreference.summary = VectorLocale.localeToLocalisedString(VectorLocale.applicationLocale)

        // Text size
        textSizePreference.summary = getString(FontScale.getFontScaleValue(requireActivity()).nameResId)

        textSizePreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity?.let { displayTextSizeSelection(it) }
            true
        }
    }

    private fun displayTextSizeSelection(activity: Activity) {
        val inflater = activity.layoutInflater
        val layout = inflater.inflate(R.layout.dialog_select_text_size, null)

        val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.font_size)
                .setView(layout)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .show()

        val linearLayout = layout.findViewById<LinearLayout>(R.id.text_selection_group_view)

        val childCount = linearLayout.childCount

        val index = FontScale.getFontScaleValue(activity).index

        for (i in 0 until childCount) {
            val v = linearLayout.getChildAt(i)

            if (v is CheckedTextView) {
                v.isChecked = i == index

                v.setOnClickListener {
                    dialog.dismiss()
                    FontScale.updateFontScale(activity, i)
                    vectorConfiguration.applyToApplicationContext()
                    activity.restart()
                }
            }
        }
    }
}

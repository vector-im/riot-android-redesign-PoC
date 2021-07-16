/*
 * Copyright 2018 New Vector Ltd
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
package im.vector.app.features.settings.troubleshoot

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.isIgnoringBatteryOptimizations
import im.vector.app.core.utils.requestDisablingBatteryOptimization
import javax.inject.Inject

class TestBatteryOptimization @Inject constructor(
        private val context: AppCompatActivity,
        private val stringProvider: StringProvider
) : TroubleshootTest(R.string.settings_troubleshoot_test_battery_title) {

    override fun perform(activityResultLauncher: ActivityResultLauncher<Intent>) {
        if (isIgnoringBatteryOptimizations(context)) {
            description = stringProvider.getString(R.string.settings_troubleshoot_test_battery_success)
            status = TestStatus.SUCCESS
            quickFix = null
        } else {
            description = stringProvider.getString(R.string.settings_troubleshoot_test_battery_failed)
            quickFix = object : TroubleshootQuickFix(R.string.settings_troubleshoot_test_battery_quickfix) {
                override fun doFix() {
                    requestDisablingBatteryOptimization(context, activityResultLauncher)
                }
            }
            status = TestStatus.FAILED
        }
    }
}

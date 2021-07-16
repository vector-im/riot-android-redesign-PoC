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
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject

/**
 * Test that the application is started on boot
 */
class TestAutoStartBoot @Inject constructor(private val vectorPreferences: VectorPreferences,
                                            private val stringProvider: StringProvider)
    : TroubleshootTest(R.string.settings_troubleshoot_test_service_boot_title) {

    override fun perform(activityResultLauncher: ActivityResultLauncher<Intent>) {
        if (vectorPreferences.autoStartOnBoot()) {
            description = stringProvider.getString(R.string.settings_troubleshoot_test_service_boot_success)
            status = TestStatus.SUCCESS
            quickFix = null
        } else {
            description = stringProvider.getString(R.string.settings_troubleshoot_test_service_boot_failed)
            quickFix = object : TroubleshootQuickFix(R.string.settings_troubleshoot_test_service_boot_quickfix) {
                override fun doFix() {
                    vectorPreferences.setAutoStartOnBoot(true)
                    manager?.retry(activityResultLauncher)
                }
            }
            status = TestStatus.FAILED
        }
    }
}

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
import im.vector.app.core.pushers.UPHelper
import im.vector.app.core.resources.StringProvider
import javax.inject.Inject

/*
* Test that app can successfully retrieve a new endpoint
 */
class TestNewEndpoint @Inject constructor(private val context: AppCompatActivity,
                                          private val stringProvider: StringProvider
                                          ) : TroubleshootTest(R.string.settings_troubleshoot_test_endpoint_title) {

    override fun perform(activityResultLauncher: ActivityResultLauncher<Intent>) {
        status = TestStatus.RUNNING

        val endpoint = UPHelper.getUpEndpoint(context)

        if (!endpoint.isNullOrEmpty()) {
            status = TestStatus.SUCCESS
            description = stringProvider.getString(R.string.settings_troubleshoot_test_endpoint_success, endpoint)
        } else {
            status = TestStatus.FAILED
            description = stringProvider.getString(R.string.settings_troubleshoot_test_endpoint_failed)
        }
    }
}

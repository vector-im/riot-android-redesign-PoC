/*
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
package im.vector.app.gplay.features.settings.troubleshoot

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.troubleshoot.TroubleshootTest
import im.vector.app.push.fcm.FcmHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.pushers.PushGatewayFailure
import javax.inject.Inject

/**
 * Test Push by asking the Push Gateway to send a Push back
 */
class TestPushFromPushGateway @Inject constructor(private val context: AppCompatActivity,
                                                  private val stringProvider: StringProvider,
                                                  private val errorFormatter: ErrorFormatter,
                                                  private val pushersManager: PushersManager,
                                                  private val activeSessionHolder: ActiveSessionHolder)
    : TroubleshootTest(R.string.settings_troubleshoot_test_push_loop_title) {

    private var action: Job? = null
    private var pushReceived: Boolean = false

    override fun perform(activityResultLauncher: ActivityResultLauncher<Intent>) {
        pushReceived = false
        val fcmToken = FcmHelper.getFcmToken(context) ?: run {
            status = TestStatus.FAILED
            return
        }
        action = activeSessionHolder.getActiveSession().coroutineScope.launch {
            val result = runCatching { pushersManager.testPush(fcmToken) }

            withContext(Dispatchers.Main) {
                status = result
                        .fold(
                                {
                                    if (pushReceived) {
                                        // Push already received (race condition)
                                        description = stringProvider.getString(R.string.settings_troubleshoot_test_push_loop_success)
                                        TestStatus.SUCCESS
                                    } else {
                                        // Wait for the push to be received
                                        description = stringProvider.getString(R.string.settings_troubleshoot_test_push_loop_waiting_for_push)
                                        TestStatus.RUNNING
                                    }
                                },
                                {
                                    description = if (it is PushGatewayFailure.PusherRejected) {
                                        stringProvider.getString(R.string.settings_troubleshoot_test_push_loop_failed)
                                    } else {
                                        errorFormatter.toHumanReadable(it)
                                    }
                                    TestStatus.FAILED
                                }
                        )
            }
        }
    }

    override fun onPushReceived() {
        pushReceived = true
        description = stringProvider.getString(R.string.settings_troubleshoot_test_push_loop_success)
        status = TestStatus.SUCCESS
    }

    override fun cancel() {
        action?.cancel()
    }
}

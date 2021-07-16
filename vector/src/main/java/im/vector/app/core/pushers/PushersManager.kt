/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.core.pushers

import android.content.Context
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.AppNameProvider
import im.vector.app.core.resources.LocaleProvider
import im.vector.app.core.resources.StringProvider
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

private const val DEFAULT_PUSHER_FILE_TAG = "mobile"

class PushersManager @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val localeProvider: LocaleProvider,
        private val stringProvider: StringProvider,
        private val appNameProvider: AppNameProvider
) {
    suspend fun testPush(context: Context) {
        val currentSession = activeSessionHolder.getActiveSession()

        currentSession.testPush(
                UPHelper.getPushGateway(context)!!,
                getPusherAppId(context),
                UPHelper.getUpEndpoint(context)!!,
                TEST_EVENT_ID
        )
    }

    fun registerPusher(context: Context, pushKey: String, gateway: String): UUID {
        val currentSession = activeSessionHolder.getActiveSession()
        val profileTag = DEFAULT_PUSHER_FILE_TAG + "_" + abs(currentSession.myUserId.hashCode())

        return currentSession.addHttpPusher(
                pushKey, // this is the UnifiedPush endpoint
                getPusherAppId(context),
                profileTag,
                localeProvider.current().language,
                appNameProvider.getAppName(),
                currentSession.sessionParams.deviceId ?: "MOBILE",
                gateway,
                append = false,
                withEventIdOnly = true
        )
    }

    suspend fun unregisterPusher(context: Context, pushKey: String) {
        val currentSession = activeSessionHolder.getSafeActiveSession() ?: return
        currentSession.removeHttpPusher(pushKey, getPusherAppId(context))
    }

    private fun getPusherAppId(context: Context) : String {
        val appId = stringProvider.getString(R.string.pusher_app_id)
        return if (UPHelper.isEmbeddedDistributor(context)) {
            appId
        } else {
            "$appId.up"
        }
    }

    companion object {
        const val TEST_EVENT_ID = "\$THIS_IS_A_FAKE_EVENT_ID"
    }
}

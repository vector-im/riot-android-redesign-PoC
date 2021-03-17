/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.fdroid

import android.content.Context
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.fdroid.receiver.AlarmSyncBroadcastReceiver
import im.vector.app.features.settings.BackgroundSyncMode
import im.vector.app.features.settings.VectorPreferences
import timber.log.Timber

object BackgroundSyncStarter {
    fun start(context: Context, vectorPreferences: VectorPreferences, activeSessionHolder: ActiveSessionHolder) {
        if (vectorPreferences.areNotificationEnabledForDevice()) {
            val activeSession = activeSessionHolder.getSafeActiveSession() ?: return

            when (vectorPreferences.getFdroidSyncBackgroundMode()) {
                BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_BATTERY -> {
                    // we rely on periodic worker
                    Timber.i("## Sync: Work scheduled to periodically sync in ${vectorPreferences.backgroundSyncDelay()}s")
                    activeSession.startAutomaticBackgroundSync(
                            vectorPreferences.backgroundSyncTimeOut().toLong(),
                            true,
                            vectorPreferences.backgroundSyncDelay().toLong()
                    )
                }
                BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_REALTIME -> {
                    // We need to use alarm in this mode
                    AlarmSyncBroadcastReceiver.scheduleAlarm(context, activeSession.sessionId, vectorPreferences.backgroundSyncDelay())
                    Timber.i("## Sync: Alarm scheduled to start syncing")
                }
                BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_DISABLED -> {
                    // we do nothing
                    Timber.i("## Sync: background sync is disabled")
                }
            }
        }
    }
}

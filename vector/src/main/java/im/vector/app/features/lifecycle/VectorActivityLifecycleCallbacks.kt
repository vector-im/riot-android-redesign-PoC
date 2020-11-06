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

package im.vector.app.features.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.features.popup.PopupAlertManager

class VectorActivityLifecycleCallbacks constructor(
        private val popupAlertManager: PopupAlertManager,
        private val analyticsEngine: AnalyticsEngine) : Application.ActivityLifecycleCallbacks {
    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityResumed(activity: Activity) {
        popupAlertManager.onNewActivityDisplayed(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.StartActivity(activity))
    }

    override fun onActivityDestroyed(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityStopped(activity: Activity) {
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.EndActivity(activity))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    }
}

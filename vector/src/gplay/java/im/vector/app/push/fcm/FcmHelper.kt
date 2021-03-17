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
package im.vector.app.push.fcm

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultSharedPreferences
import im.vector.app.core.pushers.PushersManager
import im.vector.app.features.settings.VectorPreferences
import timber.log.Timber

/**
 * This class store the FCM token in SharedPrefs and ensure this token is retrieved.
 * It has an alter ego in the fdroid variant.
 */
object FcmHelper {
    private val PREFS_KEY_FCM_TOKEN = "FCM_TOKEN"

    fun isPushSupported(): Boolean = true

    /**
     * Retrieves the FCM registration token.
     *
     * @return the FCM token or null if not received from FCM
     */
    fun getFcmToken(context: Context): String? {
        return DefaultSharedPreferences.getInstance(context).getString(PREFS_KEY_FCM_TOKEN, null)
    }

    /**
     * Store FCM token to the SharedPrefs
     * TODO Store in realm
     *
     * @param context android context
     * @param token   the token to store
     */
    fun storeFcmToken(context: Context,
                      token: String?) {
        DefaultSharedPreferences.getInstance(context).edit {
            putString(PREFS_KEY_FCM_TOKEN, token)
        }
    }

    /**
     * onNewToken may not be called on application upgrade, so ensure my shared pref is set
     *
     * @param activity the first launch Activity
     */
    fun ensureFcmTokenIsRetrieved(activity: Activity, pushersManager: PushersManager, registerPusher: Boolean) {
        //        if (TextUtils.isEmpty(getFcmToken(activity))) {
        // 'app should always check the device for a compatible Google Play services APK before accessing Google Play services features'
        if (checkPlayServices(activity)) {
            try {
                FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            storeFcmToken(activity, token)
                            if (registerPusher) {
                                pushersManager.registerPusherWithFcmKey(token)
                            }
                        }
                        .addOnFailureListener { e ->
                            Timber.e(e, "## ensureFcmTokenIsRetrieved() : failed")
                        }
            } catch (e: Throwable) {
                Timber.e(e, "## ensureFcmTokenIsRetrieved() : failed")
            }
        } else {
            Toast.makeText(activity, R.string.no_valid_google_play_services_apk, Toast.LENGTH_SHORT).show()
            Timber.e("No valid Google Play Services found. Cannot use FCM.")
        }
    }

    /**
     * Check the device to make sure it has the Google Play Services APK. If
     * it doesn't, display a dialog that allows users to download the APK from
     * the Google Play Store or enable it in the device's system settings.
     */
    private fun checkPlayServices(activity: Activity): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(activity)
        return resultCode == ConnectionResult.SUCCESS
    }

    @Suppress("UNUSED_PARAMETER")
    fun onEnterForeground(context: Context, activeSessionHolder: ActiveSessionHolder) {
        // No op
    }

    @Suppress("UNUSED_PARAMETER")
    fun onEnterBackground(context: Context, vectorPreferences: VectorPreferences, activeSessionHolder: ActiveSessionHolder) {
        val activeSession = activeSessionHolder.getSafeActiveSession() ?: return
        activeSession.startAutomaticBackgroundSync(
                vectorPreferences.backgroundSyncTimeOut().toLong(),
                false,
                vectorPreferences.backgroundSyncDelay().toLong()
        )
    }
}

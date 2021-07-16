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
package im.vector.app.features.settings

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import com.squareup.seismic.ShakeDetector
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.core.di.DefaultSharedPreferences
import im.vector.app.features.disclaimer.SHARED_PREF_KEY
import im.vector.app.features.homeserver.ServerUrlsRepository
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import javax.inject.Inject

class VectorPreferences @Inject constructor(private val context: Context) {

    companion object {
        const val SETTINGS_CHANGE_PASSWORD_PREFERENCE_KEY = "SETTINGS_CHANGE_PASSWORD_PREFERENCE_KEY"
        const val SETTINGS_VERSION_PREFERENCE_KEY = "SETTINGS_VERSION_PREFERENCE_KEY"
        const val SETTINGS_SDK_VERSION_PREFERENCE_KEY = "SETTINGS_SDK_VERSION_PREFERENCE_KEY"
        const val SETTINGS_OLM_VERSION_PREFERENCE_KEY = "SETTINGS_OLM_VERSION_PREFERENCE_KEY"
        const val SETTINGS_LOGGED_IN_PREFERENCE_KEY = "SETTINGS_LOGGED_IN_PREFERENCE_KEY"
        const val SETTINGS_HOME_SERVER_PREFERENCE_KEY = "SETTINGS_HOME_SERVER_PREFERENCE_KEY"
        const val SETTINGS_IDENTITY_SERVER_PREFERENCE_KEY = "SETTINGS_IDENTITY_SERVER_PREFERENCE_KEY"
        const val SETTINGS_APP_TERM_CONDITIONS_PREFERENCE_KEY = "SETTINGS_APP_TERM_CONDITIONS_PREFERENCE_KEY"
        const val SETTINGS_PRIVACY_POLICY_PREFERENCE_KEY = "SETTINGS_PRIVACY_POLICY_PREFERENCE_KEY"

        const val SETTINGS_NOTIFICATION_ADVANCED_PREFERENCE_KEY = "SETTINGS_NOTIFICATION_ADVANCED_PREFERENCE_KEY"
        const val SETTINGS_THIRD_PARTY_NOTICES_PREFERENCE_KEY = "SETTINGS_THIRD_PARTY_NOTICES_PREFERENCE_KEY"
        const val SETTINGS_OTHER_THIRD_PARTY_NOTICES_PREFERENCE_KEY = "SETTINGS_OTHER_THIRD_PARTY_NOTICES_PREFERENCE_KEY"
        const val SETTINGS_COPYRIGHT_PREFERENCE_KEY = "SETTINGS_COPYRIGHT_PREFERENCE_KEY"
        const val SETTINGS_CLEAR_CACHE_PREFERENCE_KEY = "SETTINGS_CLEAR_CACHE_PREFERENCE_KEY"
        const val SETTINGS_CLEAR_MEDIA_CACHE_PREFERENCE_KEY = "SETTINGS_CLEAR_MEDIA_CACHE_PREFERENCE_KEY"
        const val SETTINGS_USER_SETTINGS_PREFERENCE_KEY = "SETTINGS_USER_SETTINGS_PREFERENCE_KEY"
        const val SETTINGS_CONTACT_PREFERENCE_KEYS = "SETTINGS_CONTACT_PREFERENCE_KEYS"
        const val SETTINGS_NOTIFICATIONS_TARGETS_PREFERENCE_KEY = "SETTINGS_NOTIFICATIONS_TARGETS_PREFERENCE_KEY"
        const val SETTINGS_NOTIFICATIONS_TARGET_DIVIDER_PREFERENCE_KEY = "SETTINGS_NOTIFICATIONS_TARGET_DIVIDER_PREFERENCE_KEY"
        const val SETTINGS_FDROID_BACKGROUND_SYNC_MODE = "SETTINGS_FDROID_BACKGROUND_SYNC_MODE"
        const val SETTINGS_BACKGROUND_SYNC_PREFERENCE_KEY = "SETTINGS_BACKGROUND_SYNC_PREFERENCE_KEY"
        const val SETTINGS_BACKGROUND_SYNC_DIVIDER_PREFERENCE_KEY = "SETTINGS_BACKGROUND_SYNC_DIVIDER_PREFERENCE_KEY"
        const val SETTINGS_LABS_PREFERENCE_KEY = "SETTINGS_LABS_PREFERENCE_KEY"
        const val SETTINGS_CRYPTOGRAPHY_PREFERENCE_KEY = "SETTINGS_CRYPTOGRAPHY_PREFERENCE_KEY"
        const val SETTINGS_CRYPTOGRAPHY_DIVIDER_PREFERENCE_KEY = "SETTINGS_CRYPTOGRAPHY_DIVIDER_PREFERENCE_KEY"
        const val SETTINGS_CRYPTOGRAPHY_MANAGE_PREFERENCE_KEY = "SETTINGS_CRYPTOGRAPHY_MANAGE_PREFERENCE_KEY"
        const val SETTINGS_CRYPTOGRAPHY_MANAGE_DIVIDER_PREFERENCE_KEY = "SETTINGS_CRYPTOGRAPHY_MANAGE_DIVIDER_PREFERENCE_KEY"
        const val SETTINGS_ROOM_SETTINGS_LABS_END_TO_END_PREFERENCE_KEY = "SETTINGS_ROOM_SETTINGS_LABS_END_TO_END_PREFERENCE_KEY"
        const val SETTINGS_ROOM_SETTINGS_LABS_END_TO_END_IS_ACTIVE_PREFERENCE_KEY = "SETTINGS_ROOM_SETTINGS_LABS_END_TO_END_IS_ACTIVE_PREFERENCE_KEY"
        const val SETTINGS_ENCRYPTION_CROSS_SIGNING_PREFERENCE_KEY = "SETTINGS_ENCRYPTION_CROSS_SIGNING_PREFERENCE_KEY"
        const val SETTINGS_ENCRYPTION_EXPORT_E2E_ROOM_KEYS_PREFERENCE_KEY = "SETTINGS_ENCRYPTION_EXPORT_E2E_ROOM_KEYS_PREFERENCE_KEY"
        const val SETTINGS_ENCRYPTION_IMPORT_E2E_ROOM_KEYS_PREFERENCE_KEY = "SETTINGS_ENCRYPTION_IMPORT_E2E_ROOM_KEYS_PREFERENCE_KEY"
        const val SETTINGS_ENCRYPTION_NEVER_SENT_TO_PREFERENCE_KEY = "SETTINGS_ENCRYPTION_NEVER_SENT_TO_PREFERENCE_KEY"
        const val SETTINGS_SHOW_DEVICES_LIST_PREFERENCE_KEY = "SETTINGS_SHOW_DEVICES_LIST_PREFERENCE_KEY"
        const val SETTINGS_ALLOW_INTEGRATIONS_KEY = "SETTINGS_ALLOW_INTEGRATIONS_KEY"
        const val SETTINGS_INTEGRATION_MANAGER_UI_URL_KEY = "SETTINGS_INTEGRATION_MANAGER_UI_URL_KEY"
        const val SETTINGS_SECURE_MESSAGE_RECOVERY_PREFERENCE_KEY = "SETTINGS_SECURE_MESSAGE_RECOVERY_PREFERENCE_KEY"

        const val SETTINGS_CRYPTOGRAPHY_HS_ADMIN_DISABLED_E2E_DEFAULT = "SETTINGS_CRYPTOGRAPHY_HS_ADMIN_DISABLED_E2E_DEFAULT"
//        const val SETTINGS_SECURE_BACKUP_RESET_PREFERENCE_KEY = "SETTINGS_SECURE_BACKUP_RESET_PREFERENCE_KEY"

        // user
        const val SETTINGS_PROFILE_PICTURE_PREFERENCE_KEY = "SETTINGS_PROFILE_PICTURE_PREFERENCE_KEY"

        // contacts
        const val SETTINGS_CONTACTS_PHONEBOOK_COUNTRY_PREFERENCE_KEY = "SETTINGS_CONTACTS_PHONEBOOK_COUNTRY_PREFERENCE_KEY"

        // interface
        const val SETTINGS_INTERFACE_LANGUAGE_PREFERENCE_KEY = "SETTINGS_INTERFACE_LANGUAGE_PREFERENCE_KEY"
        const val SETTINGS_INTERFACE_TEXT_SIZE_KEY = "SETTINGS_INTERFACE_TEXT_SIZE_KEY"
        const val SETTINGS_SHOW_URL_PREVIEW_KEY = "SETTINGS_SHOW_URL_PREVIEW_KEY"
        private const val SETTINGS_SEND_TYPING_NOTIF_KEY = "SETTINGS_SEND_TYPING_NOTIF_KEY"
        private const val SETTINGS_ENABLE_MARKDOWN_KEY = "SETTINGS_ENABLE_MARKDOWN_KEY"
        private const val SETTINGS_ALWAYS_SHOW_TIMESTAMPS_KEY = "SETTINGS_ALWAYS_SHOW_TIMESTAMPS_KEY"
        private const val SETTINGS_12_24_TIMESTAMPS_KEY = "SETTINGS_12_24_TIMESTAMPS_KEY"
        private const val SETTINGS_SHOW_READ_RECEIPTS_KEY = "SETTINGS_SHOW_READ_RECEIPTS_KEY"
        private const val SETTINGS_SHOW_REDACTED_KEY = "SETTINGS_SHOW_REDACTED_KEY"
        private const val SETTINGS_SHOW_ROOM_MEMBER_STATE_EVENTS_KEY = "SETTINGS_SHOW_ROOM_MEMBER_STATE_EVENTS_KEY"
        private const val SETTINGS_SHOW_JOIN_LEAVE_MESSAGES_KEY = "SETTINGS_SHOW_JOIN_LEAVE_MESSAGES_KEY"
        private const val SETTINGS_SHOW_AVATAR_DISPLAY_NAME_CHANGES_MESSAGES_KEY = "SETTINGS_SHOW_AVATAR_DISPLAY_NAME_CHANGES_MESSAGES_KEY"
        private const val SETTINGS_VIBRATE_ON_MENTION_KEY = "SETTINGS_VIBRATE_ON_MENTION_KEY"
        private const val SETTINGS_SEND_MESSAGE_WITH_ENTER = "SETTINGS_SEND_MESSAGE_WITH_ENTER"
        private const val SETTINGS_ENABLE_CHAT_EFFECTS = "SETTINGS_ENABLE_CHAT_EFFECTS"
        private const val SETTINGS_SHOW_EMOJI_KEYBOARD = "SETTINGS_SHOW_EMOJI_KEYBOARD"

        // Room directory
        private const val SETTINGS_ROOM_DIRECTORY_SHOW_ALL_PUBLIC_ROOMS = "SETTINGS_ROOM_DIRECTORY_SHOW_ALL_PUBLIC_ROOMS"

        // Help
        private const val SETTINGS_SHOULD_SHOW_HELP_ON_ROOM_LIST_KEY = "SETTINGS_SHOULD_SHOW_HELP_ON_ROOM_LIST_KEY"

        // home
        private const val SETTINGS_PIN_UNREAD_MESSAGES_PREFERENCE_KEY = "SETTINGS_PIN_UNREAD_MESSAGES_PREFERENCE_KEY"
        private const val SETTINGS_PIN_MISSED_NOTIFICATIONS_PREFERENCE_KEY = "SETTINGS_PIN_MISSED_NOTIFICATIONS_PREFERENCE_KEY"

        // flair
        const val SETTINGS_GROUPS_FLAIR_KEY = "SETTINGS_GROUPS_FLAIR_KEY"

        // notifications
        const val SETTINGS_ENABLE_ALL_NOTIF_PREFERENCE_KEY = "SETTINGS_ENABLE_ALL_NOTIF_PREFERENCE_KEY"
        const val SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY = "SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY"

        //    public static final String SETTINGS_TURN_SCREEN_ON_PREFERENCE_KEY = "SETTINGS_TURN_SCREEN_ON_PREFERENCE_KEY";
        const val SETTINGS_SYSTEM_CALL_NOTIFICATION_PREFERENCE_KEY = "SETTINGS_SYSTEM_CALL_NOTIFICATION_PREFERENCE_KEY"
        const val SETTINGS_SYSTEM_NOISY_NOTIFICATION_PREFERENCE_KEY = "SETTINGS_SYSTEM_NOISY_NOTIFICATION_PREFERENCE_KEY"
        const val SETTINGS_SYSTEM_SILENT_NOTIFICATION_PREFERENCE_KEY = "SETTINGS_SYSTEM_SILENT_NOTIFICATION_PREFERENCE_KEY"
        const val SETTINGS_NOTIFICATION_RINGTONE_PREFERENCE_KEY = "SETTINGS_NOTIFICATION_RINGTONE_PREFERENCE_KEY"
        const val SETTINGS_NOTIFICATION_RINGTONE_SELECTION_PREFERENCE_KEY = "SETTINGS_NOTIFICATION_RINGTONE_SELECTION_PREFERENCE_KEY"

        // media
        private const val SETTINGS_DEFAULT_MEDIA_COMPRESSION_KEY = "SETTINGS_DEFAULT_MEDIA_COMPRESSION_KEY"
        private const val SETTINGS_DEFAULT_MEDIA_SOURCE_KEY = "SETTINGS_DEFAULT_MEDIA_SOURCE_KEY"
        private const val SETTINGS_PREVIEW_MEDIA_BEFORE_SENDING_KEY = "SETTINGS_PREVIEW_MEDIA_BEFORE_SENDING_KEY"
        private const val SETTINGS_PLAY_SHUTTER_SOUND_KEY = "SETTINGS_PLAY_SHUTTER_SOUND_KEY"

        // background sync
        const val SETTINGS_START_ON_BOOT_PREFERENCE_KEY = "SETTINGS_START_ON_BOOT_PREFERENCE_KEY"
        const val SETTINGS_ENABLE_BACKGROUND_SYNC_PREFERENCE_KEY = "SETTINGS_ENABLE_BACKGROUND_SYNC_PREFERENCE_KEY"
        const val SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY = "SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY"
        const val SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY = "SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY"

        // Calls
        const val SETTINGS_CALL_PREVENT_ACCIDENTAL_CALL_KEY = "SETTINGS_CALL_PREVENT_ACCIDENTAL_CALL_KEY"
        const val SETTINGS_CALL_RINGTONE_USE_RIOT_PREFERENCE_KEY = "SETTINGS_CALL_RINGTONE_USE_RIOT_PREFERENCE_KEY"
        const val SETTINGS_CALL_RINGTONE_URI_PREFERENCE_KEY = "SETTINGS_CALL_RINGTONE_URI_PREFERENCE_KEY"

        // labs
        const val SETTINGS_LAZY_LOADING_PREFERENCE_KEY = "SETTINGS_LAZY_LOADING_PREFERENCE_KEY"
        const val SETTINGS_USER_REFUSED_LAZY_LOADING_PREFERENCE_KEY = "SETTINGS_USER_REFUSED_LAZY_LOADING_PREFERENCE_KEY"
        const val SETTINGS_DATA_SAVE_MODE_PREFERENCE_KEY = "SETTINGS_DATA_SAVE_MODE_PREFERENCE_KEY"
        private const val SETTINGS_USE_JITSI_CONF_PREFERENCE_KEY = "SETTINGS_USE_JITSI_CONF_PREFERENCE_KEY"
        private const val SETTINGS_USE_NATIVE_CAMERA_PREFERENCE_KEY = "SETTINGS_USE_NATIVE_CAMERA_PREFERENCE_KEY"
        private const val SETTINGS_ENABLE_SEND_VOICE_FEATURE_PREFERENCE_KEY = "SETTINGS_ENABLE_SEND_VOICE_FEATURE_PREFERENCE_KEY"

        const val SETTINGS_LABS_ALLOW_EXTENDED_LOGS = "SETTINGS_LABS_ALLOW_EXTENDED_LOGS"
        const val SETTINGS_LABS_USE_RESTRICTED_JOIN_RULE = "SETTINGS_LABS_USE_RESTRICTED_JOIN_RULE"
        const val SETTINGS_LABS_SPACES_HOME_AS_ORPHAN = "SETTINGS_LABS_SPACES_HOME_AS_ORPHAN"

        const val SETTINGS_LABS_VOICE_MESSAGE = "SETTINGS_LABS_VOICE_MESSAGE"

        private const val SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY = "SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY"
        private const val SETTINGS_LABS_SHOW_HIDDEN_EVENTS_PREFERENCE_KEY = "SETTINGS_LABS_SHOW_HIDDEN_EVENTS_PREFERENCE_KEY"
        private const val SETTINGS_LABS_ENABLE_SWIPE_TO_REPLY = "SETTINGS_LABS_ENABLE_SWIPE_TO_REPLY"
        private const val SETTINGS_DEVELOPER_MODE_FAIL_FAST_PREFERENCE_KEY = "SETTINGS_DEVELOPER_MODE_FAIL_FAST_PREFERENCE_KEY"

        // SETTINGS_LABS_HIDE_TECHNICAL_E2E_ERRORS
        private const val SETTINGS_LABS_SHOW_COMPLETE_HISTORY_IN_ENCRYPTED_ROOM = "SETTINGS_LABS_SHOW_COMPLETE_HISTORY_IN_ENCRYPTED_ROOM"
        const val SETTINGS_LABS_UNREAD_NOTIFICATIONS_AS_TAB = "SETTINGS_LABS_UNREAD_NOTIFICATIONS_AS_TAB"

        // analytics
        const val SETTINGS_USE_ANALYTICS_KEY = "SETTINGS_USE_ANALYTICS_KEY"

        // Rageshake
        const val SETTINGS_USE_RAGE_SHAKE_KEY = "SETTINGS_USE_RAGE_SHAKE_KEY"
        const val SETTINGS_RAGE_SHAKE_DETECTION_THRESHOLD_KEY = "SETTINGS_RAGE_SHAKE_DETECTION_THRESHOLD_KEY"

        // Security
        const val SETTINGS_SECURITY_USE_FLAG_SECURE = "SETTINGS_SECURITY_USE_FLAG_SECURE"
        const val SETTINGS_SECURITY_USE_PIN_CODE_FLAG = "SETTINGS_SECURITY_USE_PIN_CODE_FLAG"
        const val SETTINGS_SECURITY_CHANGE_PIN_CODE_FLAG = "SETTINGS_SECURITY_CHANGE_PIN_CODE_FLAG"
        private const val SETTINGS_SECURITY_USE_BIOMETRICS_FLAG = "SETTINGS_SECURITY_USE_BIOMETRICS_FLAG"
        private const val SETTINGS_SECURITY_USE_GRACE_PERIOD_FLAG = "SETTINGS_SECURITY_USE_GRACE_PERIOD_FLAG"
        const val SETTINGS_SECURITY_USE_COMPLETE_NOTIFICATIONS_FLAG = "SETTINGS_SECURITY_USE_COMPLETE_NOTIFICATIONS_FLAG"

        // other
        const val SETTINGS_MEDIA_SAVING_PERIOD_KEY = "SETTINGS_MEDIA_SAVING_PERIOD_KEY"
        private const val SETTINGS_MEDIA_SAVING_PERIOD_SELECTED_KEY = "SETTINGS_MEDIA_SAVING_PERIOD_SELECTED_KEY"
        private const val DID_ASK_TO_IGNORE_BATTERY_OPTIMIZATIONS_KEY = "DID_ASK_TO_IGNORE_BATTERY_OPTIMIZATIONS_KEY"
        private const val DID_MIGRATE_TO_NOTIFICATION_REWORK = "DID_MIGRATE_TO_NOTIFICATION_REWORK"
        private const val DID_ASK_TO_USE_ANALYTICS_TRACKING_KEY = "DID_ASK_TO_USE_ANALYTICS_TRACKING_KEY"
        private const val SETTINGS_DISPLAY_ALL_EVENTS_KEY = "SETTINGS_DISPLAY_ALL_EVENTS_KEY"

        private const val DID_ASK_TO_ENABLE_SESSION_PUSH = "DID_ASK_TO_ENABLE_SESSION_PUSH"

        private const val MEDIA_SAVING_3_DAYS = 0
        private const val MEDIA_SAVING_1_WEEK = 1
        private const val MEDIA_SAVING_1_MONTH = 2
        private const val MEDIA_SAVING_FOREVER = 3

        private const val SETTINGS_UNKNOWN_DEVICE_DISMISSED_LIST = "SETTINGS_UNKNWON_DEVICE_DISMISSED_LIST"

        private const val TAKE_PHOTO_VIDEO_MODE = "TAKE_PHOTO_VIDEO_MODE"

        // Possible values for TAKE_PHOTO_VIDEO_MODE
        const val TAKE_PHOTO_VIDEO_MODE_ALWAYS_ASK = 0
        const val TAKE_PHOTO_VIDEO_MODE_PHOTO = 1
        const val TAKE_PHOTO_VIDEO_MODE_VIDEO = 2

        // Background sync modes

        // some preferences keys must be kept after a logout
        private val mKeysToKeepAfterLogout = listOf(
                SETTINGS_DEFAULT_MEDIA_COMPRESSION_KEY,
                SETTINGS_DEFAULT_MEDIA_SOURCE_KEY,
                SETTINGS_PLAY_SHUTTER_SOUND_KEY,

                SETTINGS_SEND_TYPING_NOTIF_KEY,
                SETTINGS_ALWAYS_SHOW_TIMESTAMPS_KEY,
                SETTINGS_12_24_TIMESTAMPS_KEY,
                SETTINGS_SHOW_READ_RECEIPTS_KEY,
                SETTINGS_SHOW_ROOM_MEMBER_STATE_EVENTS_KEY,
                SETTINGS_SHOW_JOIN_LEAVE_MESSAGES_KEY,
                SETTINGS_SHOW_AVATAR_DISPLAY_NAME_CHANGES_MESSAGES_KEY,
                SETTINGS_MEDIA_SAVING_PERIOD_KEY,
                SETTINGS_MEDIA_SAVING_PERIOD_SELECTED_KEY,
                SETTINGS_PREVIEW_MEDIA_BEFORE_SENDING_KEY,
                SETTINGS_SEND_MESSAGE_WITH_ENTER,
                SETTINGS_SHOW_EMOJI_KEYBOARD,

                SETTINGS_PIN_UNREAD_MESSAGES_PREFERENCE_KEY,
                SETTINGS_PIN_MISSED_NOTIFICATIONS_PREFERENCE_KEY,
                // Do not keep SETTINGS_LAZY_LOADING_PREFERENCE_KEY because the user may log in on a server which does not support lazy loading
                SETTINGS_DATA_SAVE_MODE_PREFERENCE_KEY,
                SETTINGS_START_ON_BOOT_PREFERENCE_KEY,
                SETTINGS_INTERFACE_TEXT_SIZE_KEY,
                SETTINGS_USE_JITSI_CONF_PREFERENCE_KEY,
                SETTINGS_NOTIFICATION_RINGTONE_PREFERENCE_KEY,
                SETTINGS_NOTIFICATION_RINGTONE_SELECTION_PREFERENCE_KEY,

                SETTINGS_ROOM_SETTINGS_LABS_END_TO_END_PREFERENCE_KEY,
                SETTINGS_CONTACTS_PHONEBOOK_COUNTRY_PREFERENCE_KEY,
                SETTINGS_INTERFACE_LANGUAGE_PREFERENCE_KEY,
                SETTINGS_BACKGROUND_SYNC_PREFERENCE_KEY,
                SETTINGS_ENABLE_BACKGROUND_SYNC_PREFERENCE_KEY,
                SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY,
                SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY,

                SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY,
                SETTINGS_LABS_SHOW_HIDDEN_EVENTS_PREFERENCE_KEY,
                SETTINGS_LABS_ALLOW_EXTENDED_LOGS,
                SETTINGS_LABS_USE_RESTRICTED_JOIN_RULE,
                SETTINGS_DEVELOPER_MODE_FAIL_FAST_PREFERENCE_KEY,

                SETTINGS_USE_RAGE_SHAKE_KEY,
                SETTINGS_SECURITY_USE_FLAG_SECURE
        )
    }

    private val defaultPrefs = DefaultSharedPreferences.getInstance(context)

    /**
     * Allow subscribing and unsubscribing to configuration changes. This is
     * particularly useful when you need to be notified of a configuration change
     * in a background service, e.g. for the P2P demos.
     */
    fun subscribeToChanges(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        defaultPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unsubscribeToChanges(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        defaultPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Clear the preferences.
     */
    fun clearPreferences() {
        val keysToKeep = HashSet(mKeysToKeepAfterLogout)

        // homeserver urls
        keysToKeep.add(ServerUrlsRepository.HOME_SERVER_URL_PREF)
        keysToKeep.add(ServerUrlsRepository.IDENTITY_SERVER_URL_PREF)

        // theme
        keysToKeep.add(ThemeUtils.APPLICATION_THEME_KEY)

        // Disclaimer dialog
        keysToKeep.add(SHARED_PREF_KEY)

        // get all the existing keys
        val keys = defaultPrefs.all.keys

        // remove the one to keep
        keys.removeAll(keysToKeep)

        defaultPrefs.edit {
            for (key in keys) {
                remove(key)
            }
        }
    }

    fun areNotificationEnabledForDevice(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY, true)
    }

    fun setNotificationEnabledForDevice(enabled: Boolean) {
        defaultPrefs.edit {
            putBoolean(SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY, enabled)
        }
    }

    fun developerMode(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_DEVELOPER_MODE_PREFERENCE_KEY, false)
    }

    fun shouldShowHiddenEvents(): Boolean {
        return developerMode() && defaultPrefs.getBoolean(SETTINGS_LABS_SHOW_HIDDEN_EVENTS_PREFERENCE_KEY, false)
    }

    fun swipeToReplyIsEnabled(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_LABS_ENABLE_SWIPE_TO_REPLY, true)
    }

    fun labShowCompleteHistoryInEncryptedRoom(): Boolean {
        return developerMode() && defaultPrefs.getBoolean(SETTINGS_LABS_SHOW_COMPLETE_HISTORY_IN_ENCRYPTED_ROOM, false)
    }

    fun labAllowedExtendedLogging(): Boolean {
        return developerMode() && defaultPrefs.getBoolean(SETTINGS_LABS_ALLOW_EXTENDED_LOGS, false)
    }

    fun labAddNotificationTab(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_LABS_UNREAD_NOTIFICATIONS_AS_TAB, false)
    }

    fun failFast(): Boolean {
        return BuildConfig.DEBUG || (developerMode() && defaultPrefs.getBoolean(SETTINGS_DEVELOPER_MODE_FAIL_FAST_PREFERENCE_KEY, false))
    }

    fun didAskUserToEnableSessionPush(): Boolean {
        return defaultPrefs.getBoolean(DID_ASK_TO_ENABLE_SESSION_PUSH, false)
    }

    fun setDidAskUserToEnableSessionPush() {
        defaultPrefs.edit {
            putBoolean(DID_ASK_TO_ENABLE_SESSION_PUSH, true)
        }
    }

    /**
     * Tells if we have already asked the user to disable battery optimisations on android >= M devices.
     *
     * @return true if it was already requested
     */
    fun didAskUserToIgnoreBatteryOptimizations(): Boolean {
        return defaultPrefs.getBoolean(DID_ASK_TO_IGNORE_BATTERY_OPTIMIZATIONS_KEY, false)
    }

    /**
     * Mark as requested the question to disable battery optimisations.
     */
    fun setDidAskUserToIgnoreBatteryOptimizations() {
        defaultPrefs.edit {
            putBoolean(DID_ASK_TO_IGNORE_BATTERY_OPTIMIZATIONS_KEY, true)
        }
    }

    fun didMigrateToNotificationRework(): Boolean {
        return defaultPrefs.getBoolean(DID_MIGRATE_TO_NOTIFICATION_REWORK, false)
    }

    fun setDidMigrateToNotificationRework() {
        defaultPrefs.edit {
            putBoolean(DID_MIGRATE_TO_NOTIFICATION_REWORK, true)
        }
    }

    /**
     * Tells if the timestamp must be displayed in 12h format
     *
     * @return true if the time must be displayed in 12h format
     */
    fun displayTimeIn12hFormat(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_12_24_TIMESTAMPS_KEY, false)
    }

    /**
     * Tells if the join and leave membership events should be shown in the messages list.
     *
     * @return true if the join and leave membership events should be shown in the messages list
     */
    fun showJoinLeaveMessages(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOW_JOIN_LEAVE_MESSAGES_KEY, true)
    }

    /**
     * Tells if the avatar and display name events should be shown in the messages list.
     *
     * @return true true if the avatar and display name events should be shown in the messages list.
     */
    fun showAvatarDisplayNameChangeMessages(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOW_AVATAR_DISPLAY_NAME_CHANGES_MESSAGES_KEY, true)
    }

    /**
     * Tells the native camera to take a photo or record a video.
     *
     * @return true to use the native camera app to record video or take photo.
     */
    fun useNativeCamera(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_USE_NATIVE_CAMERA_PREFERENCE_KEY, false)
    }

    /**
     * Tells if the send voice feature is enabled.
     *
     * @return true if the send voice feature is enabled.
     */
    fun isSendVoiceFeatureEnabled(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_ENABLE_SEND_VOICE_FEATURE_PREFERENCE_KEY, false)
    }

    /**
     * Show all rooms in room directory
     */
    fun showAllPublicRooms(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_ROOM_DIRECTORY_SHOW_ALL_PUBLIC_ROOMS, false)
    }

    /**
     * Tells which compression level to use by default
     *
     * @return the selected compression level
     */
    fun getSelectedDefaultMediaCompressionLevel(): Int {
        return Integer.parseInt(defaultPrefs.getString(SETTINGS_DEFAULT_MEDIA_COMPRESSION_KEY, "0")!!)
    }

    /**
     * Tells which media source to use by default
     *
     * @return the selected media source
     */
    fun getSelectedDefaultMediaSource(): Int {
        return Integer.parseInt(defaultPrefs.getString(SETTINGS_DEFAULT_MEDIA_SOURCE_KEY, "0")!!)
    }

    /**
     * Tells whether to use shutter sound.
     *
     * @return true if shutter sound should play
     */
    fun useShutterSound(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_PLAY_SHUTTER_SOUND_KEY, true)
    }

    fun storeUnknownDeviceDismissedList(deviceIds: List<String>) {
        defaultPrefs.edit(true) {
            putStringSet(SETTINGS_UNKNOWN_DEVICE_DISMISSED_LIST, deviceIds.toSet())
        }
    }

    fun getUnknownDeviceDismissedList(): List<String> {
        return tryOrNull {
            defaultPrefs.getStringSet(SETTINGS_UNKNOWN_DEVICE_DISMISSED_LIST, null)?.toList()
        }.orEmpty()
    }

    /**
     * Update the notification ringtone
     *
     * @param uri     the new notification ringtone, or null for no RingTone
     */
    fun setNotificationRingTone(uri: Uri?) {
        defaultPrefs.edit {
            var value = ""

            if (null != uri) {
                value = uri.toString()

                if (value.startsWith("file://")) {
                    // it should never happen
                    // else android.os.FileUriExposedException will be triggered.
                    // see https://github.com/vector-im/riot-android/issues/1725
                    return
                }
            }

            putString(SETTINGS_NOTIFICATION_RINGTONE_PREFERENCE_KEY, value)
        }
    }

    /**
     * Provides the selected notification ring tone
     *
     * @return the selected ring tone or null for no RingTone
     */
    fun getNotificationRingTone(): Uri? {
        val url = defaultPrefs.getString(SETTINGS_NOTIFICATION_RINGTONE_PREFERENCE_KEY, null)

        // the user selects "None"
        if (url == "") {
            return null
        }

        var uri: Uri? = null

        // https://github.com/vector-im/riot-android/issues/1725
        if (null != url && !url.startsWith("file://")) {
            try {
                uri = Uri.parse(url)
            } catch (e: Exception) {
                Timber.e(e, "## getNotificationRingTone() : Uri.parse failed")
            }
        }

        if (null == uri) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

        Timber.v("## getNotificationRingTone() returns $uri")
        return uri
    }

    /**
     * Provide the notification ringtone filename
     *
     * @return the filename or null if "None" is selected
     */
    fun getNotificationRingToneName(): String? {
        val toneUri = getNotificationRingTone() ?: return null

        try {
            val proj = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
            return context.contentResolver.query(toneUri, proj, null, null, null)?.use {
                val columnIndex = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                it.moveToFirst()
                it.getString(columnIndex)
            }
        } catch (e: Exception) {
            Timber.e(e, "## getNotificationRingToneName() failed")
        }

        return null
    }

    /**
     * Enable or disable the lazy loading
     *
     * @param newValue true to enable lazy loading, false to disable it
     */
    fun setUseLazyLoading(newValue: Boolean) {
        defaultPrefs.edit {
            putBoolean(SETTINGS_LAZY_LOADING_PREFERENCE_KEY, newValue)
        }
    }

    /**
     * Tells if the lazy loading is enabled
     *
     * @return true if the lazy loading of room members is enabled
     */
    fun useLazyLoading(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_LAZY_LOADING_PREFERENCE_KEY, false)
    }

    /**
     * User explicitly refuses the lazy loading.
     *
     */
    fun setUserRefuseLazyLoading() {
        defaultPrefs.edit {
            putBoolean(SETTINGS_USER_REFUSED_LAZY_LOADING_PREFERENCE_KEY, true)
        }
    }

    /**
     * Tells if the user has explicitly refused the lazy loading
     *
     * @return true if the user has explicitly refuse the lazy loading of room members
     */
    fun hasUserRefusedLazyLoading(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_USER_REFUSED_LAZY_LOADING_PREFERENCE_KEY, false)
    }

    /**
     * Tells if the data save mode is enabled
     *
     * @return true if the data save mode is enabled
     */
    fun useDataSaveMode(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_DATA_SAVE_MODE_PREFERENCE_KEY, false)
    }

    /**
     * Tells if the conf calls must be done with Jitsi.
     *
     * @return true if the conference call must be done with jitsi.
     */
    fun useJitsiConfCall(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_USE_JITSI_CONF_PREFERENCE_KEY, true)
    }

    /**
     * Tells if the application is started on boot
     *
     * @return true if the application must be started on boot
     */
    fun autoStartOnBoot(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_START_ON_BOOT_PREFERENCE_KEY, true)
    }

    /**
     * Tells if the application is started on boot
     *
     * @param value   true to start the application on boot
     */
    fun setAutoStartOnBoot(value: Boolean) {
        defaultPrefs.edit {
            putBoolean(SETTINGS_START_ON_BOOT_PREFERENCE_KEY, value)
        }
    }

    /**
     * Provides the selected saving period.
     *
     * @return the selected period
     */
    fun getSelectedMediasSavingPeriod(): Int {
        return defaultPrefs.getInt(SETTINGS_MEDIA_SAVING_PERIOD_SELECTED_KEY, MEDIA_SAVING_1_WEEK)
    }

    /**
     * Updates the selected saving period.
     *
     * @param index   the selected period index
     */
    fun setSelectedMediasSavingPeriod(index: Int) {
        defaultPrefs.edit {
            putInt(SETTINGS_MEDIA_SAVING_PERIOD_SELECTED_KEY, index)
        }
    }

    /**
     * Provides the minimum last access time to keep a media file.
     *
     * @return the min last access time (in seconds)
     */
    fun getMinMediasLastAccessTime(): Long {
        return when (getSelectedMediasSavingPeriod()) {
            MEDIA_SAVING_3_DAYS  -> System.currentTimeMillis() / 1000 - 3 * 24 * 60 * 60
            MEDIA_SAVING_1_WEEK  -> System.currentTimeMillis() / 1000 - 7 * 24 * 60 * 60
            MEDIA_SAVING_1_MONTH -> System.currentTimeMillis() / 1000 - 30 * 24 * 60 * 60
            MEDIA_SAVING_FOREVER -> 0
            else                 -> 0
        }
    }

    /**
     * Provides the selected saving period.
     *
     * @return the selected period
     */
    fun getSelectedMediasSavingPeriodString(): String {
        return when (getSelectedMediasSavingPeriod()) {
            MEDIA_SAVING_3_DAYS  -> context.getString(R.string.media_saving_period_3_days)
            MEDIA_SAVING_1_WEEK  -> context.getString(R.string.media_saving_period_1_week)
            MEDIA_SAVING_1_MONTH -> context.getString(R.string.media_saving_period_1_month)
            MEDIA_SAVING_FOREVER -> context.getString(R.string.media_saving_period_forever)
            else                 -> "?"
        }
    }

    /**
     * Fix some migration issues
     */
    fun fixMigrationIssues() {
        // Nothing to do for the moment
    }

    /**
     * Tells if the markdown is enabled
     *
     * @return true if the markdown is enabled
     */
    fun isMarkdownEnabled(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_ENABLE_MARKDOWN_KEY, false)
    }

    /**
     * Update the markdown enable status.
     *
     * @param isEnabled true to enable the markdown
     */
    fun setMarkdownEnabled(isEnabled: Boolean) {
        defaultPrefs.edit {
            putBoolean(SETTINGS_ENABLE_MARKDOWN_KEY, isEnabled)
        }
    }

    /**
     * Tells if a confirmation dialog should be displayed before staring a call
     */
    fun preventAccidentalCall(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_CALL_PREVENT_ACCIDENTAL_CALL_KEY, false)
    }

    /**
     * Tells if the read receipts should be shown
     *
     * @return true if the read receipts should be shown
     */
    fun showReadReceipts(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOW_READ_RECEIPTS_KEY, true)
    }

    /**
     * Tells if the redacted message should be shown
     *
     * @return true if the redacted should be shown
     */
    fun showRedactedMessages(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOW_REDACTED_KEY, true)
    }

    /**
     * Tells if the help on room list should be shown
     *
     * @return true if the help on room list should be shown
     */
    fun shouldShowLongClickOnRoomHelp(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOULD_SHOW_HELP_ON_ROOM_LIST_KEY, true)
    }

    /**
     * Prevent help on room list to be shown again
     */
    fun neverShowLongClickOnRoomHelpAgain() {
        defaultPrefs.edit {
            putBoolean(SETTINGS_SHOULD_SHOW_HELP_ON_ROOM_LIST_KEY, false)
        }
    }

    /**
     * Tells if the message timestamps must be always shown
     *
     * @return true if the message timestamps must be always shown
     */
    fun alwaysShowTimeStamps(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_ALWAYS_SHOW_TIMESTAMPS_KEY, false)
    }

    /**
     * Tells if the typing notifications should be sent
     *
     * @return true to send the typing notifs
     */
    fun sendTypingNotifs(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SEND_TYPING_NOTIF_KEY, true)
    }

    /**
     * Tells of the missing notifications rooms must be displayed at left (home screen)
     *
     * @return true to move the missed notifications to the left side
     */
    fun pinMissedNotifications(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_PIN_MISSED_NOTIFICATIONS_PREFERENCE_KEY, true)
    }

    /**
     * Tells of the unread rooms must be displayed at left (home screen)
     *
     * @return true to move the unread room to the left side
     */
    fun pinUnreadMessages(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_PIN_UNREAD_MESSAGES_PREFERENCE_KEY, true)
    }

    /**
     * Tells if the phone must vibrate when mentioning
     *
     * @return true
     */
    fun vibrateWhenMentioning(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_VIBRATE_ON_MENTION_KEY, false)
    }

    /**
     * Tells if a dialog has been displayed to ask to use the analytics tracking (piwik, matomo, etc.).
     *
     * @return true if a dialog has been displayed to ask to use the analytics tracking
     */
    fun didAskToUseAnalytics(): Boolean {
        return defaultPrefs.getBoolean(DID_ASK_TO_USE_ANALYTICS_TRACKING_KEY, false)
    }

    /**
     * To call if the user has been asked for analytics tracking.
     *
     */
    fun setDidAskToUseAnalytics() {
        defaultPrefs.edit {
            putBoolean(DID_ASK_TO_USE_ANALYTICS_TRACKING_KEY, true)
        }
    }

    /**
     * Tells if the analytics tracking is authorized (piwik, matomo, etc.).
     *
     * @return true if the analytics tracking is authorized
     */
    fun useAnalytics(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_USE_ANALYTICS_KEY, false)
    }

    /**
     * Tells if the user wants to see URL previews in the timeline
     *
     * @return true if the user wants to see URL previews in the timeline
     */
    fun showUrlPreviews(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOW_URL_PREVIEW_KEY, true)
    }

    /**
     * Enable or disable the analytics tracking.
     *
     * @param useAnalytics true to enable the analytics tracking
     */
    fun setUseAnalytics(useAnalytics: Boolean) {
        defaultPrefs.edit {
            putBoolean(SETTINGS_USE_ANALYTICS_KEY, useAnalytics)
        }
    }

    /**
     * Tells if media should be previewed before sending
     *
     * @return true to preview media
     */
    fun previewMediaWhenSending(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_PREVIEW_MEDIA_BEFORE_SENDING_KEY, false)
    }

    /**
     * Tells if message should be send by pressing enter on the soft keyboard
     *
     * @return true to send message with enter
     */
    fun sendMessageWithEnter(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SEND_MESSAGE_WITH_ENTER, false)
    }

    /**
     * Tells if the emoji keyboard button should be visible or not.
     *
     * @return true to show emoji keyboard button.
     */
    fun showEmojiKeyboard(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SHOW_EMOJI_KEYBOARD, true)
    }

    /**
     * Tells if the rage shake is used.
     *
     * @return true if the rage shake is used
     */
    fun useRageshake(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_USE_RAGE_SHAKE_KEY, true)
    }

    /**
     * Get the rage shake sensitivity.
     */
    fun getRageshakeSensitivity(): Int {
        return defaultPrefs.getInt(SETTINGS_RAGE_SHAKE_DETECTION_THRESHOLD_KEY, ShakeDetector.SENSITIVITY_MEDIUM)
    }

    /**
     * Tells if all the events must be displayed ie even the redacted events.
     *
     * @return true to display all the events even the redacted ones.
     */
    fun displayAllEvents(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_DISPLAY_ALL_EVENTS_KEY, false)
    }

    /**
     * The user does not allow screenshots of the application
     */
    fun useFlagSecure(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SECURITY_USE_FLAG_SECURE, false)
    }

    /**
     * The user enable protecting app access with pin code.
     * Currently we use the pin code store to know if the pin is enabled, so this is not used
     */
    fun useFlagPinCode(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SECURITY_USE_PIN_CODE_FLAG, false)
    }

    fun useBiometricsToUnlock(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SECURITY_USE_BIOMETRICS_FLAG, true)
    }

    fun useGracePeriod(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_SECURITY_USE_GRACE_PERIOD_FLAG, true)
    }

    fun chatEffectsEnabled(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_ENABLE_CHAT_EFFECTS, true)
    }

    /**
     * Return true if Pin code is disabled, or if user set the settings to see full notification content
     */
    fun useCompleteNotificationFormat(): Boolean {
        return !useFlagPinCode()
                || defaultPrefs.getBoolean(SETTINGS_SECURITY_USE_COMPLETE_NOTIFICATIONS_FLAG, true)
    }

    fun backgroundSyncTimeOut(): Int {
        return tryOrNull {
            // The xml pref is saved as a string so use getString and parse
            defaultPrefs.getString(SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY, null)?.toInt()
        } ?: BackgroundSyncMode.DEFAULT_SYNC_TIMEOUT_SECONDS
    }

    fun setBackgroundSyncTimeout(timeInSecond: Int) {
        defaultPrefs
                .edit()
                .putString(SETTINGS_SET_SYNC_TIMEOUT_PREFERENCE_KEY, timeInSecond.toString())
                .apply()
    }

    fun backgroundSyncDelay(): Int {
        return tryOrNull {
            // The xml pref is saved as a string so use getString and parse
            defaultPrefs.getString(SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY, null)?.toInt()
        } ?: BackgroundSyncMode.DEFAULT_SYNC_DELAY_SECONDS
    }

    fun setBackgroundSyncDelay(timeInSecond: Int) {
        defaultPrefs
                .edit()
                .putString(SETTINGS_SET_SYNC_DELAY_PREFERENCE_KEY, timeInSecond.toString())
                .apply()
    }

    fun isBackgroundSyncEnabled(): Boolean {
        return getFdroidSyncBackgroundMode() != BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_DISABLED
    }

    fun setFdroidSyncBackgroundMode(mode: BackgroundSyncMode) {
        defaultPrefs
                .edit()
                .putString(SETTINGS_FDROID_BACKGROUND_SYNC_MODE, mode.name)
                .apply()
    }

    fun getFdroidSyncBackgroundMode(): BackgroundSyncMode {
        return try {
            val strPref = defaultPrefs
                    .getString(SETTINGS_FDROID_BACKGROUND_SYNC_MODE, BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_BATTERY.name)
            BackgroundSyncMode.values().firstOrNull { it.name == strPref } ?: BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_BATTERY
        } catch (e: Throwable) {
            BackgroundSyncMode.FDROID_BACKGROUND_SYNC_MODE_FOR_BATTERY
        }
    }

    fun labsUseExperimentalRestricted(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_LABS_USE_RESTRICTED_JOIN_RULE, false)
    }

    fun labsSpacesOnlyOrphansInHome(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_LABS_SPACES_HOME_AS_ORPHAN, false)
    }

    /*
     * Photo / video picker
     */
    fun getTakePhotoVideoMode(): Int {
        return defaultPrefs.getInt(TAKE_PHOTO_VIDEO_MODE, TAKE_PHOTO_VIDEO_MODE_ALWAYS_ASK)
    }

    fun setTakePhotoVideoMode(mode: Int) {
        return defaultPrefs.edit {
            putInt(TAKE_PHOTO_VIDEO_MODE, mode)
        }
    }

    fun labsUseVoiceMessage(): Boolean {
        return defaultPrefs.getBoolean(SETTINGS_LABS_VOICE_MESSAGE, false)
    }
}

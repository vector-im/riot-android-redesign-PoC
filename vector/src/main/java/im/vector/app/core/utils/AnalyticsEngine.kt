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

package im.vector.app.core.utils

import android.app.Activity
import android.content.Context
import im.vector.app.BuildConfig
import im.vector.app.core.extensions.exhaustive
import im.vector.app.features.home.RoomListDisplayMode
import ly.count.android.sdk.Countly
import ly.count.android.sdk.CountlyConfig
import ly.count.android.sdknative.CountlyNative
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.internal.util.sha256
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsEngine @Inject constructor(private val context: Context) {

    sealed class AnalyticEvent {
        data class Init(val session: Session) : AnalyticEvent()

        // Used for session detection
        data class StartActivity(val activity: Activity) : AnalyticEvent()
        data class EndActivity(val activity: Activity) : AnalyticEvent()

        data class RoomView(val roomId: String, val isEncrypted: Boolean, val memberCount: Int, val isPublic: Boolean) : AnalyticEvent()
        data class HomeView(val mode: RoomListDisplayMode) : AnalyticEvent()
        data class SettingsView(val category: String) : AnalyticEvent()
        data class StartCall(val roomId: String, val isVideo: Boolean) : AnalyticEvent()
        data class JoinCall(val roomId: String, val isVideo: Boolean) : AnalyticEvent()
        data class StartJitsiConf(val roomId: String, val isVideo: Boolean) : AnalyticEvent()
        data class JoinConference(val roomId: String, val isVideo: Boolean) : AnalyticEvent()
        data class SendText(val roomId: String) : AnalyticEvent()
        data class SendReply(val roomId: String) : AnalyticEvent()
        data class SendQuote(val roomId: String) : AnalyticEvent()
        data class SendEdit(val roomId: String) : AnalyticEvent()
        data class SendMedia(val roomId: String, val type: ContentAttachmentData.Type) : AnalyticEvent()
        data class BeginInvite(val roomId: String, val isEncrypted: Boolean, val memberCount: Int, val isPublic: Boolean) : AnalyticEvent()
        data class SendInvite(val roomId: String, val inviteeCount: Int, val isEncrypted: Boolean, val memberCount: Int, val isPublic: Boolean) : AnalyticEvent()

        object StartRoomDirectory : AnalyticEvent()
        object EndRoomDirectory : AnalyticEvent()

        object StartRoomDirectorySearch : AnalyticEvent()
        data class EndRoomDirectorySearch(val resultCount: Int) : AnalyticEvent()

        object StartJoinRoomEvent : AnalyticEvent()
        data class EndJoinRoomEvent(val roomId: String, val isEncrypted: Boolean, val memberCount: Int, val isPublic: Boolean) : AnalyticEvent()
        object CancelJoinRoomEvent : AnalyticEvent()

        data class UnHandledCrash(val exception: Throwable) : AnalyticEvent()
        data class GetFeedBacks(val id: String, val closeText: String, val activity: Activity) : AnalyticEvent()
    }

    private var countly: Countly? = null

    fun isEnabled(): Boolean {
        // Should check consent here
        return countly?.isInitialized ?: false
    }

    fun report(event: AnalyticEvent) {
        when (event) {
            is AnalyticEvent.Init                   -> {
                val session = event.session
                CountlyConfig(context, "8abf1ee15646bc884556b82e5053857904264b66", "https://try.count.ly/").let { config ->
                    if (BuildConfig.DEBUG) {
                        config.setLoggingEnabled(true)
                    }
                    config.enableCrashReporting()
                    CountlyNative.initNative(context.applicationContext)
//                    config.setCustomCrashSegment(mapOf(
//                            "flavor" to BuildConfig.FLAVOR,
//                            "branch" to BuildConfig.GIT_BRANCH_NAME
//                    ))
                    config.setDeviceId(session.myUserId.sha256())
                    Countly.sharedInstance().init(config).also {
                        countly = it
                    }
                    Countly.userData.setUserData(
                            mapOf("home_server" to session.sessionParams.homeServerHost)
                    )
                    Countly.userData.save()
                }
            }
            is AnalyticEvent.RoomView               -> {
                countly?.views()?.recordView(
                        "view_room",
                        mapOf(
                                "room_id" to event.roomId.sha256(),
                                "is_encrypted" to event.isEncrypted,
                                "num_users" to event.memberCount,
                                "is_public" to event.isPublic
                        )
                )
                Unit
            }
            is AnalyticEvent.HomeView               -> {
                countly?.views()?.recordView(
                        "view_home",
                        mapOf<String, Any>("filter" to event.mode.name)
                )
                Unit
            }
            is AnalyticEvent.StartCall              -> {
                countly?.events()?.recordEvent(
                        "start_call",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_video" to event.isVideo,
                                "is_jitsi" to false
                        )
                )
            }
            is AnalyticEvent.StartJitsiConf         -> {
                countly?.events()?.recordEvent(
                        "start_call",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_video" to event.isVideo,
                                "is_jitsi" to true
                        )
                )
            }
            is AnalyticEvent.SendText               -> {
                countly?.events()?.recordEvent(
                        "send_message",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_edit" to false,
                                "is_reply" to false,
                                "message_type" to "Text"
                        )
                )
            }
            is AnalyticEvent.SendReply              -> {
                countly?.events()?.recordEvent(
                        "send_message",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_edit" to false,
                                "is_reply" to true,
                                "message_type" to "Text"
                        )
                )
            }
            is AnalyticEvent.SendQuote              -> {
                countly?.events()?.recordEvent(
                        "send_message",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_edit" to false,
                                "is_reply" to false,
                                "message_type" to "Text"
                        )
                )
            }
            is AnalyticEvent.SendMedia              -> {
                countly?.events()?.recordEvent(
                        "send_message",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_edit" to false,
                                "is_reply" to false,
                                "message_type" to when (event.type) {
                                    ContentAttachmentData.Type.FILE  -> "File"
                                    ContentAttachmentData.Type.IMAGE -> "Image"
                                    ContentAttachmentData.Type.AUDIO -> "Audio"
                                    ContentAttachmentData.Type.VIDEO -> "Video"
                                }
                        )
                )
            }
            is AnalyticEvent.SendEdit               -> {
                countly?.events()?.recordEvent(
                        "send_message",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_edit" to true,
                                "is_reply" to false,
                                "message_type" to "Text"
                        )
                )
            }
            is AnalyticEvent.JoinCall               -> {
                countly?.events()?.recordEvent(
                        "join_call",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_video" to event.isVideo,
                                "is_jitsi" to false
                        )
                )
            }
            is AnalyticEvent.JoinConference         -> {
                countly?.events()?.recordEvent(
                        "join_call",
                        mapOf<String, Any>(
                                "room_id" to event.roomId.sha256(),
                                "is_video" to event.isVideo,
                                "is_jitsi" to true
                        )
                )
            }
            is AnalyticEvent.StartActivity          -> {
                countly?.onStart(event.activity)
            }
            is AnalyticEvent.EndActivity            -> {
                countly?.onStop()
            }
            AnalyticEvent.StartRoomDirectory        -> {
                countly?.events()?.startEvent("room_directory")
                Unit
            }
            AnalyticEvent.EndRoomDirectory          -> {
                countly?.events()?.endEvent("room_directory")
                Unit
            }
            AnalyticEvent.StartRoomDirectorySearch  -> {
                countly?.events()?.startEvent("room_directory_search")
                Unit
            }
            is AnalyticEvent.EndRoomDirectorySearch -> {
                countly?.events()?.endEvent("room_directory_search", mapOf("result_count" to event.resultCount), 1, 0.0)
                Unit
            }
            AnalyticEvent.StartJoinRoomEvent        -> {
                countly?.events()?.startEvent("join_room")
                Unit
            }
            is AnalyticEvent.EndJoinRoomEvent       -> {
                countly?.events()?.endEvent(
                        "join_room",
                        mapOf<String, Any>("room_id" to event.roomId.sha256()),
                        1,
                        0.0
                )
                Unit
            }
            AnalyticEvent.CancelJoinRoomEvent       -> {
                countly?.events()?.cancelEvent("join_room")
                Unit
            }
            is AnalyticEvent.UnHandledCrash         -> {
                countly?.crashes()?.recordUnhandledException(event.exception)
                Unit
            }
            is AnalyticEvent.GetFeedBacks           -> {
                countly?.ratings()?.showFeedbackPopup(event.id, event.closeText, event.activity) {
                    // if (it) error is null no error...
                }
            }
            is AnalyticEvent.SettingsView           -> {
                countly?.views()?.recordView("settings_view", mapOf("category" to event.category))
                Unit
            }
            is AnalyticEvent.BeginInvite            -> {
                countly?.events()?.recordEvent(
                        "begin_invite",
                        mapOf(
                                "room_id" to event.roomId.sha256(),
                                "is_encrypted" to event.isEncrypted,
                                "num_users" to event.memberCount,
                                "is_public" to event.isPublic
                        ),
                        1,
                        0.0
                )
                Unit
            }
            is AnalyticEvent.SendInvite             -> {
                countly?.events()?.recordEvent(
                        "send_invite",
                        mapOf(
                                "room_id" to event.roomId.sha256(),
                                "is_encrypted" to event.isEncrypted,
                                "num_users" to event.memberCount,
                                "is_public" to event.isPublic
                        ),
                        1,
                        event.inviteeCount.toDouble()
                )
                Unit
            }
        }.exhaustive
    }
}

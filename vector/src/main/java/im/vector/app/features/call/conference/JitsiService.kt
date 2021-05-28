/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.call.conference

import im.vector.app.R
import im.vector.app.core.network.await
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.ensureProtocol
import im.vector.app.core.utils.toBase32String
import im.vector.app.features.call.conference.jwt.JitsiJWTFactory
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.settings.VectorLocale
import im.vector.app.features.themes.ThemeProvider
import okhttp3.Request
import org.jitsi.meet.sdk.JitsiMeetUserInfo
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.api.util.appendParamToUrl
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.internal.di.MoshiProvider
import java.net.URL
import java.util.UUID
import javax.inject.Inject

class JitsiService @Inject constructor(
        private val session: Session,
        private val rawService: RawService,
        private val stringProvider: StringProvider,
        private val themeProvider: ThemeProvider,
        private val jitsiWidgetPropertiesFactory: JitsiWidgetPropertiesFactory,
        private val jitsiJWTFactory: JitsiJWTFactory) {

    companion object {
        const val JITSI_OPEN_ID_TOKEN_JWT_AUTH = "openidtoken-jwt"
        private const val JITSI_AUTH_KEY = "auth"
    }

    suspend fun createJitsiWidget(roomId: String, withVideo: Boolean): Widget {
        // Build data for a jitsi widget
        val widgetId: String = WidgetType.Jitsi.preferred + "_" + session.myUserId + "_" + System.currentTimeMillis()
        val preferredJitsiDomain = tryOrNull {
            rawService.getElementWellknown(session.myUserId)
                    ?.jitsiServer
                    ?.preferredDomain
        }
        val jitsiDomain = preferredJitsiDomain ?: stringProvider.getString(R.string.preferred_jitsi_domain)
        val jitsiAuth = getJitsiAuth(jitsiDomain)
        val confId = createConferenceId(roomId, jitsiAuth)

        // We use the default element wrapper for this widget
        // https://github.com/vector-im/element-web/blob/develop/docs/jitsi-dev.md
        // https://github.com/matrix-org/matrix-react-sdk/blob/develop/src/utils/WidgetUtils.ts#L469
        val url = buildString {
            append("https://app.element.io/jitsi.html")
            appendParamToUrl("confId", confId)
            append("#conferenceDomain=\$domain")
            append("&conferenceId=\$conferenceId")
            append("&isAudioOnly=\$isAudioOnly")
            append("&displayName=\$matrix_display_name")
            append("&avatarUrl=\$matrix_avatar_url")
            append("&userId=\$matrix_user_id")
            append("&roomId=\$matrix_room_id")
            append("&theme=\$theme")
            if (jitsiAuth != null) {
                append("&auth=$jitsiAuth")
            }
        }
        val widgetEventContent = mapOf(
                "url" to url,
                "type" to WidgetType.Jitsi.legacy,
                "data" to mapOf(
                        "conferenceId" to confId,
                        "domain" to jitsiDomain,
                        "isAudioOnly" to !withVideo,
                        JITSI_AUTH_KEY to jitsiAuth
                ),
                "creatorUserId" to session.myUserId,
                "id" to widgetId,
                "name" to "jitsi"
        )

        return session.widgetService().createRoomWidget(roomId, widgetId, widgetEventContent)
    }

    suspend fun joinConference(roomId: String, jitsiWidget: Widget, enableVideo: Boolean): JitsiCallViewEvents.JoinConference {
        val me = session.getRoomMember(session.myUserId, roomId)?.toMatrixItem()
        val userDisplayName = me?.getBestName()
        val userAvatar = me?.avatarUrl?.let { session.contentUrlResolver().resolveFullSize(it) }
        val userInfo = JitsiMeetUserInfo().apply {
            this.displayName = userDisplayName
            this.avatar = userAvatar?.let { URL(it) }
        }
        val roomName = session.getRoomSummary(roomId)?.displayName
        val properties = session.widgetService().getWidgetComputedUrl(jitsiWidget, themeProvider.isLightTheme())
                ?.let { url -> jitsiWidgetPropertiesFactory.create(url) } ?: throw IllegalStateException()

        val token = if (jitsiWidget.isOpenIdJWTAuthenticationRequired()) {
            getOpenIdJWTToken(roomId, properties.domain, userDisplayName ?: session.myUserId, userAvatar ?: "")
        } else {
            null
        }
        return JitsiCallViewEvents.JoinConference(
                enableVideo = enableVideo,
                jitsiUrl = properties.domain.ensureProtocol(),
                subject = roomName ?: "",
                confId = properties.confId ?: "",
                userInfo = userInfo,
                token = token
        )
    }

    private fun Widget.isOpenIdJWTAuthenticationRequired(): Boolean {
        return widgetContent.data[JITSI_AUTH_KEY] == JITSI_OPEN_ID_TOKEN_JWT_AUTH
    }

    private suspend fun getOpenIdJWTToken(roomId: String, domain: String, userDisplayName: String, userAvatar: String): String {
        val openIdToken = session.openIdService().getOpenIdToken()
        return jitsiJWTFactory.create(
                openIdToken = openIdToken,
                jitsiServerDomain = domain,
                roomId = roomId,
                userAvatarUrl = userAvatar,
                userDisplayName = userDisplayName
        )
    }

    private fun createConferenceId(roomId: String, jitsiAuth: String?): String {
        return if (jitsiAuth == JITSI_OPEN_ID_TOKEN_JWT_AUTH) {
            // Create conference ID from room ID
            // For compatibility with Jitsi, use base32 without padding.
            // More details here:
            // https://github.com/matrix-org/prosody-mod-auth-matrix-user-verification
            roomId.toBase32String(padding = false)
        } else {
            // Create a random enough jitsi conference id
            // Note: the jitsi server automatically creates conference when the conference
            // id does not exist yet
            var widgetSessionId = UUID.randomUUID().toString()
            if (widgetSessionId.length > 8) {
                widgetSessionId = widgetSessionId.substring(0, 7)
            }
            roomId.substring(1, roomId.indexOf(":") - 1) + widgetSessionId.lowercase(VectorLocale.applicationLocale)
        }
    }

    private suspend fun getJitsiAuth(jitsiDomain: String): String? {
        val request = Request.Builder().url("$jitsiDomain/.well-known/element/jitsi".ensureProtocol()).build()
        return tryOrNull {
            val response = session.getOkHttpClient().newCall(request).await()
            val json = response.body?.string() ?: return null
            MoshiProvider.providesMoshi().adapter(JitsiWellKnown::class.java).fromJson(json)?.auth
        }
    }
}

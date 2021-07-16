/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.create

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.identity.IdentityServiceError
import org.matrix.android.sdk.api.session.identity.toMedium
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.crypto.DeviceListManager
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.internal.di.AuthenticatedIdentity
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.token.AccessTokenProvider
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.identity.EnsureIdentityTokenTask
import org.matrix.android.sdk.internal.session.identity.data.IdentityStore
import org.matrix.android.sdk.internal.session.identity.data.getIdentityServerUrlWithoutProtocol
import org.matrix.android.sdk.internal.session.room.membership.threepid.ThreePidInviteBody
import java.security.InvalidParameterException
import java.util.UUID
import javax.inject.Inject

internal class CreateRoomBodyBuilder @Inject constructor(
        private val ensureIdentityTokenTask: EnsureIdentityTokenTask,
        private val deviceListManager: DeviceListManager,
        private val identityStore: IdentityStore,
        private val fileUploader: FileUploader,
        @UserId
        private val userId: String,
        @AuthenticatedIdentity
        private val accessTokenProvider: AccessTokenProvider
) {

    suspend fun build(params: CreateRoomParams): CreateRoomBody {
        val invite3pids = params.invite3pids
                .takeIf { it.isNotEmpty() }
                ?.let { invites ->
                    // This can throw an exception if identity server is not configured
                    ensureIdentityTokenTask.execute(Unit)

                    val identityServerUrlWithoutProtocol = identityStore.getIdentityServerUrlWithoutProtocol()
                            ?: throw IdentityServiceError.NoIdentityServerConfigured
                    val identityServerAccessToken = accessTokenProvider.getToken() ?: throw IdentityServiceError.NoIdentityServerConfigured

                    invites.map {
                        ThreePidInviteBody(
                                idServer = identityServerUrlWithoutProtocol,
                                idAccessToken = identityServerAccessToken,
                                medium = it.toMedium(),
                                address = it.value
                        )
                    }
                }

        params.featurePreset?.updateRoomParams(params)

        val initialStates = (
                listOfNotNull(
                        buildEncryptionWithAlgorithmEvent(params),
                        buildHistoryVisibilityEvent(params),
                        buildAvatarEvent(params),
                        buildGuestAccess(params)
                )
                        + params.featurePreset?.setupInitialStates().orEmpty()
                )
                .takeIf { it.isNotEmpty() }

        return CreateRoomBody(
                visibility = params.visibility,
                roomAliasName = params.roomAliasName,
                name = params.name,
                topic = params.topic,
                invitedUserIds = params.invitedUserIds.filter { it != userId },
                invite3pids = invite3pids,
                creationContent = params.creationContent.takeIf { it.isNotEmpty() },
                initialStates = initialStates,
                preset = params.preset,
                isDirect = params.isDirect,
                powerLevelContentOverride = params.powerLevelContentOverride,
                roomVersion = params.roomVersion

        )
    }

    private suspend fun buildAvatarEvent(params: CreateRoomParams): Event? {
        return params.avatarUri?.let { avatarUri ->
            // First upload the image, ignoring any error
            tryOrNull {
                fileUploader.uploadFromUri(
                        uri = avatarUri,
                        filename = UUID.randomUUID().toString(),
                        mimeType = MimeTypes.Jpeg)
            }
                    ?.let { response ->
                        Event(
                                type = EventType.STATE_ROOM_AVATAR,
                                stateKey = "",
                                content = mapOf("url" to response.contentUri)
                        )
                    }
        }
    }

    private fun buildHistoryVisibilityEvent(params: CreateRoomParams): Event? {
        return params.historyVisibility
                ?.let {
                    Event(
                            type = EventType.STATE_ROOM_HISTORY_VISIBILITY,
                            stateKey = "",
                            content = mapOf("history_visibility" to it)
                    )
                }
    }

    private fun buildGuestAccess(params: CreateRoomParams): Event? {
        return params.guestAccess
                ?.let {
                    Event(
                            type = EventType.STATE_ROOM_GUEST_ACCESS,
                            stateKey = "",
                            content = mapOf("guest_access" to it.value)
                    )
                }
    }


    /**
     * Add the crypto algorithm to the room creation parameters.
     */
    private suspend fun buildEncryptionWithAlgorithmEvent(params: CreateRoomParams): Event? {
        if (params.algorithm == null
                && canEnableEncryption(params)) {
            // Enable the encryption
            params.enableEncryption()
        }
        return params.algorithm
                ?.let {
                    if (it != MXCRYPTO_ALGORITHM_MEGOLM) {
                        throw InvalidParameterException("Unsupported algorithm: $it")
                    }
                    Event(
                            type = EventType.STATE_ROOM_ENCRYPTION,
                            stateKey = "",
                            content = mapOf("algorithm" to it)
                    )
                }
    }

    private suspend fun canEnableEncryption(params: CreateRoomParams): Boolean {
        return params.enableEncryptionIfInvitedUsersSupportIt
                // Parity with web, enable if users have encryption ready devices
                // for now remove checks on cross signing and 3pid invites
                // && crossSigningService.isCrossSigningVerified()
                && params.invite3pids.isEmpty()
                && params.invitedUserIds.isNotEmpty()
                && params.invitedUserIds.let { userIds ->
            val keys = deviceListManager.downloadKeys(userIds, forceDownload = false)

            userIds.all { userId ->
                keys.map[userId].let { deviceMap ->
                    if (deviceMap.isNullOrEmpty()) {
                        // A user has no device, so do not enable encryption
                        false
                    } else {
                        // Check that every user's device have at least one key
                        deviceMap.values.all { !it.keys.isNullOrEmpty() }
                    }
                }
            }
        }
    }
}

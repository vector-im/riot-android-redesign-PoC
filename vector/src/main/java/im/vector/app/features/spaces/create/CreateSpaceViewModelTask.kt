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

package im.vector.app.features.spaces.create

import android.net.Uri
import im.vector.app.core.platform.ViewModelTask
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.raw.wellknown.isE2EByDefault
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import org.matrix.android.sdk.api.session.room.model.RoomDirectoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomPreset
import org.matrix.android.sdk.api.session.room.model.create.RestrictedRoomPreset
import timber.log.Timber
import javax.inject.Inject

sealed class CreateSpaceTaskResult {

    data class Success(val spaceId: String, val childIds: List<String>) : CreateSpaceTaskResult()

    data class PartialSuccess(val spaceId: String, val childIds: List<String>, val failedRooms: Map<String, Throwable>) : CreateSpaceTaskResult()

    class FailedToCreateSpace(val failure: Throwable) : CreateSpaceTaskResult()
}

data class CreateSpaceTaskParams(
        val spaceName: String,
        val spaceTopic: String?,
        val spaceAvatar: Uri? = null,
        val spaceAlias: String? = null,
        val isPublic: Boolean,
        val defaultRooms: List<String> = emptyList()
)

class CreateSpaceViewModelTask @Inject constructor(
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val rawService: RawService
) : ViewModelTask<CreateSpaceTaskParams, CreateSpaceTaskResult> {

    override suspend fun execute(params: CreateSpaceTaskParams): CreateSpaceTaskResult {
        val spaceID = try {
            session.spaceService().createSpace(
                    params.spaceName,
                    params.spaceTopic,
                    params.spaceAvatar,
                    params.isPublic,
                    params.spaceAlias
            )
        } catch (failure: Throwable) {
            return CreateSpaceTaskResult.FailedToCreateSpace(failure)
        }

        val createdSpace = session.spaceService().getSpace(spaceID)

        val childErrors = mutableMapOf<String, Throwable>()
        val childIds = mutableListOf<String>()

        val e2eByDefault = tryOrNull {
            rawService.getElementWellknown(session.sessionParams)
                    ?.isE2EByDefault()
                    ?: true
        } ?: true

        params.defaultRooms
                .filter { it.isNotBlank() }
                .forEach { roomName ->
                    try {
                        val roomId = try {
                            if (params.isPublic) {
                                session.createRoom(
                                        CreateRoomParams().apply {
                                            this.name = roomName
                                            this.preset = CreateRoomPreset.PRESET_PUBLIC_CHAT
                                        }
                                )
                            } else {
                                val homeServerCapabilities = session
                                        .getHomeServerCapabilities()
                                val restrictedSupport = homeServerCapabilities
                                        .isFeatureSupported(HomeServerCapabilities.ROOM_CAP_RESTRICTED)

                                val createRestricted = when (restrictedSupport) {
                                    HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED          -> true
                                    HomeServerCapabilities.RoomCapabilitySupport.SUPPORTED_UNSTABLE -> vectorPreferences.labsUseExperimentalRestricted()
                                    else                                                            -> false
                                }
                                if (createRestricted) {
                                    session.createRoom(CreateRoomParams().apply {
                                        this.name = roomName
                                        this.featurePreset = RestrictedRoomPreset(
                                                homeServerCapabilities,
                                                listOf(
                                                        RoomJoinRulesAllowEntry(
                                                                spaceID = spaceID,
                                                                via = session.sessionParams.homeServerHost?.let { listOf(it) } ?: emptyList()
                                                        )
                                                )
                                        )
                                        if (e2eByDefault) {
                                            this.enableEncryption()
                                        }
                                    })
                                } else {
                                    session.createRoom(CreateRoomParams().apply {
                                        this.name = roomName
                                        visibility = RoomDirectoryVisibility.PRIVATE
                                        this.preset = CreateRoomPreset.PRESET_PRIVATE_CHAT
                                        if (e2eByDefault) {
                                            this.enableEncryption()
                                        }
                                    })
                                }
                            }
                        } catch (timeout: CreateRoomFailure.CreatedWithTimeout) {
                            // we ignore that?
                            timeout.roomID
                        }
                        val via = session.sessionParams.homeServerHost?.let { listOf(it) } ?: emptyList()
                        createdSpace!!.addChildren(roomId, via, null, autoJoin = false, suggested = true)
                        // set canonical
                        session.spaceService().setSpaceParent(
                                roomId,
                                createdSpace.spaceId,
                                true,
                                via
                        )
                        childIds.add(roomId)
                    } catch (failure: Throwable) {
                        Timber.d("Space: Failed to create child room in $spaceID")
                        childErrors[roomName] = failure
                    }
                }

        return if (childErrors.isEmpty()) {
            CreateSpaceTaskResult.Success(spaceID, childIds)
        } else {
            CreateSpaceTaskResult.PartialSuccess(spaceID, childIds, childErrors)
        }
    }
}

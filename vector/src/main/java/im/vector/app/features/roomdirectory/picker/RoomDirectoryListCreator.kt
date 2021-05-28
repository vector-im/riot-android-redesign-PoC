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

package im.vector.app.features.roomdirectory.picker

import im.vector.app.R
import im.vector.app.core.resources.StringArrayProvider
import im.vector.app.features.roomdirectory.RoomDirectoryData
import im.vector.app.features.roomdirectory.RoomDirectoryServer
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.thirdparty.ThirdPartyProtocol
import javax.inject.Inject

class RoomDirectoryListCreator @Inject constructor(
        private val stringArrayProvider: StringArrayProvider,
        private val session: Session
) {

    fun computeDirectories(thirdPartyProtocolData: Map<String, ThirdPartyProtocol>,
                           customHomeservers: Set<String>): List<RoomDirectoryServer> {
        val result = ArrayList<RoomDirectoryServer>()

        val protocols = ArrayList<RoomDirectoryData>()

        // Add user homeserver name
        val userHsName = session.myUserId.substringAfter(":")

        // Add default protocol
        protocols.add(
                RoomDirectoryData(
                        homeServer = null,
                        displayName = RoomDirectoryData.MATRIX_PROTOCOL_NAME,
                        includeAllNetworks = false
                )
        )

        // Add result of the request
        thirdPartyProtocolData.forEach {
            it.value.instances?.forEach { thirdPartyProtocolInstance ->
                protocols.add(
                        RoomDirectoryData(
                                homeServer = null,
                                displayName = thirdPartyProtocolInstance.desc ?: "",
                                thirdPartyInstanceId = thirdPartyProtocolInstance.instanceId,
                                includeAllNetworks = false,
                                // Default to protocol icon
                                avatarUrl = thirdPartyProtocolInstance.icon ?: it.value.icon
                        )
                )
            }
        }

        // Add all rooms
        protocols.add(
                RoomDirectoryData(
                        homeServer = null,
                        displayName = RoomDirectoryData.MATRIX_PROTOCOL_NAME,
                        includeAllNetworks = true
                )
        )

        result.add(
                RoomDirectoryServer(
                        serverName = userHsName,
                        isUserServer = true,
                        isManuallyAdded = false,
                        protocols = protocols
                )
        )

        // Add custom directory servers, form the config file, excluding the current user homeserver
        stringArrayProvider.getStringArray(R.array.room_directory_servers)
                .filter { it != userHsName }
                .forEach {
                    // Use the server name as a default display name
                    result.add(
                            RoomDirectoryServer(
                                    serverName = it,
                                    isUserServer = false,
                                    isManuallyAdded = false,
                                    protocols = listOf(
                                            RoomDirectoryData(
                                                    homeServer = it,
                                                    displayName = RoomDirectoryData.MATRIX_PROTOCOL_NAME,
                                                    includeAllNetworks = false
                                            )
                                    )
                            )
                    )
                }

        // Add manually added server by the user
        customHomeservers
                .forEach {
                    // Use the server name as a default display name
                    result.add(
                            RoomDirectoryServer(
                                    serverName = it,
                                    isUserServer = false,
                                    isManuallyAdded = true,
                                    protocols = listOf(
                                            RoomDirectoryData(
                                                    homeServer = it,
                                                    displayName = RoomDirectoryData.MATRIX_PROTOCOL_NAME,
                                                    includeAllNetworks = false
                                            )
                                    )
                            )
                    )
                }

        return result
    }
}

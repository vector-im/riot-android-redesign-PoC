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

package im.vector.app.features.roomdirectory

/**
 * This class describes a rooms directory server protocol.
 */
data class RoomDirectoryData(
        /**
         * The server name (might be null)
         * Set null when the server is the current user's home server.
         */
        val homeServer: String? = null,

        /**
         * The display name (the server description)
         */
        val displayName: String = MATRIX_PROTOCOL_NAME,

        /**
         * the avatar url
         */
        val avatarUrl: String? = null,

        /**
         * The third party server identifier
         */
        val thirdPartyInstanceId: String? = null,

        /**
         * Tell if all the federated servers must be included
         */
        val includeAllNetworks: Boolean = false
) {

    companion object {
        const val MATRIX_PROTOCOL_NAME = "Matrix"
    }
}

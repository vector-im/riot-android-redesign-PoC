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

package org.matrix.android.sdk.api.session.room.model.create

import android.net.Uri
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomDirectoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM

// TODO Give a way to include other initial states
open class CreateRoomParams {
    /**
     * A public visibility indicates that the room will be shown in the published room list.
     * A private visibility will hide the room from the published room list.
     * Rooms default to private visibility if this key is not included.
     * NB: This should not be confused with join_rules which also uses the word public. One of: ["public", "private"]
     */
    var visibility: RoomDirectoryVisibility? = null

    /**
     * The desired room alias local part. If this is included, a room alias will be created and mapped to the newly created room.
     * The alias will belong on the same homeserver which created the room.
     * For example, if this was set to "foo" and sent to the homeserver "example.com" the complete room alias would be #foo:example.com.
     */
    var roomAliasName: String? = null

    /**
     * If this is not null, an m.room.name event will be sent into the room to indicate the name of the room.
     * See Room Events for more information on m.room.name.
     */
    var name: String? = null

    /**
     * If this is not null, an m.room.topic event will be sent into the room to indicate the topic for the room.
     * See Room Events for more information on m.room.topic.
     */
    var topic: String? = null

    /**
     * If this is not null, the image uri will be sent to the media server and will be set as a room avatar.
     */
    var avatarUri: Uri? = null

    /**
     * A list of user IDs to invite to the room.
     * This will tell the server to invite everyone in the list to the newly created room.
     */
    val invitedUserIds = mutableListOf<String>()

    /**
     * A list of objects representing third party IDs to invite into the room.
     */
    val invite3pids = mutableListOf<ThreePid>()

    /**
     * Initial Guest Access
     */
    var guestAccess: GuestAccess? = null

    /**
     * If set to true, when the room will be created, if cross-signing is enabled and we can get keys for every invited users,
     * the encryption will be enabled on the created room
     */
    var enableEncryptionIfInvitedUsersSupportIt: Boolean = false

    /**
     * Convenience parameter for setting various default state events based on a preset. Must be either:
     * private_chat => join_rules is set to invite. history_visibility is set to shared.
     * trusted_private_chat => join_rules is set to invite. history_visibility is set to shared. All invitees are given the same power level as the
     * room creator.
     * public_chat: => join_rules is set to public. history_visibility is set to shared.
     */
    var preset: CreateRoomPreset? = null

    /**
     * This flag makes the server set the is_direct flag on the m.room.member events sent to the users in invite and invite_3pid.
     * See Direct Messaging for more information.
     */
    var isDirect: Boolean? = null

    /**
     * Extra keys to be added to the content of the m.room.create.
     * The server will clobber the following keys: creator.
     * Future versions of the specification may allow the server to clobber other keys.
     */
    val creationContent = mutableMapOf<String, Any>()

    /**
     * Set to true to disable federation of this room.
     * Default: false
     */
    var disableFederation = false
        set(value) {
            field = value
            if (value) {
                creationContent[CREATION_CONTENT_KEY_M_FEDERATE] = false
            } else {
                // This is the default value, we remove the field
                creationContent.remove(CREATION_CONTENT_KEY_M_FEDERATE)
            }
        }

    var roomType: String? = null // RoomType.MESSAGING
        set(value) {
            field = value
            if (value != null) {
                creationContent[CREATION_CONTENT_KEY_ROOM_TYPE] = value
            } else {
                // This is the default value, we remove the field
                creationContent.remove(CREATION_CONTENT_KEY_ROOM_TYPE)
            }
        }

    /**
     * The power level content to override in the default power level event
     */
    var powerLevelContentOverride: PowerLevelsContent? = null

    /**
     * Mark as a direct message room.
     */
    fun setDirectMessage() {
        preset = CreateRoomPreset.PRESET_TRUSTED_PRIVATE_CHAT
        isDirect = true
    }

    /**
     * Supported value: MXCRYPTO_ALGORITHM_MEGOLM
     */
    var algorithm: String? = null
        private set

    var historyVisibility: RoomHistoryVisibility? = null

    fun enableEncryption() {
        algorithm = MXCRYPTO_ALGORITHM_MEGOLM
    }

    var roomVersion: String? = null

    var featurePreset: RoomFeaturePreset? = null

    companion object {
        private const val CREATION_CONTENT_KEY_M_FEDERATE = "m.federate"
        private const val CREATION_CONTENT_KEY_ROOM_TYPE = "type"
    }
}

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

package im.vector.app.features.roomdirectory.createroom

import android.net.Uri
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class CreateRoomViewState(
        val avatarUri: Uri? = null,
        val roomName: String = "",
        val roomTopic: String = "",
        val roomJoinRules: RoomJoinRules = RoomJoinRules.INVITE,
        val isEncrypted: Boolean = false,
        val showAdvanced: Boolean = false,
        val disableFederation: Boolean = false,
        val homeServerName: String = "",
        val hsAdminHasDisabledE2E: Boolean = false,
        val asyncCreateRoomRequest: Async<String> = Uninitialized,
        val parentSpaceId: String?,
        val parentSpaceSummary: RoomSummary? = null,
        val supportsRestricted: Boolean = false,
        val aliasLocalPart: String? = null
) : MvRxState {

    constructor(args: CreateRoomArgs) : this(
            roomName = args.initialName,
            parentSpaceId = args.parentSpaceId
    )

    /**
     * Return true if there is not important input from user
     */
    fun isEmpty() = avatarUri == null
            && roomName.isEmpty()
            && roomTopic.isEmpty()
            && aliasLocalPart.isNullOrEmpty()
}

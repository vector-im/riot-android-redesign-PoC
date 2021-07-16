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

package im.vector.app.features.roomprofile.settings.joinrule

import im.vector.app.core.ui.bottomsheet.BottomSheetGenericState
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules

data class RoomJoinRuleState(
        val currentRoomJoinRule: RoomJoinRules = RoomJoinRules.INVITE,
        val allowedJoinedRules: List<JoinRulesOptionSupport> =
                listOf(RoomJoinRules.INVITE, RoomJoinRules.PUBLIC).map { it.toOption(true) },
        val currentGuestAccess: GuestAccess? = null
) : BottomSheetGenericState() {

    constructor(args: RoomJoinRuleBottomSheetArgs) : this(
            currentRoomJoinRule = args.currentRoomJoinRule,
            allowedJoinedRules = args.allowedJoinedRules
    )
}

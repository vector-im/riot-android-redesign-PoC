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

package im.vector.app.features.roomprofile.settings.joinrule.advanced

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.app.features.roomprofile.settings.joinrule.JoinRulesOptionSupport
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.MatrixItem

data class RoomJoinRuleChooseRestrictedState(
        // the currentRoomId
        val roomId: String,
        val roomSummary: Async<RoomSummary> = Uninitialized,
        val initialRoomJoinRules: RoomJoinRules? = null,
        val currentRoomJoinRules: RoomJoinRules? = null,
        val updatedAllowList: List<MatrixItem> = emptyList(),
        val choices: List<JoinRulesOptionSupport>? = null,
        val initialAllowList: List<RoomJoinRulesAllowEntry> = emptyList(),
        val possibleSpaceCandidate: List<MatrixItem> = emptyList(),
        val unknownRestricted: List<MatrixItem> = emptyList(),
        val filter: String = "",
        val filteredResults: Async<List<MatrixItem>> = Uninitialized,
        val hasUnsavedChanges: Boolean = false,
        val updatingStatus: Async<Unit> = Uninitialized,
        val upgradeNeededForRestricted: Boolean = false,
        val restrictedSupportedByThisVersion: Boolean = false,
        val restrictedVersionNeeded: String? = null,
        val didSwitchToReplacementRoom: Boolean = false
) : MvRxState {
    constructor(args: RoomProfileArgs) : this(roomId = args.roomId)
}

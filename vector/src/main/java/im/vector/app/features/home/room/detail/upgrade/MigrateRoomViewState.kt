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

package im.vector.app.features.home.room.detail.upgrade

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized

data class MigrateRoomViewState(
        val roomId: String,
        val newVersion: String,
        val customDescription: CharSequence? = null,
        val currentVersion: String? = null,
        val isPublic: Boolean = false,
        val shouldIssueInvites: Boolean = false,
        val shouldUpdateKnownParents: Boolean = true,
        val otherMemberCount: Int = 0,
        val knownParents: List<String> = emptyList(),
        val upgradingStatus: Async<UpgradeRoomViewModelTask.Result> = Uninitialized,
        val upgradingProgress: Int = 0,
        val upgradingProgressTotal: Int = 0,
        val upgradingProgressIndeterminate: Boolean = true,
        val migrationReason: MigrateRoomBottomSheet.MigrationReason = MigrateRoomBottomSheet.MigrationReason.MANUAL,
        val autoMigrateMembersAndParents: Boolean = false
) : MvRxState {
    constructor(args: MigrateRoomBottomSheet.Args) : this(
            roomId = args.roomId,
            newVersion = args.newVersion,
            migrationReason = args.reason,
            autoMigrateMembersAndParents = args.reason == MigrateRoomBottomSheet.MigrationReason.FOR_RESTRICTED,
            customDescription = args.customDescription
    )
}

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

import im.vector.app.core.platform.VectorViewModelAction
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.util.MatrixItem

sealed class RoomJoinRuleChooseRestrictedActions : VectorViewModelAction {
    data class FilterWith(val filter: String) : RoomJoinRuleChooseRestrictedActions()
    data class ToggleSelection(val matrixItem: MatrixItem) : RoomJoinRuleChooseRestrictedActions()
    data class SelectJoinRules(val rules: RoomJoinRules) : RoomJoinRuleChooseRestrictedActions()
    object DoUpdateJoinRules : RoomJoinRuleChooseRestrictedActions()
    data class SwitchToRoomAfterMigration(val roomId: String) : RoomJoinRuleChooseRestrictedActions()
}

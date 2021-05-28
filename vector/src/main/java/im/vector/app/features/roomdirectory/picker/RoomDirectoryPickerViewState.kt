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

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.roomdirectory.RoomDirectoryServer
import org.matrix.android.sdk.api.session.room.model.thirdparty.ThirdPartyProtocol

data class RoomDirectoryPickerViewState(
        val asyncThirdPartyRequest: Async<Map<String, ThirdPartyProtocol>> = Uninitialized,
        val customHomeservers: Set<String> = emptySet(),
        val inEditMode: Boolean = false,
        val enteredServer: String = "",
        val addServerAsync: Async<Unit> = Uninitialized,
        // computed
        val directories: List<RoomDirectoryServer> = emptyList()
) : MvRxState

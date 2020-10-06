/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home

import arrow.core.Option
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.sync.SyncState

data class HomeDetailViewState(
        val groupSummary: Option<GroupSummary> = Option.empty(),
        val asyncRooms: Async<List<RoomSummary>> = Uninitialized,
        val displayMode: HomeDisplayMode = HomeDisplayMode.CHATS,
        val tabList: List<HomeDisplayMode> = listOf(HomeDisplayMode.CHATS, HomeDisplayMode.FAVORITES, HomeDisplayMode.ROOMS, HomeDisplayMode.PEOPLE),
        val notificationCountCatchup: Int = 0,
        val notificationHighlightCatchup: Boolean = false,
        val notificationCountPeople: Int = 0,
        val notificationHighlightPeople: Boolean = false,
        val notificationCountRooms: Int = 0,
        val notificationHighlightRooms: Boolean = false,
        val syncState: SyncState = SyncState.Idle
) : MvRxState

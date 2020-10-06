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

import androidx.annotation.StringRes
import im.vector.app.R

enum class HomeDisplayMode(@StringRes val titleRes: Int) {
        CHATS(R.string.home_bottom_tab_chats),
        NOTIFICATIONS(R.string.bottom_action_notification),
        FAVORITES(R.string.room_recents_favourites),
        PEOPLE(R.string.bottom_action_people_x),
        ROOMS(R.string.bottom_action_rooms),
        // YOU(R.string.home_bottom_tab_you),
}

enum class RoomListDisplayMode(@StringRes val titleRes: Int) {
        ALL(R.string.room_list_tabs_all),
        FAVORITES(R.string.room_recents_favourites),
        NOTIFICATIONS(R.string.bottom_action_notification),
        LOW_PRIORITY(R.string.room_recents_low_priority),
        INVITES(R.string.invitations_header),
        PEOPLE(R.string.bottom_action_people_x),
        ROOMS(R.string.bottom_action_rooms),
        FILTERED(/* Not used */ 0)
    }

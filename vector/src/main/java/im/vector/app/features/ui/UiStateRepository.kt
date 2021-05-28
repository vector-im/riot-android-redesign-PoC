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

package im.vector.app.features.ui

import im.vector.app.features.home.RoomListDisplayMode

/**
 * This interface is used to persist UI state across application restart
 */
interface UiStateRepository {

    /**
     * Reset all the saved data
     */
    fun reset()

    fun getDisplayMode(): RoomListDisplayMode

    fun storeDisplayMode(displayMode: RoomListDisplayMode)

    // TODO Handle SharedPreference per session in a better way, also to cleanup when login out
    fun storeSelectedSpace(spaceId: String?, sessionId: String)
    fun storeSelectedGroup(groupId: String?, sessionId: String)

    fun storeGroupingMethod(isSpace: Boolean, sessionId: String)

    fun getSelectedSpace(sessionId: String): String?
    fun getSelectedGroup(sessionId: String): String?
    fun isGroupingMethodSpace(sessionId: String): Boolean

    fun setCustomRoomDirectoryHomeservers(sessionId: String, servers: Set<String>)
    fun getCustomRoomDirectoryHomeservers(sessionId: String): Set<String>
}

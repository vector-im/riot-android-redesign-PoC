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

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent

interface RoomFeaturePreset {

    fun updateRoomParams(params: CreateRoomParams)

    fun setupInitialStates(): List<Event>?
}

class RestrictedRoomPreset(val homeServerCapabilities: HomeServerCapabilities, val restrictedList: List<RoomJoinRulesAllowEntry>) : RoomFeaturePreset {

    override fun updateRoomParams(params: CreateRoomParams) {
        params.historyVisibility = params.historyVisibility ?: RoomHistoryVisibility.SHARED
        params.guestAccess = params.guestAccess ?: GuestAccess.Forbidden
        params.roomVersion = homeServerCapabilities.versionOverrideForFeature(HomeServerCapabilities.ROOM_CAP_RESTRICTED)
    }

    override fun setupInitialStates(): List<Event>? {
        return listOf(
                Event(
                        type = EventType.STATE_ROOM_JOIN_RULES,
                        stateKey = "",
                        content = RoomJoinRulesContent(
                                _joinRules = RoomJoinRules.RESTRICTED.value,
                                allowList = restrictedList
                        ).toContent()
                )
        )
    }
}

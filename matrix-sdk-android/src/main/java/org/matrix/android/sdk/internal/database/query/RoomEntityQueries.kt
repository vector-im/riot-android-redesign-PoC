/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.database.query

import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomEntityFields
import io.realm.Realm
import io.realm.RealmQuery
import io.realm.kotlin.where

internal fun RoomEntity.Companion.where(realm: Realm, roomId: String): RealmQuery<RoomEntity> {
    return realm.where<RoomEntity>()
            .equalTo(RoomEntityFields.ROOM_ID, roomId)
}

internal fun RoomEntity.Companion.getOrCreate(realm: Realm, roomId: String): RoomEntity {
    return where(realm, roomId).findFirst() ?: realm.createObject(RoomEntity::class.java, roomId)
}

internal fun RoomEntity.Companion.where(realm: Realm, membership: Membership? = null): RealmQuery<RoomEntity> {
    val query = realm.where<RoomEntity>()
    if (membership != null) {
        query.equalTo(RoomEntityFields.MEMBERSHIP_STR, membership.name)
    }
    return query
}

internal fun RoomEntity.fastContains(eventId: String): Boolean {
    return EventEntity.where(realm, eventId = eventId).findFirst() != null
}

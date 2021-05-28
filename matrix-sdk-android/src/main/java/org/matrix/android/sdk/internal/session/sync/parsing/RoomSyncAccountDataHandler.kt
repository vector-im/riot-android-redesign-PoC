/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.sync.parsing

import io.realm.Realm
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.tag.RoomTagContent
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.model.RoomAccountDataEntity
import org.matrix.android.sdk.internal.database.model.RoomAccountDataEntityFields
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.query.getOrCreate
import org.matrix.android.sdk.internal.session.room.read.FullyReadContent
import org.matrix.android.sdk.internal.session.sync.RoomFullyReadHandler
import org.matrix.android.sdk.internal.session.sync.RoomTagHandler
import org.matrix.android.sdk.internal.session.sync.model.RoomSyncAccountData
import javax.inject.Inject

internal class RoomSyncAccountDataHandler @Inject constructor(private val roomTagHandler: RoomTagHandler,
                                                              private val roomFullyReadHandler: RoomFullyReadHandler) {

    fun handle(realm: Realm, roomId: String, accountData: RoomSyncAccountData) {
        if (accountData.events.isNullOrEmpty()) {
            return
        }
        val roomEntity = RoomEntity.getOrCreate(realm, roomId)
        for (event in accountData.events) {
            val eventType = event.getClearType()
            handleGeneric(roomEntity, event.getClearContent(), eventType)
            if (eventType == RoomAccountDataTypes.EVENT_TYPE_TAG) {
                val content = event.getClearContent().toModel<RoomTagContent>()
                roomTagHandler.handle(realm, roomId, content)
            } else if (eventType == RoomAccountDataTypes.EVENT_TYPE_FULLY_READ) {
                val content = event.getClearContent().toModel<FullyReadContent>()
                roomFullyReadHandler.handle(realm, roomId, content)
            }
        }
    }

    private fun handleGeneric(roomEntity: RoomEntity, content: JsonDict?, eventType: String) {
        val existing = roomEntity.accountData.where().equalTo(RoomAccountDataEntityFields.TYPE, eventType).findFirst()
        if (existing != null) {
            // Update current value
            existing.contentStr = ContentMapper.map(content)
        } else {
            val roomAccountData = RoomAccountDataEntity(
                    type = eventType,
                    contentStr = ContentMapper.map(content)
            )
            roomEntity.accountData.add(roomAccountData)
        }
    }
}

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

package org.matrix.android.sdk.internal.session.room.accountdata

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Transformations
import com.zhuinden.monarchy.Monarchy
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.RealmSessionProvider
import org.matrix.android.sdk.internal.database.mapper.AccountDataMapper
import org.matrix.android.sdk.internal.database.model.RoomAccountDataEntityFields
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

internal class RoomAccountDataDataSource @Inject constructor(@SessionDatabase private val monarchy: Monarchy,
                                                             private val realmSessionProvider: RealmSessionProvider,
                                                             private val accountDataMapper: AccountDataMapper) {

    fun getAccountDataEvent(roomId: String, type: String): AccountDataEvent? {
        return getAccountDataEvents(roomId, setOf(type)).firstOrNull()
    }

    fun getLiveAccountDataEvent(roomId: String, type: String): LiveData<Optional<AccountDataEvent>> {
        return Transformations.map(getLiveAccountDataEvents(roomId, setOf(type))) {
            it.firstOrNull()?.toOptional()
        }
    }

    fun getAccountDataEvents(roomId: String, types: Set<String>): List<AccountDataEvent> {
        return realmSessionProvider.withRealm { realm ->
            val roomEntity = RoomEntity.where(realm, roomId).findFirst() ?: return@withRealm emptyList()
            roomEntity.accountDataEvents(types)
        }
    }

    fun getLiveAccountDataEvents(roomId: String, types: Set<String>): LiveData<List<AccountDataEvent>> {
        val liveRoomEntity = monarchy.findAllManagedWithChanges { RoomEntity.where(it, roomId) }
        val resultLiveData = MediatorLiveData<List<AccountDataEvent>>()
        resultLiveData.addSource(liveRoomEntity) {
            val roomEntity = it.realmResults.firstOrNull()
            if (roomEntity == null) {
                resultLiveData.postValue(emptyList())
            } else {
                val mappedResult = roomEntity.accountDataEvents(types)
                resultLiveData.postValue(mappedResult)
            }
        }
        return resultLiveData
    }

    private fun RoomEntity.accountDataEvents(types: Set<String>): List<AccountDataEvent> {
        val query = accountData.where()
        if (types.isNotEmpty()) {
            query.`in`(RoomAccountDataEntityFields.TYPE, types.toTypedArray())
        }
        return query.findAll().map { accountDataMapper.map(it) }
    }
}

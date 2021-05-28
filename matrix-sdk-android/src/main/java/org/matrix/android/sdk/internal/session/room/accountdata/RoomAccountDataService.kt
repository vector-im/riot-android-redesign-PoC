/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.api.session.accountdata.AccountDataService
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.util.Optional

internal class RoomAccountDataService @AssistedInject constructor(@Assisted private val roomId: String,
                                                                  private val dataSource: RoomAccountDataDataSource,
                                                                  private val updateRoomAccountDataTask: UpdateRoomAccountDataTask
) : AccountDataService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): RoomAccountDataService
    }

    override fun getAccountDataEvent(type: String): AccountDataEvent? {
        return dataSource.getAccountDataEvent(roomId, type)
    }

    override fun getLiveAccountDataEvent(type: String): LiveData<Optional<AccountDataEvent>> {
        return dataSource.getLiveAccountDataEvent(roomId, type)
    }

    override fun getAccountDataEvents(types: Set<String>): List<AccountDataEvent> {
        return dataSource.getAccountDataEvents(roomId, types)
    }

    override fun getLiveAccountDataEvents(types: Set<String>): LiveData<List<AccountDataEvent>> {
        return dataSource.getLiveAccountDataEvents(roomId, types)
    }

    override suspend fun updateAccountData(type: String, content: Content) {
        val params = UpdateRoomAccountDataTask.Params(roomId, type, content)
        return updateRoomAccountDataTask.execute(params)
    }
}

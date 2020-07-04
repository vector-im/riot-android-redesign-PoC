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

package im.vector.matrix.android.internal.session.room.read

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.room.model.ReadReceipt
import im.vector.matrix.android.api.session.room.read.ReadService
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.api.util.toOptional
import im.vector.matrix.android.internal.database.mapper.ReadReceiptsSummaryMapper
import im.vector.matrix.android.internal.database.model.ReadMarkerEntity
import im.vector.matrix.android.internal.database.model.ReadReceiptEntity
import im.vector.matrix.android.internal.database.model.ReadReceiptsSummaryEntity
import im.vector.matrix.android.internal.database.query.isEventRead
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith

internal class DefaultReadService @AssistedInject constructor(
        @Assisted private val roomId: String,
        @SessionDatabase private val monarchy: Monarchy,
        private val taskExecutor: TaskExecutor,
        private val setReadMarkersTask: SetReadMarkersTask,
        private val readReceiptsSummaryMapper: ReadReceiptsSummaryMapper,
        @UserId private val userId: String
) : ReadService {

    @AssistedInject.Factory
    interface Factory {
        fun create(roomId: String): ReadService
    }

    override fun markAsRead(params: ReadService.MarkAsReadParams, callback: MatrixCallback<Unit>) {
        val taskParams = SetReadMarkersTask.Params(
                roomId = roomId,
                forceReadMarker = params.forceReadMarker(),
                forceReadReceipt = params.forceReadReceipt()
        )
        setReadMarkersTask
                .configureWith(taskParams) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun setReadReceipt(eventId: String, callback: MatrixCallback<Unit>) {
        val params = SetReadMarkersTask.Params(roomId, fullyReadEventId = null, readReceiptEventId = eventId)
        setReadMarkersTask
                .configureWith(params) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun setReadMarker(fullyReadEventId: String, callback: MatrixCallback<Unit>) {
        val params = SetReadMarkersTask.Params(roomId, fullyReadEventId = fullyReadEventId, readReceiptEventId = null)
        setReadMarkersTask
                .configureWith(params) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun isEventRead(eventId: String): Boolean {
        return isEventRead(monarchy.realmConfiguration, userId, roomId, eventId)
    }

    override fun getReadMarker(): Optional<String> {
        var res: String? = null
        monarchy.doWithRealm { realm ->
            val readMarkerEntity = ReadMarkerEntity.where(realm, roomId = roomId).findFirst()
            res = readMarkerEntity?.eventId
        }
        return Optional(res)
    }

    override fun getReadMarkerLive(): LiveData<Optional<String>> {
        val liveRealmData = monarchy.findAllMappedWithChanges(
                { ReadMarkerEntity.where(it, roomId) },
                { it.eventId }
        )
        return Transformations.map(liveRealmData) {
            it.firstOrNull().toOptional()
        }
    }

    override fun getMyReadReceiptLive(): LiveData<Optional<String>> {
        val liveRealmData = monarchy.findAllMappedWithChanges(
                { ReadReceiptEntity.where(it, roomId = roomId, userId = userId) },
                { it.eventId }
        )
        return Transformations.map(liveRealmData) {
            it.firstOrNull().toOptional()
        }
    }

    override fun getEventReadReceiptsLive(eventId: String): LiveData<List<ReadReceipt>> {
        val liveRealmData = monarchy.findAllMappedWithChanges(
                { ReadReceiptsSummaryEntity.where(it, eventId) },
                { readReceiptsSummaryMapper.map(it) }
        )
        return Transformations.map(liveRealmData) {
            it.firstOrNull().orEmpty()
        }
    }

    private fun ReadService.MarkAsReadParams.forceReadMarker(): Boolean {
        return this == ReadService.MarkAsReadParams.READ_MARKER || this == ReadService.MarkAsReadParams.BOTH
    }

    private fun ReadService.MarkAsReadParams.forceReadReceipt(): Boolean {
        return this == ReadService.MarkAsReadParams.READ_RECEIPT || this == ReadService.MarkAsReadParams.BOTH
    }
}

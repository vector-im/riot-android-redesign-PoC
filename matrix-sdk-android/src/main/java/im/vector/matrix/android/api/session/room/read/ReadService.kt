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

package im.vector.matrix.android.api.session.room.read

import androidx.lifecycle.LiveData
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.room.model.ReadReceipt
import im.vector.matrix.android.api.util.Optional

/**
 * This interface defines methods to handle read receipts and read marker in a room. It's implemented at the room level.
 */
interface ReadService {

    enum class MarkAsReadParams {
        READ_RECEIPT,
        READ_MARKER,
        BOTH
    }

    /**
     * Force the read marker to be set on the latest event.
     */
    fun markAsRead(params: MarkAsReadParams = MarkAsReadParams.BOTH, callback: MatrixCallback<Unit>)

    /**
     * Set the read receipt on the event with provided eventId.
     */
    fun setReadReceipt(eventId: String, callback: MatrixCallback<Unit>)

    /**
     * Set the read marker on the event with provided eventId.
     */
    fun setReadMarker(fullyReadEventId: String, callback: MatrixCallback<Unit>)

    /**
     * Check if an event is already read, ie. your read receipt is set on a more recent event.
     */
    fun isEventRead(eventId: String): Boolean

    /**
     * Returns the read marker id for the room.
     */
    fun getReadMarker(): Optional<String>

    /**
     * Returns a live read marker id for the room.
     */
    fun getReadMarkerLive(): LiveData<Optional<String>>

    /**
     * Returns a live read receipt id for the room.
     */
    fun getMyReadReceiptLive(): LiveData<Optional<String>>

    /**
     * Returns a live list of read receipts for a given event
     * @param eventId: the event
     */
    fun getEventReadReceiptsLive(eventId: String): LiveData<List<ReadReceipt>>
}

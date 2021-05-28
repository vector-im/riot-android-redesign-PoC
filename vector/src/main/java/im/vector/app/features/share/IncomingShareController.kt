/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.share

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Incomplete
import im.vector.app.R
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.list.RoomSummaryItemFactory
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import javax.inject.Inject

class IncomingShareController @Inject constructor(private val roomSummaryItemFactory: RoomSummaryItemFactory,
                                                  private val stringProvider: StringProvider) : TypedEpoxyController<IncomingShareViewState>() {

    interface Callback {
        fun onRoomClicked(roomSummary: RoomSummary)
        fun onRoomLongClicked(roomSummary: RoomSummary): Boolean
    }

    var callback: Callback? = null

    override fun buildModels(data: IncomingShareViewState) {
        val host = this
        if (data.sharedData == null || data.filteredRoomSummaries is Incomplete) {
            loadingItem {
                id("loading")
            }
            return
        }
        val roomSummaries = data.filteredRoomSummaries()
        if (roomSummaries.isNullOrEmpty()) {
            noResultItem {
                id("no_result")
                text(host.stringProvider.getString(R.string.no_result_placeholder))
            }
        } else {
            roomSummaries.forEach { roomSummary ->
                roomSummaryItemFactory
                        .createRoomItem(roomSummary, data.selectedRoomIds, callback?.let { it::onRoomClicked }, callback?.let { it::onRoomLongClicked })
                        .addTo(this)
            }
        }
    }
}

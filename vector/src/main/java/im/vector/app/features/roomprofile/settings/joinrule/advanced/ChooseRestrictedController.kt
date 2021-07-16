/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.roomprofile.settings.joinrule.advanced

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.spaces.manage.roomSelectionItem
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

class ChooseRestrictedController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer
) : TypedEpoxyController<RoomJoinRuleChooseRestrictedState>() {

    interface Listener {
        fun onItemSelected(matrixItem: MatrixItem)
    }

    var listener: Listener? = null

    override fun buildModels(data: RoomJoinRuleChooseRestrictedState?) {
        data ?: return
        val host = this

        if (data.filter.isNotEmpty()) {
            when (val results = data.filteredResults) {
                Uninitialized,
                is Fail    -> return
                is Loading -> loadingItem { id("filter_load") }
                is Success -> {
                    if (results.invoke().isEmpty()) {
                        noResultItem {
                            id("empty")
                            text(host.stringProvider.getString(R.string.no_result_placeholder))
                        }
                    } else {
                        results.invoke().forEach { matrixItem ->
                            roomSelectionItem {
                                id(matrixItem.id)
                                matrixItem(matrixItem)
                                avatarRenderer(host.avatarRenderer)
                                selected(data.updatedAllowList.firstOrNull { it.id == matrixItem.id } != null)
                                itemClickListener { host.listener?.onItemSelected(matrixItem) }
                            }
                        }
                    }
                }
            }
            return
        }

        // when no filters
        genericFooterItem {
            id("h1")
            text(host.stringProvider.getString(R.string.space_you_know_that_contains_this_room))
            centered(false)
        }

        data.possibleSpaceCandidate.forEach { matrixItem ->
            roomSelectionItem {
                id(matrixItem.id)
                matrixItem(matrixItem)
                avatarRenderer(host.avatarRenderer)
                selected(data.updatedAllowList.firstOrNull { it.id == matrixItem.id } != null)
                itemClickListener { host.listener?.onItemSelected(matrixItem) }
            }
        }

        if (data.unknownRestricted.isNotEmpty()) {
            genericFooterItem {
                id("others")
                text(host.stringProvider.getString(R.string.other_spaces_or_rooms_you_might_not_know))
                centered(false)
            }

            data.unknownRestricted.forEach { matrixItem ->
                roomSelectionItem {
                    id(matrixItem.id)
                    matrixItem(matrixItem)
                    avatarRenderer(host.avatarRenderer)
                    selected(data.updatedAllowList.firstOrNull { it.id == matrixItem.id } != null)
                    itemClickListener { host.listener?.onItemSelected(matrixItem) }
                }
            }
        }
    }
}

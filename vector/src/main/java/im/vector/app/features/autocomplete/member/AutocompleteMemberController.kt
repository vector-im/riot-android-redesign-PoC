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

package im.vector.app.features.autocomplete.member

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.autocompleteMatrixItem
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class AutocompleteMemberController @Inject constructor() : TypedEpoxyController<List<RoomMemberSummary>>() {

    var listener: AutocompleteClickListener<RoomMemberSummary>? = null

    @Inject lateinit var avatarRenderer: AvatarRenderer

    override fun buildModels(data: List<RoomMemberSummary>?) {
        if (data.isNullOrEmpty()) {
            return
        }
        val host = this
        data.forEach { user ->
            autocompleteMatrixItem {
                id(user.userId)
                matrixItem(user.toMatrixItem())
                avatarRenderer(host.avatarRenderer)
                clickListener { _ ->
                    host.listener?.onItemClick(user)
                }
            }
        }
    }
}

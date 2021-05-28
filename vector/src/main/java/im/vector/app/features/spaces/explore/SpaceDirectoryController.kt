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

package im.vector.app.features.spaces.explore

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Incomplete
import im.vector.app.R
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.GenericEmptyWithActionItem
import im.vector.app.core.ui.list.genericEmptyWithActionItem
import im.vector.app.core.ui.list.genericPillItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.list.spaceChildInfoItem
import me.gujun.android.span.span
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError.Companion.M_UNRECOGNIZED
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class SpaceDirectoryController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val errorFormatter: ErrorFormatter
) : TypedEpoxyController<SpaceDirectoryState>() {

    interface InteractionListener {
        fun onButtonClick(spaceChildInfo: SpaceChildInfo)
        fun onSpaceChildClick(spaceChildInfo: SpaceChildInfo)
        fun onRoomClick(spaceChildInfo: SpaceChildInfo)
        fun retry()
        fun addExistingRooms(spaceId: String)
    }

    var listener: InteractionListener? = null

    override fun buildModels(data: SpaceDirectoryState?) {
        val host = this
        val results = data?.spaceSummaryApiResult

        if (results is Incomplete) {
            loadingItem {
                id("loading")
            }
        } else if (results is Fail) {
            val failure = results.error
            if (failure is Failure.ServerError && failure.error.code == M_UNRECOGNIZED) {
                genericPillItem {
                    id("HS no Support")
                    imageRes(R.drawable.error)
                    tintIcon(false)
                    text(
                            span {
                                span(host.stringProvider.getString(R.string.spaces_no_server_support_title)) {
                                    textStyle = "bold"
                                    textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary)
                                }
                                +"\n\n"
                                span(host.stringProvider.getString(R.string.spaces_no_server_support_description)) {
                                    textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                }
                            }
                    )
                }
            } else {
                errorWithRetryItem {
                    id("api_err")
                    text(host.errorFormatter.toHumanReadable(failure))
                    listener { host.listener?.retry() }
                }
            }
        } else {
            val flattenChildInfo = results?.invoke()
                    ?.filter {
                        it.parentRoomId == (data.hierarchyStack.lastOrNull() ?: data.spaceId)
                    }
                    ?: emptyList()

            if (flattenChildInfo.isEmpty()) {
                genericEmptyWithActionItem {
                    id("empty_res")
                    title(host.stringProvider.getString(R.string.this_space_has_no_rooms))
                    iconRes(R.drawable.ic_empty_icon_room)
                    iconTint(host.colorProvider.getColorFromAttribute(R.attr.riotx_reaction_background_on))
                    apply {
                        if (data?.canAddRooms == true) {
                            description(host.stringProvider.getString(R.string.this_space_has_no_rooms_admin))
                            val action = GenericEmptyWithActionItem.Action(host.stringProvider.getString(R.string.space_add_existing_rooms))
                            action.perform = Runnable {
                                host.listener?.addExistingRooms(data.spaceId)
                            }
                            buttonAction(action)
                        } else {
                            description(host.stringProvider.getString(R.string.this_space_has_no_rooms_not_admin))
                        }
                    }
                }
            } else {
                flattenChildInfo.forEach { info ->
                    val isSpace = info.roomType == RoomType.SPACE
                    val isJoined = data?.joinedRoomsIds?.contains(info.childRoomId) == true
                    val isLoading = data?.changeMembershipStates?.get(info.childRoomId)?.isInProgress() ?: false
                    spaceChildInfoItem {
                        id(info.childRoomId)
                        matrixItem(info.toMatrixItem())
                        avatarRenderer(host.avatarRenderer)
                        topic(info.topic)
                        memberCount(info.activeMemberCount ?: 0)
                        space(isSpace)
                        loading(isLoading)
                        buttonLabel(
                                if (isJoined) host.stringProvider.getString(R.string.action_open)
                                else host.stringProvider.getString(R.string.join)
                        )
                        apply {
                            if (isSpace) {
                                itemClickListener(View.OnClickListener { host.listener?.onSpaceChildClick(info) })
                            } else {
                                itemClickListener(View.OnClickListener { host.listener?.onRoomClick(info) })
                            }
                        }
                        buttonClickListener(View.OnClickListener { host.listener?.onButtonClick(info) })
                    }
                }
            }
        }
    }
}

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

package im.vector.app.features.spaces.people

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.epoxy.profiles.profileMatrixItemWithPowerLevel
import im.vector.app.core.extensions.join
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.GenericItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomprofile.members.RoomMemberListCategories
import im.vector.app.features.roomprofile.members.RoomMemberListViewState
import im.vector.app.features.roomprofile.members.RoomMemberSummaryFilter
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class SpacePeopleListController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val colorProvider: ColorProvider,
        private val stringProvider: StringProvider,
        private val dimensionConverter: DimensionConverter,
        private val roomMemberSummaryFilter: RoomMemberSummaryFilter
) : TypedEpoxyController<RoomMemberListViewState>() {

    private val dividerColor = colorProvider.getColorFromAttribute(R.attr.vctr_list_divider_color)

    interface InteractionListener {
        fun onSpaceMemberClicked(roomMemberSummary: RoomMemberSummary)
        fun onInviteToSpaceSelected()
    }

    var listener: InteractionListener? = null

    init {
        setData(null)
    }

    override fun buildModels(data: RoomMemberListViewState?) {
        val host = this
        val memberSummaries = data?.roomMemberSummaries?.invoke()
        if (memberSummaries == null) {
            loadingItem { id("loading") }
            return
        }
        roomMemberSummaryFilter.filter = data.filter
        var foundCount = 0
        memberSummaries.forEach { memberEntry ->

            val filtered = memberEntry.second
                    .filter { roomMemberSummaryFilter.test(it) }
            if (filtered.isNotEmpty()) {
                dividerItem {
                    id("divider_type_${memberEntry.first.titleRes}")
                    color(host.dividerColor)
                }
            }
            foundCount += filtered.size
            filtered
                    .join(
                            each = { _, roomMember ->
                                profileMatrixItemWithPowerLevel {
                                    id(roomMember.userId)
                                    matrixItem(roomMember.toMatrixItem())
                                    avatarRenderer(host.avatarRenderer)
                                    userEncryptionTrustLevel(data.trustLevelMap.invoke()?.get(roomMember.userId))
                                            .apply {
                                                val pl = host.toPowerLevelLabel(memberEntry.first)
                                                if (memberEntry.first == RoomMemberListCategories.INVITE) {
                                                    powerLevelLabel(
                                                            span {
                                                                span(host.stringProvider.getString(R.string.invited)) {
                                                                    textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                                                    textStyle = "bold"
                                                                    // fontFamily = "monospace"
                                                                }
                                                            }
                                                    )
                                                } else if (pl != null) {
                                                    powerLevelLabel(
                                                            span {
                                                                span(" $pl ") {
                                                                    backgroundColor = host.colorProvider.getColor(R.color.notification_accent_color)
                                                                    paddingTop = host.dimensionConverter.dpToPx(2)
                                                                    paddingBottom = host.dimensionConverter.dpToPx(2)
                                                                    textColor = host.colorProvider.getColor(R.color.white)
                                                                    textStyle = "bold"
                                                                    // fontFamily = "monospace"
                                                                }
                                                            }
                                                    )
                                                } else {
                                                    powerLevelLabel(null)
                                                }
                                            }

                                    clickListener { _ ->
                                        host.listener?.onSpaceMemberClicked(roomMember)
                                    }
                                }
                            },
                            between = { _, roomMemberBefore ->
                                dividerItem {
                                    id("divider_${roomMemberBefore.userId}")
                                    color(host.dividerColor)
                                }
                            }
                    )
        }

        if (foundCount == 0 && data.filter.isNotEmpty()) {
            // add the footer thing
            genericItem {
                id("not_found")
                title(
                        span {
                            +"\n"
                            +host.stringProvider.getString(R.string.no_result_placeholder)
                        }
                )
                description(
                        span {
                            +host.stringProvider.getString(R.string.looking_for_someone_not_in_space, data.roomSummary.invoke()?.displayName ?: "")
                            +"\n"
                            span("Invite them") {
                                textColor = host.colorProvider.getColorFromAttribute(R.attr.colorAccent)
                                textStyle = "bold"
                            }
                        }
                )
                itemClickAction(GenericItem.Action("invite").apply {
                    perform = Runnable {
                        host.listener?.onInviteToSpaceSelected()
                    }
                })
            }
        }
    }

    private fun toPowerLevelLabel(categories: RoomMemberListCategories): String? {
        return when (categories) {
            RoomMemberListCategories.ADMIN     -> stringProvider.getString(R.string.power_level_admin)
            RoomMemberListCategories.MODERATOR -> stringProvider.getString(R.string.power_level_moderator)
            else                               -> null
        }
    }
}

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

package im.vector.app.features.spaces

import android.view.View
import com.airbnb.epoxy.EpoxyController
import im.vector.app.R
import im.vector.app.RoomGroupingMethod
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericItemHeader
import im.vector.app.features.grouplist.groupSummaryItem
import im.vector.app.features.grouplist.homeSpaceSummaryItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.list.UnreadCounterBadgeView
import im.vector.app.group
import im.vector.app.space
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.summary.RoomAggregateNotificationCount
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class SpaceSummaryController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val colorProvider: ColorProvider,
        private val stringProvider: StringProvider) : EpoxyController() {

    var callback: Callback? = null
    private var viewState: SpaceListViewState? = null

    private val subSpaceComparator: Comparator<SpaceChildInfo> = compareBy<SpaceChildInfo> { it.order }.thenBy { it.childRoomId }

    init {
        requestModelBuild()
    }

    fun update(viewState: SpaceListViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    override fun buildModels() {
        val nonNullViewState = viewState ?: return
        val host = this
        buildGroupModels(
                nonNullViewState.asyncSpaces(),
                nonNullViewState.selectedGroupingMethod,
                nonNullViewState.rootSpaces,
                nonNullViewState.expandedStates,
                nonNullViewState.homeAggregateCount)

        if (!nonNullViewState.legacyGroups.isNullOrEmpty()) {
            genericFooterItem {
                id("legacy_space")
                text(" ")
            }

            genericItemHeader {
                id("legacy_groups")
                text(host.stringProvider.getString(R.string.groups_header))
                textColor(host.colorProvider.getColorFromAttribute(R.attr.riotx_text_primary))
            }

            // add home for communities
            nonNullViewState.myMxItem.invoke()?.let { mxItem ->
                groupSummaryItem {
                    avatarRenderer(host.avatarRenderer)
                    id("all_communities")
                    matrixItem(mxItem.copy(displayName = host.stringProvider.getString(R.string.group_all_communities)))
                    selected(nonNullViewState.selectedGroupingMethod is RoomGroupingMethod.ByLegacyGroup
                            && nonNullViewState.selectedGroupingMethod.group() == null)
                    listener { host.callback?.onGroupSelected(null) }
                }
            }

            nonNullViewState.legacyGroups.forEach { groupSummary ->
                groupSummaryItem {
                    avatarRenderer(host.avatarRenderer)
                    id(groupSummary.groupId)
                    matrixItem(groupSummary.toMatrixItem())
                    selected(nonNullViewState.selectedGroupingMethod is RoomGroupingMethod.ByLegacyGroup
                            && nonNullViewState.selectedGroupingMethod.group()?.groupId == groupSummary.groupId)
                    listener { host.callback?.onGroupSelected(groupSummary) }
                }
            }
        }
    }

    private fun buildGroupModels(summaries: List<RoomSummary>?,
                                 selected: RoomGroupingMethod,
                                 rootSpaces: List<RoomSummary>?,
                                 expandedStates: Map<String, Boolean>,
                                 homeCount: RoomAggregateNotificationCount) {
        val host = this
        spaceBetaHeaderItem {
            id("beta_header")
            clickAction(View.OnClickListener {
                host.callback?.sendFeedBack()
            })
        }

        // show invites on top

        summaries?.filter { it.membership == Membership.INVITE }
                ?.forEach {
                    spaceSummaryItem {
                        avatarRenderer(host.avatarRenderer)
                        id(it.roomId)
                        matrixItem(it.toMatrixItem())
                        countState(UnreadCounterBadgeView.State(1, true))
                        selected(false)
                        description(host.stringProvider.getString(R.string.you_are_invited))
                        listener { host.callback?.onSpaceInviteSelected(it) }
                    }
                }

        homeSpaceSummaryItem {
            id("space_home")
            selected(selected is RoomGroupingMethod.BySpace && selected.space() == null)
            countState(UnreadCounterBadgeView.State(homeCount.totalCount, homeCount.isHighlight))
            listener { host.callback?.onSpaceSelected(null) }
        }

        rootSpaces
                ?.sortedBy { it.roomId }
                ?.forEach { groupSummary ->
                    val isSelected = selected is RoomGroupingMethod.BySpace && groupSummary.roomId == selected.space()?.roomId
                    // does it have children?
                    val subSpaces = groupSummary.spaceChildren?.filter { childInfo ->
                        summaries?.indexOfFirst { it.roomId == childInfo.childRoomId } != -1
                    }?.sortedWith(subSpaceComparator)
                    val hasChildren = (subSpaces?.size ?: 0) > 0
                    val expanded = expandedStates[groupSummary.roomId] == true

                    spaceSummaryItem {
                        avatarRenderer(host.avatarRenderer)
                        id(groupSummary.roomId)
                        hasChildren(hasChildren)
                        expanded(expanded)
                        matrixItem(groupSummary.toMatrixItem())
                        selected(isSelected)
                        onMore { host.callback?.onSpaceSettings(groupSummary) }
                        listener { host.callback?.onSpaceSelected(groupSummary) }
                        toggleExpand { host.callback?.onToggleExpand(groupSummary) }
                        countState(
                                UnreadCounterBadgeView.State(
                                        groupSummary.notificationCount,
                                        groupSummary.highlightCount > 0
                                )
                        )
                    }

                    if (hasChildren && expanded) {
                        // it's expanded
                        subSpaces?.forEach { child ->
                            buildSubSpace(summaries, expandedStates, selected, child, 1, 3)
                        }
                    }
                }

        spaceAddItem {
            id("create")
            listener { host.callback?.onAddSpaceSelected() }
        }
    }

    private fun buildSubSpace(summaries: List<RoomSummary>?,
                              expandedStates: Map<String, Boolean>,
                              selected: RoomGroupingMethod,
                              info: SpaceChildInfo, currentDepth: Int, maxDepth: Int) {
        val host = this
        if (currentDepth >= maxDepth) return
        val childSummary = summaries?.firstOrNull { it.roomId == info.childRoomId } ?: return
        // does it have children?
        val subSpaces = childSummary.spaceChildren?.filter { childInfo ->
            summaries.indexOfFirst { it.roomId == childInfo.childRoomId } != -1
        }?.sortedWith(subSpaceComparator)
        val expanded = expandedStates[childSummary.roomId] == true
        val isSelected = selected is RoomGroupingMethod.BySpace && childSummary.roomId == selected.space()?.roomId

        subSpaceSummaryItem {
            avatarRenderer(host.avatarRenderer)
            id(childSummary.roomId)
            hasChildren(!subSpaces.isNullOrEmpty())
            selected(isSelected)
            expanded(expanded)
            onMore { host.callback?.onSpaceSettings(childSummary) }
            matrixItem(childSummary.toMatrixItem())
            listener { host.callback?.onSpaceSelected(childSummary) }
            toggleExpand { host.callback?.onToggleExpand(childSummary) }
            indent(currentDepth)
            countState(
                    UnreadCounterBadgeView.State(
                            childSummary.notificationCount,
                            childSummary.highlightCount > 0
                    )
            )
        }

        if (expanded) {
            subSpaces?.forEach {
                buildSubSpace(summaries, expandedStates, selected, it, currentDepth + 1, maxDepth)
            }
        }
    }

    interface Callback {
        fun onSpaceSelected(spaceSummary: RoomSummary?)
        fun onSpaceInviteSelected(spaceSummary: RoomSummary)
        fun onSpaceSettings(spaceSummary: RoomSummary)
        fun onToggleExpand(spaceSummary: RoomSummary)
        fun onAddSpaceSelected()
        fun onGroupSelected(groupSummary: GroupSummary?)
        fun sendFeedBack()
    }
}

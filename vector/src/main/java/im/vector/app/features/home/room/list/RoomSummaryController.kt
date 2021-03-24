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

package im.vector.app.features.home.room.list

import androidx.annotation.StringRes
import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Success
import im.vector.app.R
import im.vector.app.core.epoxy.helpFooterItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.core.ui.list.GenericItem
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.home.room.filtered.FilteredRoomFooterItem
import im.vector.app.features.home.room.filtered.filteredRoomFooterItem
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import javax.inject.Inject

class RoomSummaryController @Inject constructor(private val stringProvider: StringProvider,
                                                private val roomSummaryItemFactory: RoomSummaryItemFactory,
                                                private val roomListNameFilter: RoomListNameFilter,
                                                private val userPreferencesProvider: UserPreferencesProvider,
                                                private val colorProvider: ColorProvider
) : EpoxyController() {

    var listener: Listener? = null

    private var viewState: RoomListViewState? = null

    init {
        // We are requesting a model build directly as the first build of epoxy is on the main thread.
        // It avoids to build the whole list of rooms on the main thread.
        requestModelBuild()
    }

    fun update(viewState: RoomListViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    fun onRoomLongClicked() {
        userPreferencesProvider.neverShowLongClickOnRoomHelpAgain()
        requestModelBuild()
    }

    override fun buildModels() {
        val nonNullViewState = viewState ?: return
        when (nonNullViewState.displayMode) {
            RoomListDisplayMode.FILTERED -> buildFilteredRooms(nonNullViewState)
            else                         -> buildRooms(nonNullViewState)
        }
    }

    private fun buildFilteredRooms(viewState: RoomListViewState) {
        val summaries = viewState.asyncRooms() ?: return

        roomListNameFilter.filter = viewState.roomFilter

        val filteredSummaries = summaries
                .filter { it.membership == Membership.JOIN && roomListNameFilter.test(it) }

        buildRoomModels(filteredSummaries,
                viewState.roomMembershipChanges,
                emptySet())

        addFilterFooter(viewState)
    }

    private fun buildRooms(viewState: RoomListViewState) {
        var showHelp = false
        val roomSummaries = viewState.asyncFilteredRooms()
        roomSummaries?.forEach { (category, summaries) ->
            if (summaries.isEmpty()) {
                return@forEach
            } else {
                val isExpanded = viewState.isCategoryExpanded(category)
                buildRoomCategory(viewState, summaries, category.titleRes, viewState.isCategoryExpanded(category)) {
                    listener?.onToggleRoomCategory(category)
                }
                if (isExpanded) {
                    buildRoomModels(summaries,
                            viewState.roomMembershipChanges,
                            emptySet())
                    // Never set showHelp to true for invitation
                    if (category != RoomCategory.INVITE) {
                        showHelp = userPreferencesProvider.shouldShowLongClickOnRoomHelp()
                    }
                }
            }
        }

        if (viewState.displayMode == RoomListDisplayMode.ROOMS) {
            val suggested = viewState.asyncSuggestedRooms.invoke()
                    ?.filter { info ->
                        roomSummaries?.values?.flatten()
                                ?.filter { it.membership.isActive() }
                                .orEmpty()
                                .indexOfFirst { it.roomId == info.childRoomId } == -1
                    }?.filter {
                        // removed joined one (or they will temporary appear on both room and suggested
                        viewState.suggestedRoomJoiningState[it.childRoomId] !is Success
                    }
            if (suggested?.isNotEmpty() == true) {
                if (roomSummaries.isNullOrEmpty()) {
                    genericFooterItem {
                        id("empty_suggested_header")
                        centered(false)
                        style(GenericItem.STYLE.TITLE)
                        text(stringProvider.getString(
                                R.string.suggested_rooms_pills_on_empty_header,
                                viewState.currentSpace.invoke()?.name,
                                viewState.currentUserName
                        ))
                        textColor(colorProvider.getColorFromAttribute(R.attr.riot_primary_text_color))
                    }
                    genericFooterItem {
                        id("empty_suggested")
                        centered(false)
                        text(stringProvider.getString(R.string.suggested_rooms_pills_on_empty_text))
                    }
                }
                val isExpanded = viewState.isCategoryExpanded(RoomCategory.SUGGESTED_ROOM)

                roomCategoryItem {
                    id(R.string.suggested_header)
                    title(stringProvider.getString(R.string.suggested_header))
                    expanded(isExpanded)
                    listener {
                        listener?.onToggleRoomCategory(RoomCategory.SUGGESTED_ROOM)
                        update(viewState)
                    }
                }
                if (isExpanded) {
                    suggested.forEach { info ->
                        roomSummaryItemFactory.createSuggestion(
                                info,
                                viewState.suggestedRoomJoiningState
                        ) {
                            listener?.onJoinSuggestedRoom(info)
                        }.addTo(this)
                    }
                }
            }
        }

        if (showHelp) {
            buildLongClickHelp()
        }
    }

    private fun buildLongClickHelp() {
        helpFooterItem {
            id("long_click_help")
            text(stringProvider.getString(R.string.help_long_click_on_room_for_more_options))
        }
    }

    private fun addFilterFooter(viewState: RoomListViewState) {
        filteredRoomFooterItem {
            id("filter_footer")
            listener(listener)
            currentFilter(viewState.roomFilter)
        }
    }

    private fun buildRoomCategory(viewState: RoomListViewState,
                                  summaries: List<RoomSummary>,
                                  @StringRes titleRes: Int,
                                  isExpanded: Boolean,
                                  mutateExpandedState: () -> Unit) {
        // TODO should add some business logic later
        val unreadCount = if (summaries.isEmpty()) {
            0
        } else {
            summaries.map { it.notificationCount }.sumBy { i -> i }
        }
        val showHighlighted = summaries.any { it.highlightCount > 0 }
        roomCategoryItem {
            id(titleRes)
            title(stringProvider.getString(titleRes))
            expanded(isExpanded)
            unreadNotificationCount(unreadCount)
            showHighlighted(showHighlighted)
            listener {
                mutateExpandedState()
                update(viewState)
            }
        }
    }

    private fun buildRoomModels(summaries: List<RoomSummary>,
                                roomChangedMembershipStates: Map<String, ChangeMembershipState>,
                                selectedRoomIds: Set<String>) {
        summaries.forEach { roomSummary ->
            roomSummaryItemFactory
                    .create(roomSummary,
                            roomChangedMembershipStates,
                            selectedRoomIds,
                            listener)
                    .addTo(this)
        }
    }

    interface Listener : FilteredRoomFooterItem.FilteredRoomFooterItemListener {
        fun onToggleRoomCategory(roomCategory: RoomCategory)
        fun onRoomClicked(room: RoomSummary)
        fun onRoomLongClicked(room: RoomSummary): Boolean
        fun onRejectRoomInvitation(room: RoomSummary)
        fun onAcceptRoomInvitation(room: RoomSummary)
        fun onJoinSuggestedRoom(room: SpaceChildInfo)
    }
}

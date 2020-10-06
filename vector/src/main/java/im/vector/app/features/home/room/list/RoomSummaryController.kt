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

import android.os.Handler
import android.view.View
import com.airbnb.epoxy.EpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.helpFooterItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.DrawableProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.utils.DebouncedClickListener
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.home.room.detail.timeline.EpoxyControllerDiffHandler
import im.vector.app.features.home.room.detail.timeline.EpoxyControllerModelHandler
import im.vector.app.features.home.room.filtered.FilteredRoomFooterItem
import im.vector.app.features.home.room.filtered.filteredRoomFooterItem
import im.vector.app.features.home.room.list.grid.roomGridItem
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class RoomSummaryController @Inject constructor(private val stringProvider: StringProvider,
                                                private val roomSummaryItemFactory: RoomSummaryItemFactory,
                                                private val roomListNameFilter: RoomListNameFilter,
                                                private val userPreferencesProvider: UserPreferencesProvider,
                                                private val avatarRenderer: AvatarRenderer,
                                                private val vectorPreferences: VectorPreferences,
                                                private val drawableProvider: DrawableProvider,
                                                private val colorProvider: ColorProvider,
                                                @EpoxyControllerModelHandler
                                                private val backgroundModelHandler: Handler,
                                                @EpoxyControllerDiffHandler
                                                private val backgroundDiffHandler: Handler
) : EpoxyController(backgroundModelHandler, backgroundDiffHandler) {

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
                RoomListViewState.CategoryMode.List,
                viewState.roomMembershipChanges,
                emptySet())

        addFilterFooter(viewState)
    }

    private fun buildRooms(viewState: RoomListViewState) {
        var showHelp = false
        val roomSummaries = viewState.asyncFilteredRooms()
        if (vectorPreferences.labUseTabNavigation() && vectorPreferences.labPinFavInTabNavigation()
                && !roomSummaries?.get(RoomCategory.FAVOURITE).isNullOrEmpty()) {
            genericFooterItem {
                id("Top Spacing")
                text(" ")
                spanSizeOverride { _, _, _ -> spanCount }
            }
        }
        roomSummaries?.forEach { (category, summaries) ->
            if (summaries.isEmpty()) {
                return@forEach
            } else {
                val isExpanded = viewState.isCategoryExpanded(category)
                val mode = viewState.getCategoryMode(category)
                if (vectorPreferences.labUseTabNavigation()) {
                    buildRoomModels(summaries,
                            mode,
                            viewState.roomMembershipChanges,
                            emptySet())
                    // Never set showHelp to true for invitation
                    if (category != RoomCategory.INVITE) {
                        showHelp = userPreferencesProvider.shouldShowLongClickOnRoomHelp()
                    }
                } else {
                    buildRoomCategory(
                            viewState,
                            category,
                            summaries,
                            isExpanded,
                            mode,
                            { newMode ->
                                listener?.onChangeModeRoomCategory(category, newMode)
                            },
                            {
                                listener?.onToggleRoomCategory(category)
                            }
                    )
                    if (isExpanded) {
                        buildRoomModels(summaries,
                                mode,
                                viewState.roomMembershipChanges,
                                emptySet())
                        // Never set showHelp to true for invitation
                        if (category != RoomCategory.INVITE) {
                            showHelp = userPreferencesProvider.shouldShowLongClickOnRoomHelp()
                        }
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
            spanSizeOverride { _, _, _ -> spanCount }
        }
    }

    private fun addFilterFooter(viewState: RoomListViewState) {
        filteredRoomFooterItem {
            id("filter_footer")
            listener(listener)
            currentFilter(viewState.roomFilter)
            spanSizeOverride { _, _, _ -> spanCount }
        }
    }

    private fun buildRoomCategory(viewState: RoomListViewState,
                                  category: RoomCategory,
                                  summaries: List<RoomSummary>,
                                  isExpanded: Boolean,
                                  mode: RoomListViewState.CategoryMode,
                                  changeModeListener: (RoomListViewState.CategoryMode) -> Unit,
                                  mutateExpandedState: () -> Unit) {
        // TODO should add some business logic later
        val unreadCount = if (summaries.isEmpty()) {
            0
        } else {
            summaries.map { it.notificationCount }.sumBy { i -> i }
        }
        val showHighlighted = summaries.any { it.highlightCount > 0 }
        roomCategoryItem {
            id(category.titleRes)
            title(stringProvider.getString(category.titleRes))
            expanded(isExpanded)
            unreadNotificationCount(unreadCount)
            showHighlighted(showHighlighted)
            showSwitchMode(category != RoomCategory.INVITE)
            mode(mode)
            listener {
                mutateExpandedState()
                update(viewState)
            }
            changeModeListener(changeModeListener)
            spanSizeOverride { _, _, _ -> spanCount }
        }
    }

    private fun buildRoomModels(summaries: List<RoomSummary>,
                                mode: RoomListViewState.CategoryMode,
                                roomChangedMembershipStates: Map<String, ChangeMembershipState>,
                                selectedRoomIds: Set<String>) {
        when (mode) {
            RoomListViewState.CategoryMode.List ->
                summaries.forEach { roomSummary ->
                    roomSummaryItemFactory
                            .create(roomSummary,
                                    roomChangedMembershipStates,
                                    selectedRoomIds,
                                    spanCount,
                                    listener)
                            .addTo(this)
                }
            RoomListViewState.CategoryMode.Grid -> {
                // Use breadcrumbs for the moment
                summaries.forEach { roomSummary ->
                    roomGridItem {
                        id(roomSummary.roomId)
                        hasTypingUsers(roomSummary.typingUsers.isNotEmpty())
                        avatarRenderer(avatarRenderer)
                        matrixItem(roomSummary.toMatrixItem())
                        apply {
                            if (roomSummary.isFavorite) {
                                textDrawableLeft(drawableProvider.getDrawable(R.drawable.ic_star_green_24dp))
                            }
                        }

                        unreadNotificationCount(roomSummary.notificationCount)
                        showHighlighted(roomSummary.highlightCount > 0)
                        hasUnreadMessage(roomSummary.hasUnreadMessages)
                        hasDraft(roomSummary.userDrafts.isNotEmpty())
                        itemLongClickListener { _ ->
                            listener?.onRoomLongClicked(roomSummary) ?: false
                        }
                        itemClickListener(
                                DebouncedClickListener(View.OnClickListener { _ ->
                                    listener?.onRoomClicked(roomSummary)
                                })
                        )
                        spanCount
                    }
                }
            }
        }
    }

    interface Listener : FilteredRoomFooterItem.FilteredRoomFooterItemListener {
        fun onToggleRoomCategory(roomCategory: RoomCategory)
        fun onChangeModeRoomCategory(roomCategory: RoomCategory, newMode: RoomListViewState.CategoryMode)
        fun onRoomClicked(room: RoomSummary)
        fun onRoomLongClicked(room: RoomSummary): Boolean
        fun onRejectRoomInvitation(room: RoomSummary)
        fun onAcceptRoomInvitation(room: RoomSummary)
    }
}

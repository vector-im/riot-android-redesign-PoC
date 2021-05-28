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
package im.vector.app.features.home.room.list.actions

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.epoxy.bottomsheet.bottomSheetActionItem
import im.vector.app.core.epoxy.bottomsheet.bottomSheetRoomPreviewItem
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

/**
 * Epoxy controller for room list actions
 */
class RoomListQuickActionsEpoxyController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider
) : TypedEpoxyController<RoomListQuickActionsState>() {

    var listener: Listener? = null

    override fun buildModels(state: RoomListQuickActionsState) {
        val roomSummary = state.roomSummary() ?: return
        val host = this
        val showAll = state.mode == RoomListActionsArgs.Mode.FULL

        if (showAll) {
            // Preview, favorite, settings
            bottomSheetRoomPreviewItem {
                id("room_preview")
                avatarRenderer(host.avatarRenderer)
                matrixItem(roomSummary.toMatrixItem())
                stringProvider(host.stringProvider)
                izLowPriority(roomSummary.isLowPriority)
                izFavorite(roomSummary.isFavorite)
                settingsClickListener { host.listener?.didSelectMenuAction(RoomListQuickActionsSharedAction.Settings(roomSummary.roomId)) }
                favoriteClickListener { host.listener?.didSelectMenuAction(RoomListQuickActionsSharedAction.Favorite(roomSummary.roomId)) }
                lowPriorityClickListener { host.listener?.didSelectMenuAction(RoomListQuickActionsSharedAction.LowPriority(roomSummary.roomId)) }
            }

            // Notifications
            dividerItem {
                id("notifications_separator")
            }
        }

        val selectedRoomState = state.roomNotificationState()
        RoomListQuickActionsSharedAction.NotificationsAllNoisy(roomSummary.roomId).toBottomSheetItem(0, selectedRoomState)
        RoomListQuickActionsSharedAction.NotificationsAll(roomSummary.roomId).toBottomSheetItem(1, selectedRoomState)
        RoomListQuickActionsSharedAction.NotificationsMentionsOnly(roomSummary.roomId).toBottomSheetItem(2, selectedRoomState)
        RoomListQuickActionsSharedAction.NotificationsMute(roomSummary.roomId).toBottomSheetItem(3, selectedRoomState)

        if (showAll) {
            RoomListQuickActionsSharedAction.Leave(roomSummary.roomId).toBottomSheetItem(5)
        }
    }

    private fun RoomListQuickActionsSharedAction.toBottomSheetItem(index: Int, roomNotificationState: RoomNotificationState? = null) {
        val host = this@RoomListQuickActionsEpoxyController
        val selected = when (this) {
            is RoomListQuickActionsSharedAction.NotificationsAllNoisy     -> roomNotificationState == RoomNotificationState.ALL_MESSAGES_NOISY
            is RoomListQuickActionsSharedAction.NotificationsAll          -> roomNotificationState == RoomNotificationState.ALL_MESSAGES
            is RoomListQuickActionsSharedAction.NotificationsMentionsOnly -> roomNotificationState == RoomNotificationState.MENTIONS_ONLY
            is RoomListQuickActionsSharedAction.NotificationsMute         -> roomNotificationState == RoomNotificationState.MUTE
            else                                                          -> false
        }
        return bottomSheetActionItem {
            id("action_$index")
            selected(selected)
            iconRes(iconResId)
            textRes(titleRes)
            destructive(this@toBottomSheetItem.destructive)
            listener(View.OnClickListener { host.listener?.didSelectMenuAction(this@toBottomSheetItem) })
        }
    }

    interface Listener {
        fun didSelectMenuAction(quickAction: RoomListQuickActionsSharedAction)
    }
}

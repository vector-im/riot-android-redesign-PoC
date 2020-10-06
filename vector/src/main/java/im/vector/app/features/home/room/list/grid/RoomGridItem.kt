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

package im.vector.app.features.home.room.list.grid

import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.list.UnreadCounterBadgeView
import org.matrix.android.sdk.api.util.MatrixItem

@EpoxyModelClass(layout = R.layout.item_room_grid)
abstract class RoomGridItem : VectorEpoxyModel<RoomGridItem.Holder>() {

    @EpoxyAttribute var hasTypingUsers: Boolean = false
    @EpoxyAttribute lateinit var avatarRenderer: AvatarRenderer
    @EpoxyAttribute lateinit var matrixItem: MatrixItem
    @EpoxyAttribute var unreadNotificationCount: Int = 0
    @EpoxyAttribute var showHighlighted: Boolean = false
    @EpoxyAttribute var hasUnreadMessage: Boolean = false
    @EpoxyAttribute var hasDraft: Boolean = false
    @EpoxyAttribute var textDrawableLeft: Drawable? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var itemClickListener: View.OnClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var itemLongClickListener: View.OnLongClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.rootView.setOnClickListener(itemClickListener)
        holder.rootView.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            itemLongClickListener?.onLongClick(it) ?: false
        }
        holder.unreadIndentIndicator.isVisible = hasUnreadMessage
        avatarRenderer.render(matrixItem, holder.avatarImageView)
        holder.unreadCounterBadgeView.render(UnreadCounterBadgeView.State(unreadNotificationCount, showHighlighted))
        holder.draftIndentIndicator.isVisible = hasDraft
        holder.typingIndicator.isVisible = hasTypingUsers
        holder.roomName.text = if (textDrawableLeft != null) "⭐️ ${matrixItem.getBestName()}" else matrixItem.getBestName()
        // TODO this drawable don't work
        // holder.roomName.setCompoundDrawables(textDrawableLeft, null, null, null)
    }

    override fun unbind(holder: Holder) {
        holder.rootView.setOnClickListener(null)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val unreadCounterBadgeView by bind<UnreadCounterBadgeView>(R.id.itemRoomGridUnreadCounterBadgeView)
        val unreadIndentIndicator by bind<View>(R.id.itemRoomGridUnreadIndicator)
        val draftIndentIndicator by bind<View>(R.id.itemRoomGridDraftBadge)
        val typingIndicator by bind<View>(R.id.itemRoomGridTypingView)
        val avatarImageView by bind<ImageView>(R.id.itemRoomGridImageView)
        val roomName by bind<TextView>(R.id.itemRoomGridRoomName)
        val rootView by bind<ViewGroup>(R.id.itemRoomGridRoot)
    }
}

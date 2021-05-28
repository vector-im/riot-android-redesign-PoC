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
package im.vector.app.features.home.room.detail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyTouchHelperCallback
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.min

class RoomMessageTouchHelperCallback(private val context: Context,
                                     @DrawableRes actionIcon: Int,
                                     private val handler: QuickReplayHandler) : EpoxyTouchHelperCallback() {

    interface QuickReplayHandler {
        fun performQuickReplyOnHolder(model: EpoxyModel<*>)
        fun canSwipeModel(model: EpoxyModel<*>): Boolean
    }

    private var swipeBack: Boolean = false
    private var dX = 0f
    private var startTracking = false
    private var isVibrate = false

    private var replyButtonProgress: Float = 0F
    private var lastReplyButtonAnimationTime: Long = 0

    private val imageDrawable: Drawable = DrawableCompat.wrap(
            ContextCompat.getDrawable(context, actionIcon)!!
    )

    init {
        DrawableCompat.setTint(
                imageDrawable,
                ThemeUtils.getColor(context, R.attr.riotx_text_primary)
        )
    }

    private val triggerDistance = convertToPx(100)
    private val minShowDistance = convertToPx(20)
    private val triggerDelta = convertToPx(20)

    override fun onSwiped(viewHolder: EpoxyViewHolder, direction: Int) {
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: EpoxyViewHolder, target: EpoxyViewHolder): Boolean {
        return false
    }

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: EpoxyViewHolder): Int {
        if (handler.canSwipeModel(viewHolder.model)) {
            return ItemTouchHelper.Callback.makeMovementFlags(0, ItemTouchHelper.START) // Should we use Left?
        } else {
            return 0
        }
    }

    // We never let items completely go out
    override fun convertToAbsoluteDirection(flags: Int, layoutDirection: Int): Int {
        if (swipeBack) {
            swipeBack = false
            return 0
        }
        return super.convertToAbsoluteDirection(flags, layoutDirection)
    }

    override fun onChildDraw(c: Canvas,
                             recyclerView: RecyclerView,
                             viewHolder: EpoxyViewHolder,
                             dX: Float,
                             dY: Float,
                             actionState: Int,
                             isCurrentlyActive: Boolean) {
        if (actionState == ACTION_STATE_SWIPE) {
            setTouchListener(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
        val size = triggerDistance
        if (abs(viewHolder.itemView.translationX) < size || dX > this.dX /*going back*/) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            this.dX = dX
            startTracking = true
        }
        drawReplyButton(c, viewHolder.itemView)
    }

    @Suppress("UNUSED_PARAMETER")
    @SuppressLint("ClickableViewAccessibility")
    private fun setTouchListener(c: Canvas,
                                 recyclerView: RecyclerView,
                                 viewHolder: EpoxyViewHolder,
                                 dX: Float,
                                 dY: Float,
                                 actionState: Int,
                                 isCurrentlyActive: Boolean) {
        // TODO can this interfere with other interactions? should i remove it
        recyclerView.setOnTouchListener { _, event ->
            swipeBack = event.action == MotionEvent.ACTION_CANCEL || event.action == MotionEvent.ACTION_UP
            if (swipeBack) {
                if (abs(dX) >= triggerDistance) {
                    try {
                        viewHolder.model?.let { handler.performQuickReplyOnHolder(it) }
                    } catch (e: IllegalStateException) {
                        Timber.e(e)
                    }
                }
            }
            false
        }
    }

    private fun drawReplyButton(canvas: Canvas, itemView: View) {
        // Timber.v("drawReplyButton")
        val translationX = abs(itemView.translationX)
        val newTime = System.currentTimeMillis()
        val dt = min(17, newTime - lastReplyButtonAnimationTime)
        lastReplyButtonAnimationTime = newTime
        val showing = translationX >= minShowDistance
        if (showing) {
            if (replyButtonProgress < 1.0f) {
                replyButtonProgress += dt / 180.0f
                if (replyButtonProgress > 1.0f) {
                    replyButtonProgress = 1.0f
                } else {
                    itemView.invalidate()
                }
            }
        } else if (translationX <= 0.0f) {
            replyButtonProgress = 0f
            startTracking = false
            isVibrate = false
        } else {
            if (replyButtonProgress > 0.0f) {
                replyButtonProgress -= dt / 180.0f
                if (replyButtonProgress < 0.1f) {
                    replyButtonProgress = 0f
                } else {
                    itemView.invalidate()
                }
            }
        }
        val alpha: Int
        val scale: Float
        if (showing) {
            scale = if (replyButtonProgress <= 0.8f) {
                1.2f * (replyButtonProgress / 0.8f)
            } else {
                1.2f - 0.2f * ((replyButtonProgress - 0.8f) / 0.2f)
            }
            alpha = min(255f, 255 * (replyButtonProgress / 0.8f)).toInt()
        } else {
            scale = replyButtonProgress
            alpha = min(255f, 255 * replyButtonProgress).toInt()
        }

        imageDrawable.alpha = alpha
        if (startTracking) {
            if (!isVibrate && translationX >= triggerDistance) {
                itemView.performHapticFeedback(
                        HapticFeedbackConstants.LONG_PRESS
//                        , HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                isVibrate = true
            }
        }

        val x: Int = itemView.width - if (translationX > triggerDistance + triggerDelta) {
            (convertToPx(130) / 2).toInt()
        } else {
            (translationX / 2).toInt()
        }

        val y = (itemView.top + itemView.measuredHeight / 2).toFloat()
        val hw = imageDrawable.intrinsicWidth / 2f
        val hh = imageDrawable.intrinsicHeight / 2f
        imageDrawable.setBounds(
                (x - hw * scale).toInt(),
                (y - hh * scale).toInt(),
                (x + hw * scale).toInt(),
                (y + hh * scale).toInt()
        )
        imageDrawable.draw(canvas)
        imageDrawable.alpha = 255
    }

    private fun convertToPx(dp: Int): Float {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
        )
    }
}

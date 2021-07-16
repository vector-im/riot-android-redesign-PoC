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
package im.vector.app.features.home.room.detail.timeline.item

import android.view.View
import android.view.ViewStub
import android.widget.RelativeLayout
import androidx.annotation.CallSuper
import androidx.annotation.IdRes
import androidx.core.view.updateLayoutParams
import com.airbnb.epoxy.EpoxyAttribute
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.platform.CheckableView
import im.vector.app.core.utils.DimensionConverter

/**
 * Children must override getViewType()
 */
abstract class BaseEventItem<H : BaseEventItem.BaseHolder> : VectorEpoxyModel<H>(), ItemWithEvents {

    // To use for instance when opening a permalink with an eventId
    @EpoxyAttribute
    var highlighted: Boolean = false

    @EpoxyAttribute
    open var leftGuideline: Int = 0

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    lateinit var dimensionConverter: DimensionConverter

    protected var ignoreSendStatusVisibility = false

    @CallSuper
    override fun bind(holder: H) {
        super.bind(holder)
        holder.leftGuideline.updateLayoutParams<RelativeLayout.LayoutParams> {
            this.marginStart = leftGuideline
        }
        // Ignore visibility of the send status icon?
        holder.contentContainer.updateLayoutParams<RelativeLayout.LayoutParams> {
            if (ignoreSendStatusVisibility) {
                addRule(RelativeLayout.ALIGN_PARENT_END)
            } else {
                removeRule(RelativeLayout.ALIGN_PARENT_END)
            }
        }
        holder.checkableBackground.isChecked = highlighted
    }

    abstract class BaseHolder(@IdRes val stubId: Int) : VectorEpoxyHolder() {
        val leftGuideline by bind<View>(R.id.messageStartGuideline)
        val contentContainer by bind<View>(R.id.viewStubContainer)
        val checkableBackground by bind<CheckableView>(R.id.messageSelectedBackground)

        override fun bindView(itemView: View) {
            super.bindView(itemView)
            inflateStub()
        }

        private fun inflateStub() {
            view.findViewById<ViewStub>(stubId).inflate()
        }
    }
}

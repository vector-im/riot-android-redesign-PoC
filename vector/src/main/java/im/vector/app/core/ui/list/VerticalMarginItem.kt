/*
 * Copyright 2021 New Vector Ltd
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
package im.vector.app.core.ui.list

import android.view.View
import androidx.core.view.updateLayoutParams
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel

/**
 * A generic item with empty space.
 */
@EpoxyModelClass(layout = R.layout.item_vertical_margin)
abstract class VerticalMarginItem : VectorEpoxyModel<VerticalMarginItem.Holder>() {

    @EpoxyAttribute
    var heightInPx: Int = 0

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.space.updateLayoutParams {
            height = heightInPx
        }
    }

    class Holder : VectorEpoxyHolder() {
        val space by bind<View>(R.id.item_vertical_margin_space)
    }
}

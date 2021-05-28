/*
 * Copyright (c) 2020 New Vector Ltd
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
package im.vector.app.core.ui.bottomsheet

import android.view.View
import com.airbnb.epoxy.TypedEpoxyController

/**
 * Epoxy controller for generic bottom sheet actions
 */
abstract class BottomSheetGenericController<State : BottomSheetGenericState, Action : BottomSheetGenericRadioAction>
    : TypedEpoxyController<State>() {

    var listener: Listener<Action>? = null

    abstract fun getTitle(): String?

    open fun getSubTitle(): String? = null

    abstract fun getActions(state: State): List<Action>

    override fun buildModels(state: State?) {
        state ?: return
        val host = this
        // Title
        getTitle()?.let { title ->
            bottomSheetTitleItem {
                id("title")
                title(title)
                subTitle(host.getSubTitle())
            }

//            dividerItem {
//                id("title_separator")
//            }
        }
        // Actions
        val actions = getActions(state)
        actions.forEach { action ->
            action.toRadioBottomSheetItem()
                    .listener(View.OnClickListener { listener?.didSelectAction(action) })
                    .addTo(this)
        }
    }

    interface Listener<Action> {
        fun didSelectAction(action: Action)
    }
}

/*
 * Copyright (c) 2020 New Vector Ltd
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
package im.vector.app.features.discovery

import android.widget.Button
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.airbnb.epoxy.EpoxyModelWithHolder
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.onClick

@EpoxyModelClass(layout = R.layout.item_settings_continue_cancel)
abstract class SettingsContinueCancelItem : EpoxyModelWithHolder<SettingsContinueCancelItem.Holder>() {

    @EpoxyAttribute
    var continueText: String? = null

    @EpoxyAttribute
    var continueOnClick: ClickListener? = null

    @EpoxyAttribute
    var canContinue: Boolean = true

    @EpoxyAttribute
    var cancelOnClick: ClickListener? = null

    override fun bind(holder: Holder) {
        super.bind(holder)

        holder.cancelButton.onClick(cancelOnClick)

        continueText?.let { holder.continueButton.text = it }
        holder.continueButton.onClick(continueOnClick)
        holder.continueButton.isEnabled = canContinue
    }

    class Holder : VectorEpoxyHolder() {
        val cancelButton by bind<Button>(R.id.settings_item_cancel_button)
        val continueButton by bind<Button>(R.id.settings_item_continue_button)
    }
}

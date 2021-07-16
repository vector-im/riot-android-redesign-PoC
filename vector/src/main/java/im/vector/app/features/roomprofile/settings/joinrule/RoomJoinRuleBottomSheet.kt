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

package im.vector.app.features.roomprofile.settings.joinrule

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.ui.bottomsheet.BottomSheetGeneric
import im.vector.app.core.ui.bottomsheet.BottomSheetGenericController
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import javax.inject.Inject

@Parcelize
data class JoinRulesOptionSupport(
        val rule: RoomJoinRules,
        val needUpgrade: Boolean = false
) : Parcelable

fun RoomJoinRules.toOption(needUpgrade: Boolean) = JoinRulesOptionSupport(this, needUpgrade)

@Parcelize
data class RoomJoinRuleBottomSheetArgs(
        val currentRoomJoinRule: RoomJoinRules,
        val allowedJoinedRules: List<JoinRulesOptionSupport>
) : Parcelable

class RoomJoinRuleBottomSheet : BottomSheetGeneric<RoomJoinRuleState, RoomJoinRuleRadioAction>() {

    private lateinit var roomJoinRuleSharedActionViewModel: RoomJoinRuleSharedActionViewModel
    @Inject lateinit var controller: RoomJoinRuleController
    private val viewModel: RoomJoinRuleViewModel by fragmentViewModel(RoomJoinRuleViewModel::class)

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    override fun getController(): BottomSheetGenericController<RoomJoinRuleState, RoomJoinRuleRadioAction> = controller

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        roomJoinRuleSharedActionViewModel = activityViewModelProvider.get(RoomJoinRuleSharedActionViewModel::class.java)
    }

    override fun didSelectAction(action: RoomJoinRuleRadioAction) {
        roomJoinRuleSharedActionViewModel.post(action)
        dismiss()
    }

    override fun invalidate() = withState(viewModel) {
        controller.setData(it)
        super.invalidate()
    }

    companion object {
        fun newInstance(currentRoomJoinRule: RoomJoinRules,
                        allowedJoinedRules: List<JoinRulesOptionSupport> = listOf(
                                RoomJoinRules.INVITE, RoomJoinRules.PUBLIC
                        ).map { it.toOption(true) }
        ): RoomJoinRuleBottomSheet {
            return RoomJoinRuleBottomSheet().apply {
                setArguments(
                        RoomJoinRuleBottomSheetArgs(currentRoomJoinRule, allowedJoinedRules)
                )
            }
        }
    }
}

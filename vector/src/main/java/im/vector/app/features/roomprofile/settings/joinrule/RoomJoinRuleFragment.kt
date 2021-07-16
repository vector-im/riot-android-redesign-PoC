/*
 * Copyright (c) 2021 New Vector Ltd
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentJoinRulesRecyclerBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomprofile.settings.joinrule.advanced.RoomJoinRuleChooseRestrictedActions
import im.vector.app.features.roomprofile.settings.joinrule.advanced.RoomJoinRuleChooseRestrictedViewModel
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import javax.inject.Inject

class RoomJoinRuleFragment @Inject constructor(
        val controller: RoomJoinRuleAdvancedController,
        val avatarRenderer: AvatarRenderer
) : VectorBaseFragment<FragmentJoinRulesRecyclerBinding>(),
        OnBackPressed, RoomJoinRuleAdvancedController.InteractionListener {

    private val viewModel: RoomJoinRuleChooseRestrictedViewModel by activityViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentJoinRulesRecyclerBinding.inflate(inflater, container, false)

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        val hasUnsavedChanges = withState(viewModel) { it.hasUnsavedChanges }
        val isLoading = withState(viewModel) { it.updatingStatus is Loading }
        if (!hasUnsavedChanges || isLoading) {
            requireActivity().finish()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_title_warning)
                    .setMessage(R.string.warning_unsaved_change)
                    .setPositiveButton(R.string.warning_unsaved_change_discard) { _, _ ->
                        requireActivity().finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            return true
        }
        return true
    }

    override fun invalidate() = withState(viewModel) { state ->
        super.invalidate()
        controller.setData(state)
        if (state.hasUnsavedChanges) {
            // show discard and save
            views.cancelButton.isVisible = true
            views.positiveButton.text = getString(R.string.warning_unsaved_change_discard)
            views.positiveButton.isVisible = true
            views.positiveButton.text = getString(R.string.save)
            views.positiveButton.debouncedClicks {
                viewModel.handle(RoomJoinRuleChooseRestrictedActions.DoUpdateJoinRules)
            }
        } else {
            views.cancelButton.isVisible = false
            views.positiveButton.isVisible = true
            views.positiveButton.text = getString(R.string.ok)
            views.positiveButton.debouncedClicks { requireActivity().finish() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.genericRecyclerView.configureWith(controller, hasFixedSize = true)
        controller.interactionListener = this
        views.cancelButton.debouncedClicks { requireActivity().finish() }
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun didSelectRule(rules: RoomJoinRules) {
        val isLoading = withState(viewModel) { it.updatingStatus is Loading }
        if (isLoading) return

        viewModel.handle(RoomJoinRuleChooseRestrictedActions.SelectJoinRules(rules))
    }
}

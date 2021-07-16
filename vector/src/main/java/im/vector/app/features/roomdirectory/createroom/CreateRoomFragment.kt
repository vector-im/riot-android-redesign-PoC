/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.roomdirectory.createroom

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.ColorProvider
import im.vector.app.databinding.FragmentCreateRoomBinding
import im.vector.app.features.roomdirectory.RoomDirectorySharedAction
import im.vector.app.features.roomdirectory.RoomDirectorySharedActionViewModel
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleBottomSheet
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleSharedActionViewModel
import im.vector.app.features.roomprofile.settings.joinrule.toOption
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import javax.inject.Inject

@Parcelize
data class CreateRoomArgs(
        val initialName: String,
        val parentSpaceId: String? = null
) : Parcelable

class CreateRoomFragment @Inject constructor(
        private val createRoomController: CreateRoomController,
        val createRoomViewModelFactory: CreateRoomViewModel.Factory,
        colorProvider: ColorProvider
) : VectorBaseFragment<FragmentCreateRoomBinding>(),
        CreateRoomController.Listener,
        GalleryOrCameraDialogHelper.Listener,
        OnBackPressed {

    private lateinit var sharedActionViewModel: RoomDirectorySharedActionViewModel
    private val viewModel: CreateRoomViewModel by fragmentViewModel()
    private val args: CreateRoomArgs by args()

    private lateinit var roomJoinRuleSharedActionViewModel: RoomJoinRuleSharedActionViewModel

    private val galleryOrCameraDialogHelper = GalleryOrCameraDialogHelper(this, colorProvider)

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentCreateRoomBinding {
        return FragmentCreateRoomBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vectorBaseActivity.setSupportActionBar(views.createRoomToolbar)
        sharedActionViewModel = activityViewModelProvider.get(RoomDirectorySharedActionViewModel::class.java)
        setupRoomJoinRuleSharedActionViewModel()
        setupWaitingView()
        setupRecyclerView()
        views.createRoomClose.debouncedClicks {
            sharedActionViewModel.post(RoomDirectorySharedAction.Back)
        }
        viewModel.observeViewEvents {
            when (it) {
                CreateRoomViewEvents.Quit       -> vectorBaseActivity.onBackPressed()
                is CreateRoomViewEvents.Failure -> showFailure(it.throwable)
            }.exhaustive
        }
    }

    private fun setupRoomJoinRuleSharedActionViewModel() {
        roomJoinRuleSharedActionViewModel = activityViewModelProvider.get(RoomJoinRuleSharedActionViewModel::class.java)
        roomJoinRuleSharedActionViewModel
                .observe()
                .subscribe { action ->
                    viewModel.handle(CreateRoomAction.SetVisibility(action.roomJoinRule))
                }
                .disposeOnDestroyView()
    }

    override fun showFailure(throwable: Throwable) {
        // Note: RoomAliasError are displayed directly in the form
        if (throwable !is CreateRoomFailure.AliasError) {
            super.showFailure(throwable)
        }
    }

    private fun setupWaitingView() {
        views.waitingView.waitingStatusText.isVisible = true
        views.waitingView.waitingStatusText.setText(R.string.create_room_in_progress)
    }

    override fun onDestroyView() {
        views.createRoomForm.cleanup()
        createRoomController.listener = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        views.createRoomForm.configureWith(createRoomController)
        createRoomController.listener = this
    }

    override fun onAvatarDelete() {
        viewModel.handle(CreateRoomAction.SetAvatar(null))
    }

    override fun onAvatarChange() {
        galleryOrCameraDialogHelper.show()
    }

    override fun onImageReady(uri: Uri?) {
        viewModel.handle(CreateRoomAction.SetAvatar(uri))
    }

    override fun onNameChange(newName: String) {
        viewModel.handle(CreateRoomAction.SetName(newName))
    }

    override fun onTopicChange(newTopic: String) {
        viewModel.handle(CreateRoomAction.SetTopic(newTopic))
    }

    override fun selectVisibility() = withState(viewModel) { state ->

        val allowed = if (state.supportsRestricted) {
            listOf(RoomJoinRules.INVITE, RoomJoinRules.PUBLIC, RoomJoinRules.RESTRICTED)
        } else {
            listOf(RoomJoinRules.INVITE, RoomJoinRules.PUBLIC)
        }
        RoomJoinRuleBottomSheet.newInstance(state.roomJoinRules, allowed.map { it.toOption(false) })
                .show(childFragmentManager, "RoomJoinRuleBottomSheet")
    }
//    override fun setIsPublic(isPublic: Boolean) {
//        viewModel.handle(CreateRoomAction.SetIsPublic(isPublic))
//    }

    override fun setAliasLocalPart(aliasLocalPart: String) {
        viewModel.handle(CreateRoomAction.SetRoomAliasLocalPart(aliasLocalPart))
    }

    override fun setIsEncrypted(isEncrypted: Boolean) {
        viewModel.handle(CreateRoomAction.SetIsEncrypted(isEncrypted))
    }

    override fun toggleShowAdvanced() {
        viewModel.handle(CreateRoomAction.ToggleShowAdvanced)
    }

    override fun setDisableFederation(disableFederation: Boolean) {
        viewModel.handle(CreateRoomAction.DisableFederation(disableFederation))
    }

    override fun submit() {
        viewModel.handle(CreateRoomAction.Create)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        return withState(viewModel) {
            return@withState if (!it.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dialog_title_warning)
                        .setMessage(R.string.warning_room_not_created_yet)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            viewModel.handle(CreateRoomAction.Reset)
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                true
            } else {
                false
            }
        }
    }

    override fun invalidate() = withState(viewModel) { state ->
        val async = state.asyncCreateRoomRequest
        views.waitingView.root.isVisible = async is Loading
        if (async is Success) {
            // Navigate to freshly created room
            navigator.openRoom(requireActivity(), async())

            sharedActionViewModel.post(RoomDirectorySharedAction.Close)
        } else {
            // Populate list with Epoxy
            createRoomController.setData(state)
        }
    }
}

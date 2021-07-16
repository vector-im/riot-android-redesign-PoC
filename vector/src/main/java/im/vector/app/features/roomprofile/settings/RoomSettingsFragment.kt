/*
 * Copyright 2020 New Vector Ltd
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

package im.vector.app.features.roomprofile.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentRoomSettingGenericBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.app.features.roomprofile.RoomProfileSharedActionViewModel
import im.vector.app.features.roomprofile.settings.historyvisibility.RoomHistoryVisibilityBottomSheet
import im.vector.app.features.roomprofile.settings.historyvisibility.RoomHistoryVisibilitySharedActionViewModel
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleActivity
import im.vector.app.features.roomprofile.settings.joinrule.RoomJoinRuleSharedActionViewModel
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.util.toMatrixItem
import java.util.UUID
import javax.inject.Inject

class RoomSettingsFragment @Inject constructor(
        val viewModelFactory: RoomSettingsViewModel.Factory,
        private val controller: RoomSettingsController,
        colorProvider: ColorProvider,
        private val avatarRenderer: AvatarRenderer
) :
        VectorBaseFragment<FragmentRoomSettingGenericBinding>(),
        RoomSettingsController.Callback,
        OnBackPressed,
        GalleryOrCameraDialogHelper.Listener,
        RoomSettingsViewModel.Factory {

    private val viewModel: RoomSettingsViewModel by fragmentViewModel()
    private lateinit var roomProfileSharedActionViewModel: RoomProfileSharedActionViewModel
    private lateinit var roomHistoryVisibilitySharedActionViewModel: RoomHistoryVisibilitySharedActionViewModel
    private lateinit var roomJoinRuleSharedActionViewModel: RoomJoinRuleSharedActionViewModel

    private val roomProfileArgs: RoomProfileArgs by args()
    private val galleryOrCameraDialogHelper = GalleryOrCameraDialogHelper(this, colorProvider)

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomSettingGenericBinding {
        return FragmentRoomSettingGenericBinding.inflate(inflater, container, false)
    }

    override fun getMenuRes() = R.menu.vector_room_settings

    override fun create(initialState: RoomSettingsViewState): RoomSettingsViewModel {
        return viewModelFactory.create(initialState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        roomProfileSharedActionViewModel = activityViewModelProvider.get(RoomProfileSharedActionViewModel::class.java)
        setupRoomHistoryVisibilitySharedActionViewModel()
        setupRoomJoinRuleSharedActionViewModel()
        controller.callback = this
        setupToolbar(views.roomSettingsToolbar)
        views.roomSettingsRecyclerView.configureWith(controller, hasFixedSize = true)
        views.waitingView.waitingStatusText.setText(R.string.please_wait)
        views.waitingView.waitingStatusText.isVisible = true

        viewModel.observeViewEvents {
            when (it) {
                is RoomSettingsViewEvents.Failure -> showFailure(it.throwable)
                RoomSettingsViewEvents.Success    -> showSuccess()
                RoomSettingsViewEvents.GoBack     -> {
                    ignoreChanges = true
                    vectorBaseActivity.onBackPressed()
                }
            }.exhaustive
        }
    }

    private fun setupRoomJoinRuleSharedActionViewModel() {
        roomJoinRuleSharedActionViewModel = activityViewModelProvider.get(RoomJoinRuleSharedActionViewModel::class.java)
        roomJoinRuleSharedActionViewModel
                .observe()
                .subscribe { action ->
                    viewModel.handle(RoomSettingsAction.SetRoomJoinRule(action.roomJoinRule))
                }
                .disposeOnDestroyView()
    }

    private fun setupRoomHistoryVisibilitySharedActionViewModel() {
        roomHistoryVisibilitySharedActionViewModel = activityViewModelProvider.get(RoomHistoryVisibilitySharedActionViewModel::class.java)
        roomHistoryVisibilitySharedActionViewModel
                .observe()
                .subscribe { action ->
                    viewModel.handle(RoomSettingsAction.SetRoomHistoryVisibility(action.roomHistoryVisibility))
                }
                .disposeOnDestroyView()
    }

    private fun showSuccess() {
        activity?.toast(R.string.room_settings_save_success)
    }

    override fun onDestroyView() {
        controller.callback = null
        views.roomSettingsRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        withState(viewModel) { state ->
            menu.findItem(R.id.roomSettingsSaveAction).isVisible = state.showSaveAction
        }
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.roomSettingsSaveAction) {
            viewModel.handle(RoomSettingsAction.Save)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun invalidate() = withState(viewModel) { viewState ->
        controller.setData(viewState)
        renderRoomSummary(viewState)
    }

    private fun renderRoomSummary(state: RoomSettingsViewState) {
        views.waitingView.root.isVisible = state.isLoading

        state.roomSummary()?.let {
            views.roomSettingsToolbarTitleView.text = it.displayName
            avatarRenderer.render(it.toMatrixItem(), views.roomSettingsToolbarAvatarImageView)
            views.roomSettingsDecorationToolbarAvatarImageView.render(it.roomEncryptionTrustLevel)
        }

        invalidateOptionsMenu()
    }

    override fun onNameChanged(name: String) {
        viewModel.handle(RoomSettingsAction.SetRoomName(name))
    }

    override fun onTopicChanged(topic: String) {
        viewModel.handle(RoomSettingsAction.SetRoomTopic(topic))
    }

    override fun onHistoryVisibilityClicked() = withState(viewModel) { state ->
        val currentHistoryVisibility = state.newHistoryVisibility ?: state.currentHistoryVisibility
        RoomHistoryVisibilityBottomSheet.newInstance(currentHistoryVisibility)
                .show(childFragmentManager, "RoomHistoryVisibilityBottomSheet")
    }

    override fun onJoinRuleClicked() {
        startActivity(RoomJoinRuleActivity.newIntent(requireContext(), roomProfileArgs.roomId))
    }

    override fun onToggleGuestAccess() = withState(viewModel) { state ->
        val currentGuestAccess = state.newRoomJoinRules.newGuestAccess ?: state.currentGuestAccess
        val toggled = if (currentGuestAccess == GuestAccess.Forbidden) GuestAccess.CanJoin else GuestAccess.Forbidden
        viewModel.handle(RoomSettingsAction.SetRoomGuestAccess(toggled))
    }

    override fun onImageReady(uri: Uri?) {
        uri ?: return
        viewModel.handle(
                RoomSettingsAction.SetAvatarAction(
                        RoomSettingsViewState.AvatarAction.UpdateAvatar(
                                newAvatarUri = uri,
                                newAvatarFileName = getFilenameFromUri(requireContext(), uri) ?: UUID.randomUUID().toString())
                )
        )
    }

    override fun onAvatarDelete() {
        withState(viewModel) {
            when (it.avatarAction) {
                RoomSettingsViewState.AvatarAction.None            -> {
                    viewModel.handle(RoomSettingsAction.SetAvatarAction(RoomSettingsViewState.AvatarAction.DeleteAvatar))
                }
                RoomSettingsViewState.AvatarAction.DeleteAvatar    -> {
                    /* Should not happen */
                }
                is RoomSettingsViewState.AvatarAction.UpdateAvatar -> {
                    // Cancel the update of the avatar
                    viewModel.handle(RoomSettingsAction.SetAvatarAction(RoomSettingsViewState.AvatarAction.None))
                }
            }
        }
    }

    override fun onAvatarChange() {
        galleryOrCameraDialogHelper.show()
    }

    private var ignoreChanges = false

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (ignoreChanges) return false

        return withState(viewModel) {
            return@withState if (it.showSaveAction) {
                MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dialog_title_warning)
                        .setMessage(R.string.warning_unsaved_change)
                        .setPositiveButton(R.string.warning_unsaved_change_discard) { _, _ ->
                            viewModel.handle(RoomSettingsAction.Cancel)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                true
            } else {
                false
            }
        }
    }
}

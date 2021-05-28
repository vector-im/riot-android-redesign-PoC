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

package im.vector.app.features.login2.created

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.resources.ColorProvider
import im.vector.app.databinding.DialogBaseEditTextBinding
import im.vector.app.databinding.FragmentLoginAccountCreatedBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.login2.AbstractLoginFragment2
import im.vector.app.features.login2.LoginAction2
import im.vector.app.features.login2.LoginActivity2
import im.vector.app.features.login2.LoginViewState2
import org.matrix.android.sdk.api.util.MatrixItem
import java.util.UUID
import javax.inject.Inject

/**
 * In this screen:
 * - the account has been created and we propose the user to set an avatar and a display name
 */
class AccountCreatedFragment @Inject constructor(
        val accountCreatedViewModelFactory: AccountCreatedViewModel.Factory,
        private val avatarRenderer: AvatarRenderer,
        private val dateFormatter: VectorDateFormatter,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        colorProvider: ColorProvider
) : AbstractLoginFragment2<FragmentLoginAccountCreatedBinding>(),
        GalleryOrCameraDialogHelper.Listener {

    private val viewModel: AccountCreatedViewModel by fragmentViewModel()

    private val galleryOrCameraDialogHelper = GalleryOrCameraDialogHelper(this, colorProvider)

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentLoginAccountCreatedBinding {
        return FragmentLoginAccountCreatedBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListener()
        setupSubmitButton()
        observeViewEvents()

        viewModel.subscribe { invalidateState(it) }

        views.loginAccountCreatedTime.text = dateFormatter.format(System.currentTimeMillis(), DateFormatKind.MESSAGE_SIMPLE)
    }

    private fun observeViewEvents() {
        viewModel.observeViewEvents {
            when (it) {
                is AccountCreatedViewEvents.Failure -> displayErrorDialog(it.throwable)
            }
        }
    }

    private fun setupClickListener() {
        views.loginAccountCreatedMessage.setOnClickListener {
            // Update display name
            displayDialog()
        }
        views.loginAccountCreatedAvatar.setOnClickListener {
            galleryOrCameraDialogHelper.show()
        }
    }

    private fun displayDialog() = withState(viewModel) { state ->
        val inflater = requireActivity().layoutInflater
        val layout = inflater.inflate(R.layout.dialog_base_edit_text, null)
        val views = DialogBaseEditTextBinding.bind(layout)
        views.editText.setText(state.currentUser()?.getBestName().orEmpty())

        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.settings_display_name)
                .setView(layout)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val newName = views.editText.text.toString()
                    viewModel.handle(AccountCreatedAction.SetDisplayName(newName))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    override fun onImageReady(uri: Uri?) {
        uri ?: return
        viewModel.handle(AccountCreatedAction.SetAvatar(
                avatarUri = uri,
                filename = getFilenameFromUri(requireContext(), uri) ?: UUID.randomUUID().toString())
        )
    }

    private fun setupSubmitButton() {
        views.loginAccountCreatedLater.setOnClickListener { terminate() }
        views.loginAccountCreatedDone.setOnClickListener { terminate() }
    }

    private fun terminate() {
        loginViewModel.handle(LoginAction2.Finish)
    }

    private fun invalidateState(state: AccountCreatedViewState) {
        // Ugly hack...
        (activity as? LoginActivity2)?.setIsLoading(state.isLoading)

        views.loginAccountCreatedSubtitle.text = getString(R.string.login_account_created_subtitle, state.userId)

        val user = state.currentUser()
        if (user != null) {
            avatarRenderer.render(user, views.loginAccountCreatedAvatar)
            views.loginAccountCreatedMemberName.text = user.getBestName()
        } else {
            // Should not happen
            views.loginAccountCreatedMemberName.text = state.userId
        }

        // User color
        views.loginAccountCreatedMemberName
                .setTextColor(matrixItemColorProvider.getColor(MatrixItem.UserItem(state.userId)))

        views.loginAccountCreatedLater.isVisible = state.hasBeenModified.not()
        views.loginAccountCreatedDone.isVisible = state.hasBeenModified
    }

    override fun updateWithState(state: LoginViewState2) {
        // No op
    }

    override fun resetViewModel() {
        // No op
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        // Just start the next Activity
        terminate()
        return false
    }
}

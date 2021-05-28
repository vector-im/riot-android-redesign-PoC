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

package im.vector.app.features.roomprofile.uploads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import im.vector.app.R
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.saveMedia
import im.vector.app.core.utils.shareMedia
import im.vector.app.databinding.FragmentRoomUploadsBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.roomprofile.RoomProfileArgs
import kotlinx.coroutines.launch

import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class RoomUploadsFragment @Inject constructor(
        private val viewModelFactory: RoomUploadsViewModel.Factory,
        private val avatarRenderer: AvatarRenderer,
        private val notificationUtils: NotificationUtils
) : VectorBaseFragment<FragmentRoomUploadsBinding>(),
        RoomUploadsViewModel.Factory by viewModelFactory {

    private val roomProfileArgs: RoomProfileArgs by args()

    private val viewModel: RoomUploadsViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomUploadsBinding {
        return FragmentRoomUploadsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sectionsPagerAdapter = RoomUploadsPagerAdapter(this)
        views.roomUploadsViewPager.adapter = sectionsPagerAdapter

        TabLayoutMediator(views.roomUploadsTabs, views.roomUploadsViewPager) { tab, position ->
            when (position) {
                0 -> tab.text = getString(R.string.uploads_media_title)
                1 -> tab.text = getString(R.string.uploads_files_title)
            }
        }.attach()

        setupToolbar(views.roomUploadsToolbar)

        viewModel.observeViewEvents {
            when (it) {
                is RoomUploadsViewEvents.FileReadyForSharing -> {
                    shareMedia(requireContext(), it.file, getMimeTypeFromUri(requireContext(), it.file.toUri()))
                }
                is RoomUploadsViewEvents.FileReadyForSaving  -> {
                    lifecycleScope.launch {
                        runCatching {
                            saveMedia(
                                    context = requireContext(),
                                    file = it.file,
                                    title = it.title,
                                    mediaMimeType = getMimeTypeFromUri(requireContext(), it.file.toUri()),
                                    notificationUtils = notificationUtils
                            )
                        }.onFailure { failure ->
                            if (!isAdded) return@onFailure
                            showErrorInSnackbar(failure)
                        }
                    }
                    Unit
                }
                is RoomUploadsViewEvents.Failure             -> showFailure(it.throwable)
            }.exhaustive
        }
    }

    override fun invalidate() = withState(viewModel) { state ->
        renderRoomSummary(state)
    }

    private fun renderRoomSummary(state: RoomUploadsViewState) {
        state.roomSummary()?.let {
            views.roomUploadsToolbarTitleView.text = it.displayName
            views.roomUploadsDecorationToolbarAvatarImageView.render(it.roomEncryptionTrustLevel)
            avatarRenderer.render(it.toMatrixItem(), views.roomUploadsToolbarAvatarImageView)
        }
    }

    val roomUploadsAppBar: AppBarLayout
        get() = views.roomUploadsAppBar
}

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

package im.vector.app.features.roomdirectory.picker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentRoomDirectoryPickerBinding
import im.vector.app.features.roomdirectory.RoomDirectoryAction
import im.vector.app.features.roomdirectory.RoomDirectoryData
import im.vector.app.features.roomdirectory.RoomDirectoryServer
import im.vector.app.features.roomdirectory.RoomDirectorySharedAction
import im.vector.app.features.roomdirectory.RoomDirectorySharedActionViewModel
import im.vector.app.features.roomdirectory.RoomDirectoryViewModel
import timber.log.Timber
import javax.inject.Inject

class RoomDirectoryPickerFragment @Inject constructor(val roomDirectoryPickerViewModelFactory: RoomDirectoryPickerViewModel.Factory,
                                                      private val roomDirectoryPickerController: RoomDirectoryPickerController
) : VectorBaseFragment<FragmentRoomDirectoryPickerBinding>(),
        OnBackPressed,
        RoomDirectoryPickerController.Callback {

    private val viewModel: RoomDirectoryViewModel by activityViewModel()
    private lateinit var sharedActionViewModel: RoomDirectorySharedActionViewModel
    private val pickerViewModel: RoomDirectoryPickerViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomDirectoryPickerBinding {
        return FragmentRoomDirectoryPickerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vectorBaseActivity.setSupportActionBar(views.toolbar)

        vectorBaseActivity.supportActionBar?.let {
            it.setDisplayShowHomeEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
        }

        sharedActionViewModel = activityViewModelProvider.get(RoomDirectorySharedActionViewModel::class.java)
        setupRecyclerView()

        // Give the current data to our controller. There maybe a better way to do that...
        withState(viewModel) {
            roomDirectoryPickerController.currentRoomDirectoryData = it.roomDirectoryData
        }
    }

    override fun onDestroyView() {
        views.roomDirectoryPickerList.cleanup()
        roomDirectoryPickerController.callback = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        views.roomDirectoryPickerList.configureWith(roomDirectoryPickerController)
        roomDirectoryPickerController.callback = this
    }

    override fun onRoomDirectoryClicked(roomDirectoryData: RoomDirectoryData) {
        Timber.v("onRoomDirectoryClicked: $roomDirectoryData")
        viewModel.handle(RoomDirectoryAction.SetRoomDirectoryData(roomDirectoryData))

        sharedActionViewModel.post(RoomDirectorySharedAction.Back)
    }

    override fun onStartEnterServer() {
        pickerViewModel.handle(RoomDirectoryPickerAction.EnterEditMode)
    }

    override fun onCancelEnterServer() {
        pickerViewModel.handle(RoomDirectoryPickerAction.ExitEditMode)
    }

    override fun onEnterServerChange(server: String) {
        pickerViewModel.handle(RoomDirectoryPickerAction.SetServerUrl(server))
    }

    override fun onSubmitServer() {
        pickerViewModel.handle(RoomDirectoryPickerAction.Submit)
    }

    override fun onRemoveServer(roomDirectoryServer: RoomDirectoryServer) {
        pickerViewModel.handle(RoomDirectoryPickerAction.RemoveServer(roomDirectoryServer))
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.select_room_directory)
    }

    override fun retry() {
        Timber.v("Retry")
        pickerViewModel.handle(RoomDirectoryPickerAction.Retry)
    }

    override fun invalidate() = withState(pickerViewModel) { state ->
        // Populate list with Epoxy
        roomDirectoryPickerController.setData(state)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        // Leave the add server mode if started
        return withState(pickerViewModel) {
            if (it.inEditMode) {
                pickerViewModel.handle(RoomDirectoryPickerAction.ExitEditMode)
                true
            } else {
                false
            }
        }
    }
}

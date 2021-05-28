/*
 * Copyright 2019 New Vector Ltd
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
package im.vector.app.features.home.room.detail.timeline.edithistory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.databinding.BottomSheetGenericListWithTitleBinding
import im.vector.app.features.home.room.detail.timeline.action.TimelineEventFragmentArgs
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData

import javax.inject.Inject

/**
 * Bottom sheet displaying list of edits for a given event ordered by timestamp
 */
class ViewEditHistoryBottomSheet:
        VectorBaseBottomSheetDialogFragment<BottomSheetGenericListWithTitleBinding>() {

    private val viewModel: ViewEditHistoryViewModel by fragmentViewModel(ViewEditHistoryViewModel::class)

    @Inject lateinit var viewEditHistoryViewModelFactory: ViewEditHistoryViewModel.Factory
    @Inject lateinit var epoxyController: ViewEditHistoryEpoxyController

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetGenericListWithTitleBinding {
        return BottomSheetGenericListWithTitleBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.bottomSheetRecyclerView.configureWith(
                epoxyController,
                showDivider = true,
                hasFixedSize = false)
        views.bottomSheetTitle.text = context?.getString(R.string.message_edits)
    }

    override fun onDestroyView() {
        views.bottomSheetRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) {
        epoxyController.setData(it)
        super.invalidate()
    }

    companion object {
        fun newInstance(roomId: String, informationData: MessageInformationData): ViewEditHistoryBottomSheet {
            val args = Bundle()
            val parcelableArgs = TimelineEventFragmentArgs(
                    informationData.eventId,
                    roomId,
                    informationData
            )
            args.putParcelable(MvRx.KEY_ARG, parcelableArgs)
            return ViewEditHistoryBottomSheet().apply { arguments = args }
        }
    }
}

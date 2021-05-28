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

package im.vector.app.features.settings.devtools

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.dialogs.withColoredButton
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.createJSonViewerStyleProvider
import im.vector.app.databinding.FragmentGenericRecyclerBinding

import org.billcarsonfr.jsonviewer.JSonViewerDialog
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.internal.di.MoshiProvider
import javax.inject.Inject

class AccountDataFragment @Inject constructor(
        val viewModelFactory: AccountDataViewModel.Factory,
        private val epoxyController: AccountDataEpoxyController,
        private val colorProvider: ColorProvider
) : VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        AccountDataEpoxyController.InteractionListener {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericRecyclerBinding {
        return FragmentGenericRecyclerBinding.inflate(inflater, container, false)
    }

    private val viewModel: AccountDataViewModel by fragmentViewModel(AccountDataViewModel::class)

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.settings_account_data)
    }

    override fun invalidate() = withState(viewModel) { state ->
        epoxyController.setData(state)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.genericRecyclerView.configureWith(epoxyController, showDivider = true)
        epoxyController.interactionListener = this
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        epoxyController.interactionListener = null
        super.onDestroyView()
    }

    override fun didTap(data: AccountDataEvent) {
        val jsonString = MoshiProvider.providesMoshi()
                .adapter(AccountDataEvent::class.java)
                .toJson(data)
        JSonViewerDialog.newInstance(
                jsonString,
                -1, // open All
                createJSonViewerStyleProvider(colorProvider)
        ).show(childFragmentManager, "JSON_VIEWER")
    }

    override fun didLongTap(data: AccountDataEvent) {
        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.delete_account_data_warning, data.type))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.handle(AccountDataAction.DeleteAccountData(data.type))
                }
                .show()
                .withColoredButton(DialogInterface.BUTTON_POSITIVE)
    }
}

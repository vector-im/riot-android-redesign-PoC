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

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.AccountDataEvent
import org.matrix.android.sdk.rx.rx

data class AccountDataViewState(
        val accountData: Async<List<AccountDataEvent>> = Uninitialized
) : MvRxState

class AccountDataViewModel @AssistedInject constructor(@Assisted initialState: AccountDataViewState,
                                                       private val session: Session)
    : VectorViewModel<AccountDataViewState, AccountDataAction, EmptyViewEvents>(initialState) {

    init {
        session.rx().liveUserAccountData(emptySet())
                .execute {
                    copy(accountData = it)
                }
    }

    override fun handle(action: AccountDataAction) {
        when (action) {
            is AccountDataAction.DeleteAccountData -> handleDeleteAccountData(action)
        }.exhaustive
    }

    private fun handleDeleteAccountData(action: AccountDataAction.DeleteAccountData) {
        viewModelScope.launch {
            session.userAccountDataService().updateAccountData(action.type, emptyMap())
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(initialState: AccountDataViewState): AccountDataViewModel
    }

    companion object : MvRxViewModelFactory<AccountDataViewModel, AccountDataViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: AccountDataViewState): AccountDataViewModel? {
            val fragment: AccountDataFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.viewModelFactory.create(state)
        }
    }
}

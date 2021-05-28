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

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.rx.rx
import org.matrix.android.sdk.rx.unwrap
import timber.log.Timber

class AccountCreatedViewModel @AssistedInject constructor(
        @Assisted initialState: AccountCreatedViewState,
        private val session: Session
) : VectorViewModel<AccountCreatedViewState, AccountCreatedAction, AccountCreatedViewEvents>(initialState) {

    @AssistedFactory
    interface Factory {
        fun create(initialState: AccountCreatedViewState): AccountCreatedViewModel
    }

    companion object : MvRxViewModelFactory<AccountCreatedViewModel, AccountCreatedViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: AccountCreatedViewState): AccountCreatedViewModel? {
            val fragment: AccountCreatedFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.accountCreatedViewModelFactory.create(state)
        }
    }

    init {
        setState {
            copy(
                    userId = session.myUserId
            )
        }
        observeUser()
    }

    private fun observeUser() {
        session.rx()
                .liveUser(session.myUserId)
                .unwrap()
                .map {
                    if (MatrixPatterns.isUserId(it.userId)) {
                        it.toMatrixItem()
                    } else {
                        Timber.w("liveUser() has returned an invalid user: $it")
                        MatrixItem.UserItem(session.myUserId, null, null)
                    }
                }
                .execute {
                    copy(currentUser = it)
                }
    }

    override fun handle(action: AccountCreatedAction) {
        when (action) {
            is AccountCreatedAction.SetAvatar      -> handleSetAvatar(action)
            is AccountCreatedAction.SetDisplayName -> handleSetDisplayName(action)
        }
    }

    private fun handleSetAvatar(action: AccountCreatedAction.SetAvatar) {
        setState { copy(isLoading = true) }
        viewModelScope.launch {
            val result = runCatching { session.updateAvatar(session.myUserId, action.avatarUri, action.filename) }
                    .onFailure { _viewEvents.post(AccountCreatedViewEvents.Failure(it)) }
            setState {
                copy(
                        isLoading = false,
                        hasBeenModified = hasBeenModified || result.isSuccess
                )
            }
        }
    }

    private fun handleSetDisplayName(action: AccountCreatedAction.SetDisplayName) {
        setState { copy(isLoading = true) }
        viewModelScope.launch {
            val result = runCatching { session.setDisplayName(session.myUserId, action.displayName) }
                    .onFailure { _viewEvents.post(AccountCreatedViewEvents.Failure(it)) }
            setState {
                copy(
                        isLoading = false,
                        hasBeenModified = hasBeenModified || result.isSuccess
                )
            }
        }
    }
}

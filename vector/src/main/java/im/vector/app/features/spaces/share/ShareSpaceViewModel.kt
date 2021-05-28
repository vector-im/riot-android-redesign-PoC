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

package im.vector.app.features.spaces.share

import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.powerlevel.PowerLevelsObservableFactory
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper

class ShareSpaceViewModel @AssistedInject constructor(
        @Assisted private val initialState: ShareSpaceViewState,
        private val session: Session) : VectorViewModel<ShareSpaceViewState, ShareSpaceAction, ShareSpaceViewEvents>(initialState) {

    @AssistedFactory
    interface Factory {
        fun create(initialState: ShareSpaceViewState): ShareSpaceViewModel
    }

    companion object : MvRxViewModelFactory<ShareSpaceViewModel, ShareSpaceViewState> {
        override fun create(viewModelContext: ViewModelContext, state: ShareSpaceViewState): ShareSpaceViewModel? {
            val factory = when (viewModelContext) {
                is FragmentViewModelContext -> viewModelContext.fragment as? Factory
                is ActivityViewModelContext -> viewModelContext.activity as? Factory
            }
            return factory?.create(state) ?: error("You should let your activity/fragment implements Factory interface")
        }
    }

    init {
        val roomSummary = session.getRoomSummary(initialState.spaceId)
        setState {
            copy(
                    spaceSummary = roomSummary?.let { Success(it) } ?: Uninitialized,
                    canShareLink = roomSummary?.isPublic.orFalse()
            )
        }
        observePowerLevel()
    }

    private fun observePowerLevel() {
        val room = session.getRoom(initialState.spaceId) ?: return
        PowerLevelsObservableFactory(room)
                .createObservable()
                .subscribe { powerLevelContent ->
                    val powerLevelsHelper = PowerLevelsHelper(powerLevelContent)
                    setState {
                        copy(
                                canInviteByMxId = powerLevelsHelper.isUserAbleToInvite(session.myUserId)
                        )
                    }
                }
                .disposeOnClear()
    }

    override fun handle(action: ShareSpaceAction) {
        when (action) {
            ShareSpaceAction.InviteByLink -> {
                val roomSummary = session.getRoomSummary(initialState.spaceId)
                val alias = roomSummary?.canonicalAlias
                val permalink = if (alias != null) {
                    session.permalinkService().createPermalink(alias)
                } else {
                    session.permalinkService().createRoomPermalink(initialState.spaceId)
                }
                if (permalink != null) {
                    _viewEvents.post(ShareSpaceViewEvents.ShowInviteByLink(permalink, roomSummary?.name ?: ""))
                }
            }
            ShareSpaceAction.InviteByMxId -> {
                _viewEvents.post(ShareSpaceViewEvents.NavigateToInviteUser(initialState.spaceId))
            }
        }
    }
}

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

package im.vector.app.features.roomdirectory

import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import com.airbnb.mvrx.appendAt
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsFilter
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsParams
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.rx.rx
import timber.log.Timber

class RoomDirectoryViewModel @AssistedInject constructor(
        @Assisted initialState: PublicRoomsViewState,
        vectorPreferences: VectorPreferences,
        private val session: Session,
        private val explicitTermFilter: ExplicitTermFilter
) : VectorViewModel<PublicRoomsViewState, RoomDirectoryAction, RoomDirectoryViewEvents>(initialState) {

    @AssistedFactory
    interface Factory {
        fun create(initialState: PublicRoomsViewState): RoomDirectoryViewModel
    }

    companion object : MvRxViewModelFactory<RoomDirectoryViewModel, PublicRoomsViewState> {
        private const val PUBLIC_ROOMS_LIMIT = 20

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: PublicRoomsViewState): RoomDirectoryViewModel? {
            val activity: RoomDirectoryActivity = (viewModelContext as ActivityViewModelContext).activity()
            return activity.roomDirectoryViewModelFactory.create(state)
        }
    }

    private val showAllRooms = vectorPreferences.showAllPublicRooms()

    private var since: String? = null

    private var currentJob: Job? = null

    init {
        // Observe joined room (from the sync)
        observeJoinedRooms()
        observeMembershipChanges()
    }

    private fun observeJoinedRooms() {
        val queryParams = roomSummaryQueryParams {
            memberships = listOf(Membership.JOIN)
        }
        session
                .rx()
                .liveRoomSummaries(queryParams)
                .subscribe { list ->
                    val joinedRoomIds = list
                            ?.map { it.roomId }
                            ?.toSet()
                            .orEmpty()

                    setState {
                        copy(joinedRoomsIds = joinedRoomIds)
                    }
                }
                .disposeOnClear()
    }

    private fun observeMembershipChanges() {
        session.rx()
                .liveRoomChangeMembershipState()
                .subscribe {
                    setState { copy(changeMembershipStates = it) }
                }
                .disposeOnClear()
    }

    override fun handle(action: RoomDirectoryAction) {
        when (action) {
            is RoomDirectoryAction.SetRoomDirectoryData -> setRoomDirectoryData(action)
            is RoomDirectoryAction.FilterWith           -> filterWith(action)
            RoomDirectoryAction.LoadMore                -> loadMore()
            is RoomDirectoryAction.JoinRoom             -> joinRoom(action)
        }
    }

    private fun setRoomDirectoryData(action: RoomDirectoryAction.SetRoomDirectoryData) = withState {
        if (it.roomDirectoryData == action.roomDirectoryData) {
            return@withState
        }
        setState {
            copy(roomDirectoryData = action.roomDirectoryData)
        }
        reset("")
        load("", action.roomDirectoryData)
    }

    private fun filterWith(action: RoomDirectoryAction.FilterWith) = withState { state ->
        if (state.currentFilter != action.filter) {
            currentJob?.cancel()

            reset(action.filter)
            load(action.filter, state.roomDirectoryData)
        }
    }

    private fun reset(newFilter: String) {
        // Reset since token
        since = null

        setState {
            copy(
                    publicRooms = emptyList(),
                    asyncPublicRoomsRequest = Loading(),
                    hasMore = false,
                    currentFilter = newFilter
            )
        }
    }

    private fun loadMore() = withState { state ->
        if (currentJob == null) {
            setState {
                copy(
                        asyncPublicRoomsRequest = Loading()
                )
            }
            load(state.currentFilter, state.roomDirectoryData)
        }
    }

    private fun load(filter: String, roomDirectoryData: RoomDirectoryData) {
        if (!showAllRooms && !explicitTermFilter.canSearchFor(filter)) {
            setState {
                copy(
                        asyncPublicRoomsRequest = Success(Unit),
                        publicRooms = emptyList(),
                        hasMore = false
                )
            }
            return
        }

        currentJob = viewModelScope.launch {
            val data = try {
                session.getPublicRooms(roomDirectoryData.homeServer,
                        PublicRoomsParams(
                                limit = PUBLIC_ROOMS_LIMIT,
                                filter = PublicRoomsFilter(searchTerm = filter),
                                includeAllNetworks = roomDirectoryData.includeAllNetworks,
                                since = since,
                                thirdPartyInstanceId = roomDirectoryData.thirdPartyInstanceId
                        )
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    // Ignore, another request should be already started
                    return@launch
                }

                currentJob = null

                setState {
                    copy(
                            asyncPublicRoomsRequest = Fail(failure)
                    )
                }
                null
            }

            data ?: return@launch

            currentJob = null

            since = data.nextBatch

            // Filter
            val newPublicRooms = data.chunk.orEmpty()
                    .filter {
                        showAllRooms || explicitTermFilter.isValid("${it.name.orEmpty()} ${it.topic.orEmpty()}")
                    }

            setState {
                copy(
                        asyncPublicRoomsRequest = Success(Unit),
                        // It's ok to append at the end of the list, so I use publicRooms.size()
                        publicRooms = publicRooms.appendAt(newPublicRooms, publicRooms.size)
                                // Rageshake #8206 tells that we can have several times the same room
                                .distinctBy { it.roomId },
                        hasMore = since != null
                )
            }
        }
    }

    private fun joinRoom(action: RoomDirectoryAction.JoinRoom) = withState { state ->
        val roomMembershipChange = state.changeMembershipStates[action.roomId]
        if (roomMembershipChange?.isInProgress().orFalse()) {
            // Request already sent, should not happen
            Timber.w("Try to join an already joining room. Should not happen")
            return@withState
        }
        val viaServers = listOfNotNull(state.roomDirectoryData.homeServer)
        viewModelScope.launch {
            try {
                session.joinRoom(action.roomId, viaServers = viaServers)
                // We do not update the joiningRoomsIds here, because, the room is not joined yet regarding the sync data.
                // Instead, we wait for the room to be joined
            } catch (failure: Throwable) {
                // Notify the user
                _viewEvents.post(RoomDirectoryViewEvents.Failure(failure))
            }
        }
    }

    override fun onCleared() {
        currentJob?.cancel()
        super.onCleared()
    }
}

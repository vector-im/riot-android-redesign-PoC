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

import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import com.airbnb.mvrx.appendAt
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.utils.AnalyticsEngine
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsFilter
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsParams
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsResponse
import org.matrix.android.sdk.api.session.room.model.thirdparty.RoomDirectoryData
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.rx.rx
import timber.log.Timber

private const val PUBLIC_ROOMS_LIMIT = 20

class RoomDirectoryViewModel @AssistedInject constructor(@Assisted initialState: PublicRoomsViewState,
                                                         private val analyticsEngine: AnalyticsEngine,
                                                         private val session: Session)
    : VectorViewModel<PublicRoomsViewState, RoomDirectoryAction, RoomDirectoryViewEvents>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: PublicRoomsViewState): RoomDirectoryViewModel
    }

    companion object : MvRxViewModelFactory<RoomDirectoryViewModel, PublicRoomsViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: PublicRoomsViewState): RoomDirectoryViewModel? {
            val activity: RoomDirectoryActivity = (viewModelContext as ActivityViewModelContext).activity()
            return activity.roomDirectoryViewModelFactory.create(state)
        }
    }

    private var since: String? = null

    private var currentTask: Cancelable? = null

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
                            ?: emptySet()

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
            RoomDirectoryAction.StartReport             -> {
                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.StartRoomDirectory)
            }
            RoomDirectoryAction.EndReport               ->  {
                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.EndRoomDirectory)
            }
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
            currentTask?.cancel()

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
        if (currentTask == null) {
            setState {
                copy(
                        asyncPublicRoomsRequest = Loading()
                )
            }
            load(state.currentFilter, state.roomDirectoryData)
        }
    }

    private fun load(filter: String, roomDirectoryData: RoomDirectoryData) {
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.StartRoomDirectorySearch)
        currentTask = session.getPublicRooms(roomDirectoryData.homeServer,
                PublicRoomsParams(
                        limit = PUBLIC_ROOMS_LIMIT,
                        filter = PublicRoomsFilter(searchTerm = filter),
                        includeAllNetworks = roomDirectoryData.includeAllNetworks,
                        since = since,
                        thirdPartyInstanceId = roomDirectoryData.thirdPartyInstanceId
                ),
                object : MatrixCallback<PublicRoomsResponse> {
                    override fun onSuccess(data: PublicRoomsResponse) {
                        currentTask = null

                        since = data.nextBatch

                        setState {
                            copy(
                                    asyncPublicRoomsRequest = Success(data.chunk!!),
                                    // It's ok to append at the end of the list, so I use publicRooms.size()
                                    publicRooms = publicRooms.appendAt(data.chunk!!, publicRooms.size)
                                            // Rageshake #8206 tells that we can have several times the same room
                                            .distinctBy { it.roomId },
                                    hasMore = since != null
                            )
                        }
                        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.EndRoomDirectorySearch(data.chunk?.size ?: 0))
                    }

                    override fun onFailure(failure: Throwable) {
                        if (failure is Failure.Cancelled) {
                            // Ignore, another request should be already started
                            analyticsEngine.report(AnalyticsEngine.AnalyticEvent.EndRoomDirectorySearch(0))
                            return
                        }

                        currentTask = null

                        setState {
                            copy(
                                    asyncPublicRoomsRequest = Fail(failure)
                            )
                        }
                        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.EndRoomDirectorySearch(0))
                    }
                })
    }

    private fun joinRoom(action: RoomDirectoryAction.JoinRoom) = withState { state ->
        val roomMembershipChange = state.changeMembershipStates[action.roomId]
        if (roomMembershipChange?.isInProgress().orFalse()) {
            // Request already sent, should not happen
            Timber.w("Try to join an already joining room. Should not happen")
            return@withState
        }
        val viaServers = state.roomDirectoryData.homeServer?.let {
            listOf(it)
        } ?: emptyList()
        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.StartJoinRoomEvent)
        session.joinRoom(action.roomId, viaServers = viaServers, callback = object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                // We do not update the joiningRoomsIds here, because, the room is not joined yet regarding the sync data.
                // Instead, we wait for the room to be joined
                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.EndJoinRoomEvent(
                        roomId = action.roomId,
                        isPublic = true,
                        memberCount = 0,
                        isEncrypted = false
                ))
            }

            override fun onFailure(failure: Throwable) {
                // Notify the user
                _viewEvents.post(RoomDirectoryViewEvents.Failure(failure))
                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.CancelJoinRoomEvent)
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        currentTask?.cancel()
    }
}

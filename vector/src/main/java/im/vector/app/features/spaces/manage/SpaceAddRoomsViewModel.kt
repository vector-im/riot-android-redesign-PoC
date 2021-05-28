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

package im.vector.app.features.spaces.manage

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagedList
import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.ActiveSpaceFilter
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams

class AddRoomError(val errorList: Map<String, Throwable>) : Throwable() {
    override fun getLocalizedMessage(): String? {
        return errorList.map { it.value.localizedMessage }.joinToString()
    }
}

class SpaceAddRoomsViewModel @AssistedInject constructor(
        @Assisted val initialState: SpaceAddRoomsState,
        private val session: Session
) : VectorViewModel<SpaceAddRoomsState, SpaceAddRoomActions, SpaceAddRoomsViewEvents>(initialState) {

    @AssistedFactory
    interface Factory {
        fun create(initialState: SpaceAddRoomsState): SpaceAddRoomsViewModel
    }

    val updatableLiveSpacePageResult: UpdatableLivePageResult by lazy {
        session.getFilteredPagedRoomSummariesLive(
                roomSummaryQueryParams {
                    this.memberships = listOf(Membership.JOIN)
                    this.excludeType = null
                    this.includeType = listOf(RoomType.SPACE)
                    this.activeSpaceFilter = ActiveSpaceFilter.ExcludeSpace(initialState.spaceId)
                    this.displayName = QueryStringValue.Contains(initialState.currentFilter, QueryStringValue.Case.INSENSITIVE)
                },
                pagedListConfig = PagedList.Config.Builder()
                        .setPageSize(10)
                        .setInitialLoadSizeHint(20)
                        .setEnablePlaceholders(true)
                        .setPrefetchDistance(10)
                        .build(),
                sortOrder = RoomSortOrder.NAME
        )
    }

    val updatableLivePageResult: UpdatableLivePageResult by lazy {
        session.getFilteredPagedRoomSummariesLive(
                roomSummaryQueryParams {
                    this.memberships = listOf(Membership.JOIN)
                    this.excludeType = listOf(RoomType.SPACE)
                    this.includeType = null
                    this.roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
                    this.activeSpaceFilter = ActiveSpaceFilter.ExcludeSpace(initialState.spaceId)
                    this.displayName = QueryStringValue.Contains(initialState.currentFilter, QueryStringValue.Case.INSENSITIVE)
                },
                pagedListConfig = PagedList.Config.Builder()
                        .setPageSize(10)
                        .setInitialLoadSizeHint(20)
                        .setEnablePlaceholders(true)
                        .setPrefetchDistance(10)
                        .build(),
                sortOrder = RoomSortOrder.NAME
        )
    }

    val updatableDMLivePageResult: UpdatableLivePageResult by lazy {
        session.getFilteredPagedRoomSummariesLive(
                roomSummaryQueryParams {
                    this.memberships = listOf(Membership.JOIN)
                    this.excludeType = listOf(RoomType.SPACE)
                    this.includeType = null
                    this.roomCategoryFilter = RoomCategoryFilter.ONLY_DM
                    this.activeSpaceFilter = ActiveSpaceFilter.ExcludeSpace(initialState.spaceId)
                    this.displayName = QueryStringValue.Contains(initialState.currentFilter, QueryStringValue.Case.INSENSITIVE)
                },
                pagedListConfig = PagedList.Config.Builder()
                        .setPageSize(10)
                        .setInitialLoadSizeHint(20)
                        .setEnablePlaceholders(true)
                        .setPrefetchDistance(10)
                        .build(),
                sortOrder = RoomSortOrder.NAME
        )
    }

    private val selectionList = mutableMapOf<String, Boolean>()
    val selectionListLiveData = MutableLiveData<Map<String, Boolean>>()

    init {
        val spaceSummary = session.getRoomSummary(initialState.spaceId)
        setState {
            copy(
                    spaceName = spaceSummary?.displayName ?: "",
                    ignoreRooms = (spaceSummary?.flattenParentIds ?: emptyList()) + listOf(initialState.spaceId),
                    shouldShowDMs = spaceSummary?.isPublic == false
            )
        }
    }

    companion object : MvRxViewModelFactory<SpaceAddRoomsViewModel, SpaceAddRoomsState> {
        override fun create(viewModelContext: ViewModelContext, state: SpaceAddRoomsState): SpaceAddRoomsViewModel? {
            val factory = when (viewModelContext) {
                is FragmentViewModelContext -> viewModelContext.fragment as? Factory
                is ActivityViewModelContext -> viewModelContext.activity as? Factory
            }
            return factory?.create(state) ?: error("You should let your activity/fragment implements Factory interface")
        }
    }

    fun canGoBack(): Boolean {
        val needToSave = selectionList.values.any { it }
        if (needToSave) {
            _viewEvents.post(SpaceAddRoomsViewEvents.WarnUnsavedChanged)
        }
        return !needToSave
    }

    override fun handle(action: SpaceAddRoomActions) {
        when (action) {
            is SpaceAddRoomActions.UpdateFilter -> {
                updatableLivePageResult.updateQuery {
                    it.copy(
                            displayName = QueryStringValue.Contains(action.filter, QueryStringValue.Case.INSENSITIVE)
                    )
                }
                updatableLiveSpacePageResult.updateQuery {
                    it.copy(
                            displayName = QueryStringValue.Contains(action.filter, QueryStringValue.Case.INSENSITIVE)
                    )
                }
                setState {
                    copy(
                            currentFilter = action.filter
                    )
                }
            }
            is SpaceAddRoomActions.ToggleSelection -> {
                selectionList[action.roomSummary.roomId] = (selectionList[action.roomSummary.roomId] ?: false).not()
                selectionListLiveData.postValue(selectionList.toMap())
            }
            SpaceAddRoomActions.Save -> {
                doAddSelectedRooms()
            }
        }
    }

    private fun doAddSelectedRooms() {
        val childrenToAdd = selectionList.filter { it.value }.keys
        if (childrenToAdd.isEmpty()) return // should not happen

        setState {
            copy(
                    isSaving = Loading()
            )
        }
        viewModelScope.launch {
            val errors = mutableMapOf<String, Throwable>()
            val completed = mutableListOf<String>()
            childrenToAdd.forEach { roomId ->
                try {
                    session.spaceService().getSpace(initialState.spaceId)!!.addChildren(
                            roomId = roomId,
                            viaServers = null,
                            order = null
                    )
                    completed.add(roomId)
                } catch (failure: Throwable) {
                    errors[roomId] = failure
                }
            }
            if (errors.isEmpty()) {
                // success
                withContext(Dispatchers.Main) {
                    setState {
                        copy(
                                isSaving = Success(childrenToAdd.toList())
                        )
                    }
                    completed.forEach {
                        selectionList.remove(it)
                    }
                    _viewEvents.post(SpaceAddRoomsViewEvents.SavedDone)
                }
            } else if (errors.size < childrenToAdd.size) {
                // partial success
                withContext(Dispatchers.Main) {
                    setState {
                        copy(
                                isSaving = Success(completed)
                        )
                    }
                    completed.forEach {
                        selectionList.remove(it)
                    }
                    _viewEvents.post(SpaceAddRoomsViewEvents.SavedDone)
                }
            } else {
                // error
                withContext(Dispatchers.Main) {
                    setState {
                        copy(
                                isSaving = Fail(AddRoomError(errors))
                        )
                    }
                    _viewEvents.post(SpaceAddRoomsViewEvents.SaveFailed(AddRoomError(errors)))
                }
            }
        }
    }
}

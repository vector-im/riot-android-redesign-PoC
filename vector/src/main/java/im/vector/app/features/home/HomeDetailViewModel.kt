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

package im.vector.app.features.home

import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.grouplist.SelectedGroupDataSource
import im.vector.app.features.ui.UiStateRepository
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import io.reactivex.schedulers.Schedulers
import org.matrix.android.sdk.rx.rx

/**
 * View model used to update the home bottom bar notification counts, observe the sync state and
 * change the selected room list view
 */
class HomeDetailViewModel @AssistedInject constructor(@Assisted initialState: HomeDetailViewState,
                                                      private val session: Session,
                                                      private val uiStateRepository: UiStateRepository,
                                                      private val selectedGroupStore: SelectedGroupDataSource,
                                                      private val homeRoomListStore: HomeRoomListDataSource,
                                                      private val stringProvider: StringProvider)
    : VectorViewModel<HomeDetailViewState, HomeDetailAction, EmptyViewEvents>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: HomeDetailViewState): HomeDetailViewModel
    }

    companion object : MvRxViewModelFactory<HomeDetailViewModel, HomeDetailViewState> {

        override fun initialState(viewModelContext: ViewModelContext): HomeDetailViewState? {
//            val uiStateRepository = (viewModelContext.activity as HasScreenInjector).injector().uiStateRepository()
            // TODO
            return HomeDetailViewState(
                    displayMode = HomeDisplayMode.CHATS // uiStateRepository.getDisplayMode()
            )
        }

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: HomeDetailViewState): HomeDetailViewModel? {
            val fragment: HomeDetailFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.homeDetailViewModelFactory.create(state)
        }
    }

    init {
        observeSyncState()
        observeSelectedGroupStore()
        observeRoomSummaries()
    }

    override fun handle(action: HomeDetailAction) {
        when (action) {
            is HomeDetailAction.SwitchDisplayMode -> handleSwitchDisplayMode(action)
        }
    }

    private fun handleSwitchDisplayMode(action: HomeDetailAction.SwitchDisplayMode) = withState { state ->
        if (state.displayMode != action.displayMode) {
            setState {
                copy(displayMode = action.displayMode)
            }

            // uiStateRepository.storeDisplayMode(action.displayMode)
        }
    }

    // PRIVATE METHODS *****************************************************************************

    private fun observeSyncState() {
        session.rx()
                .liveSyncState()
                .subscribe { syncState ->
                    setState {
                        copy(syncState = syncState)
                    }
                }
                .disposeOnClear()
    }

    private fun observeSelectedGroupStore() {
        selectedGroupStore
                .observe()
                .subscribe {
                    setState {
                        copy(groupSummary = it)
                    }
                }
                .disposeOnClear()
    }

    private fun observeRoomSummaries() {
        homeRoomListStore
                .observe()
                .observeOn(Schedulers.computation())
                .map { it.asSequence() }
                .subscribe { summaries ->
                    val invitesDm = summaries
                            .filter { it.membership == Membership.INVITE && it.isDirect }
                            .count()

                    val invitesRoom = summaries
                            .filter { it.membership == Membership.INVITE && it.isDirect.not() }
                            .count()

                    val peopleNotifications = summaries
                            .filter { it.isDirect }
                            .map { it.notificationCount }
                            .sum()
                    val peopleHasHighlight = summaries
                            .filter { it.isDirect }
                            .any { it.highlightCount > 0 }

                    val roomsNotifications = summaries
                            .filter { !it.isDirect }
                            .map { it.notificationCount }
                            .sum()
                    val roomsHasHighlight = summaries
                            .filter { !it.isDirect }
                            .any { it.highlightCount > 0 }

                    setState {
                        copy(
                                notificationCountCatchup = peopleNotifications + roomsNotifications + invitesDm + invitesRoom,
                                notificationHighlightCatchup = peopleHasHighlight || roomsHasHighlight,
                                notificationCountPeople = peopleNotifications + invitesDm,
                                notificationHighlightPeople = peopleHasHighlight || invitesDm > 0,
                                notificationCountRooms = roomsNotifications + invitesRoom,
                                notificationHighlightRooms = roomsHasHighlight || invitesRoom > 0
                        )
                    }
                }
                .disposeOnClear()
    }
}

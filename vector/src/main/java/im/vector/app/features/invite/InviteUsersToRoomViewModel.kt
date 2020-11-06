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

package im.vector.app.features.invite

import com.airbnb.mvrx.ActivityViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.features.userdirectory.PendingInvitee
import io.reactivex.Observable
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.rx.rx

class InviteUsersToRoomViewModel @AssistedInject constructor(@Assisted
                                                             initialState: InviteUsersToRoomViewState,
                                                             session: Session,
                                                             val stringProvider: StringProvider,
                                                             private val analyticsEngine: AnalyticsEngine)
    : VectorViewModel<InviteUsersToRoomViewState, InviteUsersToRoomAction, InviteUsersToRoomViewEvents>(initialState) {

    private val room = session.getRoom(initialState.roomId)!!

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: InviteUsersToRoomViewState): InviteUsersToRoomViewModel
    }

    companion object : MvRxViewModelFactory<InviteUsersToRoomViewModel, InviteUsersToRoomViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: InviteUsersToRoomViewState): InviteUsersToRoomViewModel? {
            val activity: InviteUsersToRoomActivity = (viewModelContext as ActivityViewModelContext).activity()
            return activity.inviteUsersToRoomViewModelFactory.create(state)
        }
    }

    override fun handle(action: InviteUsersToRoomAction) {
        when (action) {
            is InviteUsersToRoomAction.InviteSelectedUsers -> inviteUsersToRoom(action.invitees)
            InviteUsersToRoomAction.AnalyticsReportStartEvent -> handleReportInviteStart()
        }
    }

    private fun handleReportInviteStart() {
        val joinRules = room.getStateEvent(EventType.STATE_ROOM_JOIN_RULES, QueryStringValue.IsNotEmpty)
                ?.content.toModel<RoomJoinRulesContent>()
                ?.joinRules

        room.roomSummary()?.let { sum ->
            analyticsEngine.report(event = AnalyticsEngine.AnalyticEvent.BeginInvite(
                    roomId = room.roomId,
                    isEncrypted = sum.isEncrypted,
                    memberCount = sum.joinedMembersCount ?: 0,
                    isPublic = joinRules == RoomJoinRules.PUBLIC
            ))
        }
    }

    private fun handleReportSendInvites(invitees: Set<PendingInvitee>) {
        val joinRules = room.getStateEvent(EventType.STATE_ROOM_JOIN_RULES, QueryStringValue.IsNotEmpty)
                ?.content.toModel<RoomJoinRulesContent>()
                ?.joinRules

        room.roomSummary()?.let { sum ->
            analyticsEngine.report(event = AnalyticsEngine.AnalyticEvent.SendInvite(
                    roomId = room.roomId,
                    inviteeCount = invitees.size,
                    isEncrypted = sum.isEncrypted,
                    memberCount = sum.joinedMembersCount ?: 0,
                    isPublic = joinRules == RoomJoinRules.PUBLIC
            ))
        }
    }

    private fun inviteUsersToRoom(invitees: Set<PendingInvitee>) {
        _viewEvents.post(InviteUsersToRoomViewEvents.Loading)

        Observable.fromIterable(invitees).flatMapCompletable { user ->
            when (user) {
                is PendingInvitee.UserPendingInvitee     -> room.rx().invite(user.user.userId, null)
                is PendingInvitee.ThreePidPendingInvitee -> room.rx().invite3pid(user.threePid)
            }
        }.subscribe(
                {
                    val successMessage = when (invitees.size) {
                        1    -> stringProvider.getString(R.string.invitation_sent_to_one_user,
                                invitees.first().getBestName())
                        2    -> stringProvider.getString(R.string.invitations_sent_to_two_users,
                                invitees.first().getBestName(),
                                invitees.last().getBestName())
                        else -> stringProvider.getQuantityString(R.plurals.invitations_sent_to_one_and_more_users,
                                invitees.size - 1,
                                invitees.first().getBestName(),
                                invitees.size - 1)
                    }
                    _viewEvents.post(InviteUsersToRoomViewEvents.Success(successMessage))
                    handleReportSendInvites(invitees)
                },
                {
                    _viewEvents.post(InviteUsersToRoomViewEvents.Failure(it))
                })
                .disposeOnClear()
    }

    fun getUserIdsOfRoomMembers(): Set<String> {
        return room.roomSummary()?.otherMemberIds?.toSet() ?: emptySet()
    }
}

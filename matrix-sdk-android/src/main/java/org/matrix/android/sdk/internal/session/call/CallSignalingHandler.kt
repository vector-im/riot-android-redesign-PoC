/*
 * Copyright (c) 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.call

import org.matrix.android.sdk.api.session.call.CallListener
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.call.CallAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallCandidatesContent
import org.matrix.android.sdk.api.session.room.model.call.CallCapabilities
import org.matrix.android.sdk.api.session.room.model.call.CallHangupContent
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.session.room.model.call.CallNegotiateContent
import org.matrix.android.sdk.api.session.room.model.call.CallRejectContent
import org.matrix.android.sdk.api.session.room.model.call.CallSelectAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallSignalingContent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

@SessionScope
internal class CallSignalingHandler @Inject constructor(private val activeCallHandler: ActiveCallHandler,
                                                        private val mxCallFactory: MxCallFactory,
                                                        @UserId private val userId: String) {

    private val callListeners = mutableSetOf<CallListener>()
    private val callListenersDispatcher = CallListenersDispatcher(callListeners)

    fun addCallListener(listener: CallListener) {
        callListeners.add(listener)
    }

    fun removeCallListener(listener: CallListener) {
        callListeners.remove(listener)
    }

    fun onCallEvent(event: Event) {
        when (event.getClearType()) {
            EventType.CALL_ANSWER        -> {
                handleCallAnswerEvent(event)
            }
            EventType.CALL_INVITE        -> {
                handleCallInviteEvent(event)
            }
            EventType.CALL_HANGUP        -> {
                handleCallHangupEvent(event)
            }
            EventType.CALL_REJECT        -> {
                handleCallRejectEvent(event)
            }
            EventType.CALL_CANDIDATES    -> {
                handleCallCandidatesEvent(event)
            }
            EventType.CALL_SELECT_ANSWER -> {
                handleCallSelectAnswerEvent(event)
            }
            EventType.CALL_NEGOTIATE     -> {
                handleCallNegotiateEvent(event)
            }
        }
    }

    private fun handleCallNegotiateEvent(event: Event) {
        val content = event.getClearContent().toModel<CallNegotiateContent>() ?: return
        val call = content.getCall() ?: return
        if (call.ourPartyId == content.partyId) {
            // Ignore remote echo
            return
        }
        callListenersDispatcher.onCallNegotiateReceived(content)
    }

    private fun handleCallSelectAnswerEvent(event: Event) {
        val content = event.getClearContent().toModel<CallSelectAnswerContent>() ?: return
        val call = content.getCall() ?: return
        if (call.ourPartyId == content.partyId) {
            // Ignore remote echo
            return
        }
        if (call.isOutgoing) {
            Timber.v("Got selectAnswer for an outbound call: ignoring")
            return
        }
        val selectedPartyId = content.selectedPartyId
        if (selectedPartyId == null) {
            Timber.w("Got nonsensical select_answer with null selected_party_id: ignoring")
            return
        }
        callListenersDispatcher.onCallSelectAnswerReceived(content)
    }

    private fun handleCallCandidatesEvent(event: Event) {
        val content = event.getClearContent().toModel<CallCandidatesContent>() ?: return
        val call = content.getCall() ?: return
        if (call.ourPartyId == content.partyId) {
            // Ignore remote echo
            return
        }
        if (call.opponentPartyId != null && !call.partyIdsMatches(content)) {
            Timber.v("Ignoring candidates from party ID ${content.partyId} we have chosen party ID ${call.opponentPartyId}")
            return
        }
        callListenersDispatcher.onCallIceCandidateReceived(call, content)
    }

    private fun handleCallRejectEvent(event: Event) {
        val content = event.getClearContent().toModel<CallRejectContent>() ?: return
        val call = content.getCall() ?: return
        if (call.ourPartyId == content.partyId) {
            // Ignore remote echo
            return
        }
        activeCallHandler.removeCall(content.callId)
        if (event.senderId == userId) {
            // discard current call, it's rejected by another of my session
            callListenersDispatcher.onCallManagedByOtherSession(content.callId)
            return
        }
        // No need to check party_id for reject because if we'd received either
        // an answer or reject, we wouldn't be in state InviteSent
        if (call.state != CallState.Dialing) {
            return
        }
        callListenersDispatcher.onCallRejectReceived(content)
    }

    private fun handleCallHangupEvent(event: Event) {
        val content = event.getClearContent().toModel<CallHangupContent>() ?: return
        val call = content.getCall() ?: return
        // party ID must match (our chosen partner hanging up the call) or be undefined (we haven't chosen
        // a partner yet but we're treating the hangup as a reject as per VoIP v0)
        if (call.opponentPartyId != null && !call.partyIdsMatches(content)) {
            Timber.v("Ignoring hangup from party ID ${content.partyId} we have chosen party ID ${call.opponentPartyId}")
            return
        }
        if (call.state != CallState.Terminated) {
            activeCallHandler.removeCall(content.callId)
            callListenersDispatcher.onCallHangupReceived(content)
        }
    }

    private fun handleCallInviteEvent(event: Event) {
        if (event.senderId == userId) {
            // ignore invites you send
            return
        }
        if (event.roomId == null || event.senderId == null) {
            return
        }
        val content = event.getClearContent().toModel<CallInviteContent>() ?: return

        content.callId ?: return
        if (activeCallHandler.getCallWithId(content.callId) != null) {
            // Call is already known, maybe due to fast lane. Ignore
            Timber.d("Ignoring already known call invite")
            return
        }

        val incomingCall = mxCallFactory.createIncomingCall(
                roomId = event.roomId,
                opponentUserId = event.senderId,
                content = content
        ) ?: return
        activeCallHandler.addCall(incomingCall)
        callListenersDispatcher.onCallInviteReceived(incomingCall, content)
    }

    private fun handleCallAnswerEvent(event: Event) {
        val content = event.getClearContent().toModel<CallAnswerContent>() ?: return
        val call = content.getCall() ?: return
        if (call.ourPartyId == content.partyId) {
            // Ignore remote echo
            return
        }
        if (event.senderId == userId) {
            // discard current call, it's answered by another of my session
            activeCallHandler.removeCall(call.callId)
            callListenersDispatcher.onCallManagedByOtherSession(content.callId)
        } else {
            if (call.opponentPartyId != null) {
                Timber.v("Ignoring answer from party ID ${content.partyId} we already have an answer from ${call.opponentPartyId}")
                return
            }
            call.apply {
                opponentPartyId = Optional.from(content.partyId)
                opponentVersion = content.version?.let { BigDecimal(it).intValueExact() } ?: MxCall.VOIP_PROTO_VERSION
                capabilities = content.capabilities ?: CallCapabilities()
            }
            callListenersDispatcher.onCallAnswerReceived(content)
        }
    }

    private fun MxCall.partyIdsMatches(contentSignalingContent: CallSignalingContent): Boolean {
        return opponentPartyId?.getOrNull() == contentSignalingContent.partyId
    }

    private fun CallSignalingContent.getCall(): MxCall? {
        val currentCall = callId?.let {
            activeCallHandler.getCallWithId(it)
        }
        if (currentCall == null) {
            Timber.v("Call with id $callId is null")
        }
        return currentCall
    }
}

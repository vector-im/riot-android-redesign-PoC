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

package im.vector.app.features.widgets

import android.text.TextUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.widgets.WidgetPostAPIMediator
import org.matrix.android.sdk.api.util.JsonDict
import timber.log.Timber
import java.util.ArrayList
import java.util.HashMap

class WidgetPostAPIHandler @AssistedInject constructor(@Assisted private val roomId: String,
                                                       private val stringProvider: StringProvider,
                                                       private val session: Session) : WidgetPostAPIMediator.Handler {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): WidgetPostAPIHandler
    }

    interface NavigationCallback {
        fun close()
        fun closeWithResult(content: Content)
        fun openIntegrationManager(integId: String?, integType: String?)
    }

    private val room = session.getRoom(roomId)!!
    var navigationCallback: NavigationCallback? = null

    override fun handleWidgetRequest(mediator: WidgetPostAPIMediator, eventData: JsonDict): Boolean {
        return when (eventData["action"] as String?) {
            "integration_manager_open" -> handleIntegrationManagerOpenAction(eventData).run { true }
            "bot_options"              -> getBotOptions(mediator, eventData).run { true }
            "can_send_event"           -> canSendEvent(mediator, eventData).run { true }
            "close_scalar"             -> handleCloseScalar().run { true }
            "get_membership_count"     -> getMembershipCount(mediator, eventData).run { true }
            "get_widgets"              -> getWidgets(mediator, eventData).run { true }
            "invite"                   -> inviteUser(mediator, eventData).run { true }
            "join_rules_state"         -> getJoinRules(mediator, eventData).run { true }
            "membership_state"         -> getMembershipState(mediator, eventData).run { true }
            "set_bot_options"          -> setBotOptions(mediator, eventData).run { true }
            "set_bot_power"            -> setBotPower(mediator, eventData).run { true }
            "set_plumbing_state"       -> setPlumbingState(mediator, eventData).run { true }
            "set_widget"               -> setWidget(mediator, eventData).run { true }
            "m.sticker"                -> pickStickerData(mediator, eventData).run { true }
            else                       -> false
        }
    }

    private fun handleCloseScalar() {
        navigationCallback?.close()
    }

    private fun handleIntegrationManagerOpenAction(eventData: JsonDict) {
        var integType: String? = null
        var integId: String? = null
        val data = eventData["data"]
        data
                .takeIf { it is Map<*, *> }
                ?.let {
                    val dict = data as Map<*, *>

                    dict["integType"]
                            .takeIf { it is String }
                            ?.let { integType = it as String }

                    dict["integId"]
                            .takeIf { it is String }
                            ?.let { integId = it as String }

                    // Add "type_" as a prefix
                    integType?.let { integType = "type_$integType" }
                }
        navigationCallback?.openIntegrationManager(integId, integType)
    }

    /**
     * Retrieve the latest botOptions event
     *
     * @param eventData the modular data
     */
    private fun getBotOptions(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData) || checkUserId(widgetPostAPIMediator, eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        Timber.d("Received request to get options for bot $userId in room $roomId requested")
        val stateEvents = room.getStateEvents(setOf(EventType.BOT_OPTIONS))
        var botOptionsEvent: Event? = null
        val stateKey = "_$userId"
        for (stateEvent in stateEvents) {
            if (TextUtils.equals(stateEvent.stateKey, stateKey)) {
                if (null == botOptionsEvent || stateEvent.ageLocalTs ?: 0 > botOptionsEvent.ageLocalTs ?: 0) {
                    botOptionsEvent = stateEvent
                }
            }
        }
        if (null != botOptionsEvent) {
            Timber.d("Received request to get options for bot $userId returns $botOptionsEvent")
            widgetPostAPIMediator.sendObjectResponse(Event::class.java, botOptionsEvent, eventData)
        } else {
            Timber.d("Received request to get options for bot $userId returns null")
            widgetPostAPIMediator.sendObjectResponse(Event::class.java, null, eventData)
        }
    }

    private fun canSendEvent(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData)) {
            return
        }
        Timber.d("Received request canSendEvent in room $roomId")
        if (room.roomSummary()?.membership != Membership.JOIN) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_must_be_in_room), eventData)
            return
        }

        val eventType = eventData["event_type"] as String
        val isState = eventData["is_state"] as Boolean

        Timber.d("## canSendEvent() : eventType $eventType isState $isState")

        val powerLevelsEvent = room.getStateEvent(EventType.STATE_ROOM_POWER_LEVELS)
        val powerLevelsContent = powerLevelsEvent?.content?.toModel<PowerLevelsContent>()
        val canSend = if (powerLevelsContent == null) {
            false
        } else {
            PowerLevelsHelper(powerLevelsContent).isUserAllowedToSend(session.myUserId, isState, eventType)
        }
        if (canSend) {
            Timber.d("## canSendEvent() returns true")
            widgetPostAPIMediator.sendBoolResponse(true, eventData)
        } else {
            Timber.d("## canSendEvent() returns widget_integration_no_permission_in_room")
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_no_permission_in_room), eventData)
        }
    }

    /**
     * Provides the membership state
     *
     * @param eventData the modular data
     */
    private fun getMembershipState(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData) || checkUserId(widgetPostAPIMediator, eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        Timber.d("membership_state of $userId in room $roomId requested")
        val roomMemberStateEvent = room.getStateEvent(EventType.STATE_ROOM_MEMBER, stateKey = QueryStringValue.Equals(userId, QueryStringValue.Case.SENSITIVE))
        if (roomMemberStateEvent != null) {
            widgetPostAPIMediator.sendObjectResponse(Map::class.java, roomMemberStateEvent.content, eventData)
        } else {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_failed_to_send_request), eventData)
        }
    }

    /**
     * Request the latest joined room event
     *
     * @param eventData the modular data
     */
    private fun getJoinRules(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData)) {
            return
        }
        Timber.d("Received request join rules  in room $roomId")
        val joinedEvents = room.getStateEvents(setOf(EventType.STATE_ROOM_JOIN_RULES))
        if (joinedEvents.isNotEmpty()) {
            widgetPostAPIMediator.sendObjectResponse(Event::class.java, joinedEvents.last(), eventData)
        } else {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_failed_to_send_request), eventData)
        }
    }

    /**
     * Provide the widgets list
     *
     * @param eventData the modular data
     */
    private fun getWidgets(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData)) {
            return
        }
        Timber.d("Received request to get widget in room $roomId")
        val responseData = ArrayList<JsonDict>()
        val allWidgets = session.widgetService().getRoomWidgets(roomId) + session.widgetService().getUserWidgets()
        for (widget in allWidgets) {
            val map = widget.event.toContent()
            responseData.add(map)
        }
        Timber.d("## getWidgets() returns $responseData")
        widgetPostAPIMediator.sendObjectResponse(List::class.java, responseData, eventData)
    }

    /**
     * Set a new widget
     *
     * @param eventData the modular data
     */
    private fun setWidget(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        val userWidget = eventData["userWidget"] as Boolean?
        if (userWidget == true) {
            Timber.d("Received request to set widget for user")
        } else {
            if (checkRoomId(widgetPostAPIMediator, eventData)) {
                return
            }
            Timber.d("Received request to set widget in room $roomId")
        }
        val widgetId = eventData["widget_id"] as String?
        val widgetType = eventData["type"] as String?
        val widgetUrl = eventData["url"] as String?

        // optional
        val widgetName = eventData["name"] as String?
        // optional
        val widgetData = eventData["data"]
        if (widgetId == null) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_unable_to_create), eventData)
            return
        }

        val widgetEventContent = HashMap<String, Any>()

        if (null != widgetUrl) {
            if (null == widgetType) {
                widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_unable_to_create), eventData)
                return
            }

            widgetEventContent["type"] = widgetType
            widgetEventContent["url"] = widgetUrl

            if (null != widgetName) {
                widgetEventContent["name"] = widgetName
            }

            if (null != widgetData) {
                widgetEventContent["data"] = widgetData
            }
        }

        if (userWidget == true) {
            val addUserWidgetBody = mapOf(
                    widgetId to mapOf(
                            "content" to widgetEventContent,
                            "state_key" to widgetId,
                            "id" to widgetId,
                            "sender" to session.myUserId,
                            "type" to "m.widget"
                    )
            )
            launchWidgetAPIAction(widgetPostAPIMediator, eventData) {
                session.userAccountDataService().updateAccountData(
                        type = UserAccountDataTypes.TYPE_WIDGETS,
                        content = addUserWidgetBody
                )
            }
        } else {
            launchWidgetAPIAction(widgetPostAPIMediator, eventData) {
                session.widgetService().createRoomWidget(
                        roomId = roomId,
                        widgetId = widgetId,
                        content = widgetEventContent
                )
            }
        }
    }

    /**
     * Update the 'plumbing state"
     *
     * @param eventData the modular data
     */
    private fun setPlumbingState(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData)) {
            return
        }
        val description = "Received request to set plumbing state to status " + eventData["status"] + " in room " + roomId + " requested"
        Timber.d(description)

        val status = eventData["status"] as String

        val params = HashMap<String, Any>()
        params["status"] = status
        launchWidgetAPIAction(widgetPostAPIMediator, eventData) {
            room.sendStateEvent(
                    eventType = EventType.PLUMBING,
                    stateKey = null,
                    body = params
            )
        }
    }

    /**
     * Update the bot options
     *
     * @param eventData the modular data
     */
    @Suppress("UNCHECKED_CAST")
    private fun setBotOptions(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData) || checkUserId(widgetPostAPIMediator, eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        val description = "Received request to set options for bot $userId in room $roomId"
        Timber.d(description)
        val content = eventData["content"] as JsonDict
        val stateKey = "_$userId"

        launchWidgetAPIAction(widgetPostAPIMediator, eventData) {
            room.sendStateEvent(
                    eventType = EventType.BOT_OPTIONS,
                    stateKey = stateKey,
                    body = content
            )
        }
    }

    /**
     * Update the bot power levels
     *
     * @param eventData the modular data
     */
    private fun setBotPower(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData) || checkUserId(widgetPostAPIMediator, eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        val description = "Received request to set power level to " + eventData["level"] + " for bot " + userId + " in room " + roomId
        Timber.d(description)
        val level = eventData["level"] as Int
        if (level >= 0) {
            // TODO
            // room.updateUserPowerLevels(userId, level, WidgetApiCallback(eventData, description))
        } else {
            Timber.e("## setBotPower() : Power level must be positive integer.")
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_positive_power_level), eventData)
        }
    }

    /**
     * Invite an user to this room
     *
     * @param eventData the modular data
     */
    private fun inviteUser(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData) || checkUserId(widgetPostAPIMediator, eventData)) {
            return
        }
        val userId = eventData["user_id"] as String
        val description = "Received request to invite $userId into room $roomId"
        Timber.d(description)
        val member = room.getRoomMember(userId)
        if (member != null && member.membership == Membership.JOIN) {
            widgetPostAPIMediator.sendSuccess(eventData)
        } else {
            launchWidgetAPIAction(widgetPostAPIMediator, eventData) {
                room.invite(userId = userId)
            }
        }
    }

    /**
     * Provides the number of members in the rooms
     *
     * @param eventData the modular data
     */
    private fun getMembershipCount(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        if (checkRoomId(widgetPostAPIMediator, eventData)) {
            return
        }
        val numberOfJoinedMembers = room.getNumberOfJoinedMembers()
        widgetPostAPIMediator.sendIntegerResponse(numberOfJoinedMembers, eventData)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pickStickerData(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict) {
        Timber.d("Received request send sticker")
        val data = eventData["data"]
        if (data == null) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_missing_parameter), eventData)
            return
        }
        val content = (data as? JsonDict)?.get("content") as? Content
        if (content == null) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_missing_parameter), eventData)
            return
        }
        widgetPostAPIMediator.sendSuccess(eventData)
        navigationCallback?.closeWithResult(content)
    }

    /**
     * Check if roomId is present in the event and match
     * Send response and return true in case of error
     *
     * @return true in case of error
     */
    private fun checkRoomId(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict): Boolean {
        val roomIdInEvent = eventData["room_id"] as String?
        // Check if param is present
        if (null == roomIdInEvent) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_missing_room_id), eventData)
            return true
        }

        if (!TextUtils.equals(roomIdInEvent, roomId)) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_room_not_visible), eventData)
            return true
        }

        // OK
        return false
    }

    /**
     * Check if userId is present in the event
     * Send response and return true in case of error
     *
     * @return true in case of error
     */
    private fun checkUserId(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict): Boolean {
        val userIdInEvent = eventData["user_id"] as String?
        // Check if param is present
        if (null == userIdInEvent) {
            widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_missing_user_id), eventData)
            return true
        }
        // OK
        return false
    }

    private fun launchWidgetAPIAction(widgetPostAPIMediator: WidgetPostAPIMediator, eventData: JsonDict, block: suspend () -> Unit): Job {
        // We should probably use a scope tight to the lifecycle here...
        return session.coroutineScope.launch {
            kotlin.runCatching {
                block()
            }.fold(
                    onSuccess = {
                        widgetPostAPIMediator.sendSuccess(eventData)
                    },
                    onFailure = {
                        widgetPostAPIMediator.sendError(stringProvider.getString(R.string.widget_integration_failed_to_send_request), eventData)
                    }
            )
        }
    }
}

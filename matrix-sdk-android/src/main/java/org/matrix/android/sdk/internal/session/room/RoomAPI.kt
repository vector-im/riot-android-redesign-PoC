/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsParams
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoomsResponse
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.network.NetworkConstants
import org.matrix.android.sdk.internal.session.room.alias.GetAliasesResponse
import org.matrix.android.sdk.internal.session.room.create.CreateRoomBody
import org.matrix.android.sdk.internal.session.room.create.CreateRoomResponse
import org.matrix.android.sdk.internal.session.room.create.JoinRoomResponse
import org.matrix.android.sdk.internal.session.room.membership.RoomMembersResponse
import org.matrix.android.sdk.internal.session.room.membership.admin.UserIdAndReason
import org.matrix.android.sdk.internal.session.room.membership.joining.InviteBody
import org.matrix.android.sdk.internal.session.room.membership.threepid.ThreePidInviteBody
import org.matrix.android.sdk.internal.session.room.relation.RelationsResponse
import org.matrix.android.sdk.internal.session.room.reporting.ReportContentBody
import org.matrix.android.sdk.internal.session.room.send.SendResponse
import org.matrix.android.sdk.internal.session.room.tags.TagBody
import org.matrix.android.sdk.internal.session.room.timeline.EventContextResponse
import org.matrix.android.sdk.internal.session.room.timeline.PaginationResponse
import org.matrix.android.sdk.internal.session.room.typing.TypingBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

internal interface RoomAPI {

    /**
     * Lists the public rooms on the server, with optional filter.
     * This API returns paginated responses. The rooms are ordered by the number of joined members, with the largest rooms first.
     *
     * Ref: https://matrix.org/docs/spec/client_server/r0.4.0.html#post-matrix-client-r0-publicrooms
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "publicRooms")
    suspend fun publicRooms(@Query("server") server: String?,
                            @Body publicRoomsParams: PublicRoomsParams
    ): PublicRoomsResponse

    /**
     * Create a room.
     * Ref: https://matrix.org/docs/spec/client_server/r0.4.0.html#post-matrix-client-r0-createroom
     * Set all the timeouts to 1 minute, because if the server takes time to answer, we will not execute the
     * create direct chat request if any
     *
     * @param param the creation room parameter
     */
    @Headers("CONNECT_TIMEOUT:60000", "READ_TIMEOUT:60000", "WRITE_TIMEOUT:60000")
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "createRoom")
    suspend fun createRoom(@Body param: CreateRoomBody): CreateRoomResponse

    /**
     * Get a list of messages starting from a reference.
     *
     * @param roomId the room id
     * @param from   the token identifying where to start. Required.
     * @param dir    The direction to return messages from. Required.
     * @param limit  the maximum number of messages to retrieve. Optional.
     * @param filter A JSON RoomEventFilter to filter returned events with. Optional.
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/messages")
    suspend fun getRoomMessagesFrom(@Path("roomId") roomId: String,
                                    @Query("from") from: String,
                                    @Query("dir") dir: String,
                                    @Query("limit") limit: Int,
                                    @Query("filter") filter: String?
    ): PaginationResponse

    /**
     * Get all members of a room
     *
     * @param roomId        the room id where to get the members
     * @param syncToken     the sync token (optional)
     * @param membership    to include only one type of membership (optional)
     * @param notMembership to exclude one type of membership (optional)
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/members")
    suspend fun getMembers(@Path("roomId") roomId: String,
                           @Query("at") syncToken: String?,
                           @Query("membership") membership: Membership?,
                           @Query("not_membership") notMembership: Membership?
    ): RoomMembersResponse

    /**
     * Send an event to a room.
     *
     * @param txId      the transaction Id
     * @param roomId    the room id
     * @param eventType the event type
     * @param content   the event content
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/send/{eventType}/{txId}")
    suspend fun send(@Path("txId") txId: String,
                     @Path("roomId") roomId: String,
                     @Path("eventType") eventType: String,
                     @Body content: Content?
    ): SendResponse

    /**
     * Get the context surrounding an event.
     *
     * @param roomId  the room id
     * @param eventId the event Id
     * @param limit   the maximum number of messages to retrieve
     * @param filter  A JSON RoomEventFilter to filter returned events with. Optional.
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/context/{eventId}")
    suspend fun getContextOfEvent(@Path("roomId") roomId: String,
                                  @Path("eventId") eventId: String,
                                  @Query("limit") limit: Int,
                                  @Query("filter") filter: String? = null): EventContextResponse

    /**
     * Retrieve an event from its room id / events id
     *
     * @param roomId  the room id
     * @param eventId the event Id
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/event/{eventId}")
    suspend fun getEvent(@Path("roomId") roomId: String,
                         @Path("eventId") eventId: String): Event

    /**
     * Send read markers.
     *
     * @param roomId  the room id
     * @param markers the read markers
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/read_markers")
    suspend fun sendReadMarker(@Path("roomId") roomId: String,
                               @Body markers: Map<String, String>)

    /**
     * Send receipt to a room
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/receipt/{receiptType}/{eventId}")
    suspend fun sendReceipt(@Path("roomId") roomId: String,
                            @Path("receiptType") receiptType: String,
                            @Path("eventId") eventId: String)

    /**
     * Invite a user to the given room.
     * Ref: https://matrix.org/docs/spec/client_server/r0.4.0.html#post-matrix-client-r0-rooms-roomid-invite
     *
     * @param roomId the room id
     * @param body   a object that just contains a user id
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/invite")
    suspend fun invite(@Path("roomId") roomId: String,
                       @Body body: InviteBody)

    /**
     * Invite a user to a room, using a ThreePid
     * Ref: https://matrix.org/docs/spec/client_server/r0.6.1#id101
     * @param roomId Required. The room identifier (not alias) to which to invite the user.
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/invite")
    suspend fun invite3pid(@Path("roomId") roomId: String,
                           @Body body: ThreePidInviteBody)

    /**
     * Send a generic state event
     *
     * @param roomId         the room id.
     * @param stateEventType the state event type
     * @param params         the request parameters
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/state/{state_event_type}")
    suspend fun sendStateEvent(@Path("roomId") roomId: String,
                               @Path("state_event_type") stateEventType: String,
                               @Body params: JsonDict)

    /**
     * Send a generic state event
     *
     * @param roomId         the room id.
     * @param stateEventType the state event type
     * @param stateKey       the state keys
     * @param params         the request parameters
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/state/{state_event_type}/{state_key}")
    suspend fun sendStateEvent(@Path("roomId") roomId: String,
                               @Path("state_event_type") stateEventType: String,
                               @Path("state_key") stateKey: String,
                               @Body params: JsonDict)

    /**
     * Get state events of a room
     * Ref: https://matrix.org/docs/spec/client_server/r0.6.1#get-matrix-client-r0-rooms-roomid-state
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/state")
    suspend fun getRoomState(@Path("roomId") roomId: String): List<Event>

    /**
     * Send a relation event to a room.
     *
     * @param txId      the transaction Id
     * @param roomId    the room id
     * @param eventType the event type
     * @param content   the event content
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/send_relation/{parent_id}/{relation_type}/{event_type}")
    suspend fun sendRelation(@Path("roomId") roomId: String,
                             @Path("parent_id") parentId: String,
                             @Path("relation_type") relationType: String,
                             @Path("event_type") eventType: String,
                             @Body content: Content?
    ): SendResponse

    /**
     * Paginate relations for event based in normal topological order
     *
     * @param relationType filter for this relation type
     * @param eventType filter for this event type
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "rooms/{roomId}/relations/{eventId}/{relationType}/{eventType}")
    suspend fun getRelations(@Path("roomId") roomId: String,
                             @Path("eventId") eventId: String,
                             @Path("relationType") relationType: String,
                             @Path("eventType") eventType: String
    ): RelationsResponse

    /**
     * Join the given room.
     *
     * @param roomIdOrAlias the room id or alias
     * @param viaServers the servers to attempt to join the room through
     * @param params the request body
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "join/{roomIdOrAlias}")
    suspend fun join(@Path("roomIdOrAlias") roomIdOrAlias: String,
                     @Query("server_name") viaServers: List<String>,
                     @Body params: Map<String, String?>): JoinRoomResponse

    /**
     * Leave the given room.
     *
     * @param roomId  the room id
     * @param params the request body
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/leave")
    suspend fun leave(@Path("roomId") roomId: String,
                      @Body params: Map<String, String?>)

    /**
     * Ban a user from the given room.
     *
     * @param roomId          the room id
     * @param userIdAndReason the banned user object (userId and reason for ban)
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/ban")
    suspend fun ban(@Path("roomId") roomId: String,
                    @Body userIdAndReason: UserIdAndReason)

    /**
     * unban a user from the given room.
     *
     * @param roomId          the room id
     * @param userIdAndReason the unbanned user object (userId and reason for unban)
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/unban")
    suspend fun unban(@Path("roomId") roomId: String,
                      @Body userIdAndReason: UserIdAndReason)

    /**
     * Kick a user from the given room.
     *
     * @param roomId          the room id
     * @param userIdAndReason the kicked user object (userId and reason for kicking)
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/kick")
    suspend fun kick(@Path("roomId") roomId: String,
                     @Body userIdAndReason: UserIdAndReason)

    /**
     * Strips all information out of an event which isn't critical to the integrity of the server-side representation of the room.
     * This cannot be undone.
     * Users may redact their own events, and any user with a power level greater than or equal to the redact power level of the room may redact events there.
     *
     * @param txId     the transaction Id
     * @param roomId   the room id
     * @param eventId  the event to delete
     * @param reason   json containing reason key {"reason": "Indecent material"}
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/redact/{eventId}/{txnId}")
    suspend fun redactEvent(
            @Path("txnId") txId: String,
            @Path("roomId") roomId: String,
            @Path("eventId") eventId: String,
            @Body reason: Map<String, String>
    ): SendResponse

    /**
     * Reports an event as inappropriate to the server, which may then notify the appropriate people.
     *
     * @param roomId  the room id
     * @param eventId the event to report content
     * @param body    body containing score and reason
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/report/{eventId}")
    suspend fun reportContent(@Path("roomId") roomId: String,
                              @Path("eventId") eventId: String,
                              @Body body: ReportContentBody)

    /**
     * Get a list of aliases maintained by the local server for the given room.
     * Ref: https://matrix.org/docs/spec/client_server/r0.6.1#get-matrix-client-r0-rooms-roomid-aliases
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc2432/rooms/{roomId}/aliases")
    suspend fun getAliases(@Path("roomId") roomId: String): GetAliasesResponse

    /**
     * Inform that the user is starting to type or has stopped typing
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "rooms/{roomId}/typing/{userId}")
    suspend fun sendTypingState(@Path("roomId") roomId: String,
                                @Path("userId") userId: String,
                                @Body body: TypingBody)

    /**
     * Room tagging
     */

    /**
     * Add a tag to a room.
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "user/{userId}/rooms/{roomId}/tags/{tag}")
    suspend fun putTag(@Path("userId") userId: String,
                       @Path("roomId") roomId: String,
                       @Path("tag") tag: String,
                       @Body body: TagBody)

    /**
     * Delete a tag from a room.
     */
    @DELETE(NetworkConstants.URI_API_PREFIX_PATH_R0 + "user/{userId}/rooms/{roomId}/tags/{tag}")
    suspend fun deleteTag(@Path("userId") userId: String,
                          @Path("roomId") roomId: String,
                          @Path("tag") tag: String)

    /**
     * Set an AccountData event to the room.
     */
    @PUT(NetworkConstants.URI_API_PREFIX_PATH_R0 + "user/{userId}/rooms/{roomId}/account_data/{type}")
    suspend fun setRoomAccountData(@Path("userId") userId: String,
                                   @Path("roomId") roomId: String,
                                   @Path("type") type: String,
                                   @Body content: JsonDict)
}

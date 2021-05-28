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

package org.matrix.android.sdk.api.session.events.model

/**
 * Constants defining known event types from Matrix specifications.
 */
object EventType {
    // Used when the type is missing, which should not happen
    const val MISSING_TYPE = "org.matrix.android.sdk.missing_type"

    const val PRESENCE = "m.presence"
    const val MESSAGE = "m.room.message"
    const val STICKER = "m.sticker"
    const val ENCRYPTED = "m.room.encrypted"
    const val FEEDBACK = "m.room.message.feedback"
    const val TYPING = "m.typing"
    const val REDACTION = "m.room.redaction"
    const val RECEIPT = "m.receipt"
    const val ROOM_KEY = "m.room_key"
    const val PLUMBING = "m.room.plumbing"
    const val BOT_OPTIONS = "m.room.bot.options"
    const val PREVIEW_URLS = "org.matrix.room.preview_urls"

    // State Events

    const val STATE_ROOM_WIDGET_LEGACY = "im.vector.modular.widgets"
    const val STATE_ROOM_WIDGET = "m.widget"
    const val STATE_ROOM_NAME = "m.room.name"
    const val STATE_ROOM_TOPIC = "m.room.topic"
    const val STATE_ROOM_AVATAR = "m.room.avatar"
    const val STATE_ROOM_MEMBER = "m.room.member"
    const val STATE_ROOM_THIRD_PARTY_INVITE = "m.room.third_party_invite"
    const val STATE_ROOM_CREATE = "m.room.create"
    const val STATE_ROOM_JOIN_RULES = "m.room.join_rules"
    const val STATE_ROOM_GUEST_ACCESS = "m.room.guest_access"
    const val STATE_ROOM_POWER_LEVELS = "m.room.power_levels"

    const val STATE_SPACE_CHILD = "m.space.child"

    const val STATE_SPACE_PARENT = "m.space.parent"

    /**
     * Note that this Event has been deprecated, see
     * - https://matrix.org/docs/spec/client_server/r0.6.1#historical-events
     * - https://github.com/matrix-org/matrix-doc/pull/2432
     */
    const val STATE_ROOM_ALIASES = "m.room.aliases"
    const val STATE_ROOM_TOMBSTONE = "m.room.tombstone"
    const val STATE_ROOM_CANONICAL_ALIAS = "m.room.canonical_alias"
    const val STATE_ROOM_HISTORY_VISIBILITY = "m.room.history_visibility"
    const val STATE_ROOM_RELATED_GROUPS = "m.room.related_groups"
    const val STATE_ROOM_PINNED_EVENT = "m.room.pinned_events"
    const val STATE_ROOM_ENCRYPTION = "m.room.encryption"
    const val STATE_ROOM_SERVER_ACL = "m.room.server_acl"

    // Call Events
    const val CALL_INVITE = "m.call.invite"
    const val CALL_CANDIDATES = "m.call.candidates"
    const val CALL_ANSWER = "m.call.answer"
    const val CALL_SELECT_ANSWER = "m.call.select_answer"
    const val CALL_NEGOTIATE = "m.call.negotiate"
    const val CALL_REJECT = "m.call.reject"
    const val CALL_HANGUP = "m.call.hangup"

    // This type is not processed by the client, just sent to the server
    const val CALL_REPLACES = "m.call.replaces"

    // Key share events
    const val ROOM_KEY_REQUEST = "m.room_key_request"
    const val FORWARDED_ROOM_KEY = "m.forwarded_room_key"
    const val ROOM_KEY_WITHHELD = "org.matrix.room_key.withheld"

    const val REQUEST_SECRET = "m.secret.request"
    const val SEND_SECRET = "m.secret.send"

    // Interactive key verification
    const val KEY_VERIFICATION_START = "m.key.verification.start"
    const val KEY_VERIFICATION_ACCEPT = "m.key.verification.accept"
    const val KEY_VERIFICATION_KEY = "m.key.verification.key"
    const val KEY_VERIFICATION_MAC = "m.key.verification.mac"
    const val KEY_VERIFICATION_CANCEL = "m.key.verification.cancel"
    const val KEY_VERIFICATION_DONE = "m.key.verification.done"
    const val KEY_VERIFICATION_READY = "m.key.verification.ready"

    // Relation Events
    const val REACTION = "m.reaction"

    // Unwedging
    internal const val DUMMY = "m.dummy"

    fun isCallEvent(type: String): Boolean {
        return type == CALL_INVITE
                || type == CALL_CANDIDATES
                || type == CALL_ANSWER
                || type == CALL_HANGUP
                || type == CALL_SELECT_ANSWER
                || type == CALL_NEGOTIATE
                || type == CALL_REJECT
                || type == CALL_REPLACES
    }
}

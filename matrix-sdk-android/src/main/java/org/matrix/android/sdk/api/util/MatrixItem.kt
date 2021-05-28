/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.util

import org.matrix.android.sdk.BuildConfig
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoom
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.user.model.User
import java.util.Locale

sealed class MatrixItem(
        open val id: String,
        open val displayName: String?,
        open val avatarUrl: String?
) {
    data class UserItem(override val id: String,
                        override val displayName: String? = null,
                        override val avatarUrl: String? = null)
        : MatrixItem(id, displayName?.removeSuffix(ircPattern), avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }
    }

    data class EventItem(override val id: String,
                         override val displayName: String? = null,
                         override val avatarUrl: String? = null)
        : MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }
    }

    data class RoomItem(override val id: String,
                        override val displayName: String? = null,
                        override val avatarUrl: String? = null)
        : MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }
    }

    data class RoomAliasItem(override val id: String,
                             override val displayName: String? = null,
                             override val avatarUrl: String? = null)
        : MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        // Best name is the id, and we keep the displayName of the room for the case we need the first letter
        override fun getBestName() = id
    }

    data class GroupItem(override val id: String,
                         override val displayName: String? = null,
                         override val avatarUrl: String? = null)
        : MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        // Best name is the id, and we keep the displayName of the room for the case we need the first letter
        override fun getBestName() = id
    }

    open fun getBestName(): String {
        return displayName?.takeIf { it.isNotBlank() } ?: id
    }

    protected fun checkId() {
        if (!id.startsWith(getIdPrefix())) {
            error("Wrong usage of MatrixItem: check the id $id should start with ${getIdPrefix()}")
        }
    }

    /**
     * Return the prefix as defined in the matrix spec (and not extracted from the id)
     */
    fun getIdPrefix() = when (this) {
        is UserItem      -> '@'
        is EventItem     -> '$'
        is RoomItem      -> '!'
        is RoomAliasItem -> '#'
        is GroupItem     -> '+'
    }

    fun firstLetterOfDisplayName(): String {
        return (displayName?.takeIf { it.isNotBlank() } ?: id)
                .let { dn ->
                    var startIndex = 0
                    val initial = dn[startIndex]

                    if (initial in listOf('@', '#', '+') && dn.length > 1) {
                        startIndex++
                    }

                    var length = 1
                    var first = dn[startIndex]

                    // LEFT-TO-RIGHT MARK
                    if (dn.length >= 2 && 0x200e == first.code) {
                        startIndex++
                        first = dn[startIndex]
                    }

                    // check if it’s the start of a surrogate pair
                    if (first.code in 0xD800..0xDBFF && dn.length > startIndex + 1) {
                        val second = dn[startIndex + 1]
                        if (second.code in 0xDC00..0xDFFF) {
                            length++
                        }
                    }

                    dn.substring(startIndex, startIndex + length)
                }
                .uppercase(Locale.ROOT)
    }

    companion object {
        private const val ircPattern = " (IRC)"
    }
}

/* ==========================================================================================
 * Extensions to create MatrixItem
 * ========================================================================================== */

fun User.toMatrixItem() = MatrixItem.UserItem(userId, displayName, avatarUrl)

fun GroupSummary.toMatrixItem() = MatrixItem.GroupItem(groupId, displayName, avatarUrl)

fun RoomSummary.toMatrixItem() = MatrixItem.RoomItem(roomId, displayName, avatarUrl)

fun RoomSummary.toRoomAliasMatrixItem() = MatrixItem.RoomAliasItem(canonicalAlias ?: roomId, displayName, avatarUrl)

// If no name is available, use room alias as Riot-Web does
fun PublicRoom.toMatrixItem() = MatrixItem.RoomItem(roomId, name ?: getPrimaryAlias() ?: "", avatarUrl)

fun RoomMemberSummary.toMatrixItem() = MatrixItem.UserItem(userId, displayName, avatarUrl)

fun SenderInfo.toMatrixItem() = MatrixItem.UserItem(userId, disambiguatedDisplayName, avatarUrl)

fun SpaceChildInfo.toMatrixItem() = MatrixItem.RoomItem(childRoomId, name ?: canonicalAlias ?: "", avatarUrl)

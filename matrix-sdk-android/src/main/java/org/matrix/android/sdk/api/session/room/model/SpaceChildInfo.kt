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

package org.matrix.android.sdk.api.session.room.model

data class SpaceChildInfo(
        val childRoomId: String,
        // We might not know this child at all,
        // i.e we just know it exists but no info on type/name/etc..
        val isKnown: Boolean,
        val roomType: String?,
        val name: String?,
        val topic: String?,
        val avatarUrl: String?,
        val order: String?,
        val activeMemberCount: Int?,
        val autoJoin: Boolean,
        val viaServers: List<String>,
        val parentRoomId: String?,
        val suggested: Boolean?,
        val canonicalAlias: String?,
        val aliases: List<String>?
)

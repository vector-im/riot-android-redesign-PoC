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

package org.matrix.android.sdk.internal.session.space

import io.realm.RealmConfiguration
import kotlinx.coroutines.TimeoutCancellationException
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.space.JoinSpaceResult
import org.matrix.android.sdk.internal.database.awaitNotEmptyResult
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntityFields
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.membership.joining.JoinRoomTask
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal interface JoinSpaceTask : Task<JoinSpaceTask.Params, JoinSpaceResult> {
    data class Params(
            val roomIdOrAlias: String,
            val reason: String?,
            val viaServers: List<String> = emptyList()
    )
}

internal class DefaultJoinSpaceTask @Inject constructor(
        private val joinRoomTask: JoinRoomTask,
        @SessionDatabase
        private val realmConfiguration: RealmConfiguration,
        private val roomSummaryDataSource: RoomSummaryDataSource
) : JoinSpaceTask {

    override suspend fun execute(params: JoinSpaceTask.Params): JoinSpaceResult {
        Timber.v("## Space: > Joining root space ${params.roomIdOrAlias} ...")
        try {
            joinRoomTask.execute(JoinRoomTask.Params(
                    params.roomIdOrAlias,
                    params.reason,
                    params.viaServers
            ))
        } catch (failure: Throwable) {
            return JoinSpaceResult.Fail(failure)
        }
        Timber.v("## Space: < Joining root space done for ${params.roomIdOrAlias}")
        // we want to wait for sync result to check for auto join rooms

        Timber.v("## Space: > Wait for post joined sync ${params.roomIdOrAlias} ...")
        try {
            awaitNotEmptyResult(realmConfiguration, TimeUnit.MINUTES.toMillis(2L)) { realm ->
                realm.where(RoomSummaryEntity::class.java)
                        .apply {
                            if (params.roomIdOrAlias.startsWith("!")) {
                                equalTo(RoomSummaryEntityFields.ROOM_ID, params.roomIdOrAlias)
                            } else {
                                equalTo(RoomSummaryEntityFields.CANONICAL_ALIAS, params.roomIdOrAlias)
                            }
                        }
                        .equalTo(RoomSummaryEntityFields.MEMBERSHIP_STR, Membership.JOIN.name)
            }
        } catch (exception: TimeoutCancellationException) {
            Timber.w("## Space: > Error created with timeout")
            return JoinSpaceResult.PartialSuccess(emptyMap())
        }

        val errors = mutableMapOf<String, Throwable>()
        Timber.v("## Space: > Sync done ...")
        // after that i should have the children (? do I need to paginate to get state)
        val summary = roomSummaryDataSource.getSpaceSummary(params.roomIdOrAlias)
        Timber.v("## Space: Found space summary Name:[${summary?.name}] children: ${summary?.spaceChildren?.size}")
        summary?.spaceChildren?.forEach {
//            val childRoomSummary = it.roomSummary ?: return@forEach
            Timber.v("## Space: Processing child :[${it.childRoomId}] autoJoin:${it.autoJoin}")
            if (it.autoJoin) {
                // I should try to join as well
                if (it.roomType == RoomType.SPACE) {
                    // recursively join auto-joined child of this space?
                    when (val subspaceJoinResult = execute(JoinSpaceTask.Params(it.childRoomId, null, it.viaServers))) {
                        JoinSpaceResult.Success           -> {
                            // nop
                        }
                        is JoinSpaceResult.Fail           -> {
                            errors[it.childRoomId] = subspaceJoinResult.error
                        }
                        is JoinSpaceResult.PartialSuccess -> {
                            errors.putAll(subspaceJoinResult.failedRooms)
                        }
                    }
                } else {
                    try {
                        Timber.v("## Space: Joining room child ${it.childRoomId}")
                        joinRoomTask.execute(JoinRoomTask.Params(
                                roomIdOrAlias = it.childRoomId,
                                reason = "Auto-join parent space",
                                viaServers = it.viaServers
                        ))
                    } catch (failure: Throwable) {
                        errors[it.childRoomId] = failure
                        Timber.e("## Space: Failed to join room child ${it.childRoomId}")
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            JoinSpaceResult.Success
        } else {
            JoinSpaceResult.PartialSuccess(errors)
        }
    }
}

// try {
//    awaitNotEmptyResult(realmConfiguration, TimeUnit.MINUTES.toMillis(1L)) { realm ->
//        realm.where(RoomEntity::class.java)
//                .equalTo(RoomEntityFields.ROOM_ID, roomId)
//    }
// } catch (exception: TimeoutCancellationException) {
//    throw CreateRoomFailure.CreatedWithTimeout
// }

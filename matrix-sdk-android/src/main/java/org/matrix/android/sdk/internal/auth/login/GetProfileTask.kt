/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.auth.login

import org.matrix.android.sdk.api.auth.login.LoginProfileInfo
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.internal.auth.AuthAPI
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task

internal interface GetProfileTask : Task<GetProfileTask.Params, LoginProfileInfo> {
    data class Params(
            val userId: String
    )
}

internal class DefaultGetProfileTask(
        private val authAPI: AuthAPI,
        private val contentUrlResolver: ContentUrlResolver
) : GetProfileTask {

    override suspend fun execute(params: GetProfileTask.Params): LoginProfileInfo {
        val info = executeRequest(null) {
            authAPI.getProfile(params.userId)
        }

        return LoginProfileInfo(
                matrixId = params.userId,
                displayName = info[ProfileService.DISPLAY_NAME_KEY] as? String,
                fullAvatarUrl = contentUrlResolver.resolveFullSize(info[ProfileService.AVATAR_URL_KEY] as? String)
        )
    }
}

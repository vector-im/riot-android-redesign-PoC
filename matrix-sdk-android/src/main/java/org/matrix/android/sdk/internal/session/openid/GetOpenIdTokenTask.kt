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

package org.matrix.android.sdk.internal.session.openid

import org.matrix.android.sdk.api.session.openid.OpenIdToken
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface GetOpenIdTokenTask : Task<Unit, OpenIdToken>

internal class DefaultGetOpenIdTokenTask @Inject constructor(
        @UserId private val userId: String,
        private val openIdAPI: OpenIdAPI,
        private val globalErrorReceiver: GlobalErrorReceiver) : GetOpenIdTokenTask {

    override suspend fun execute(params: Unit): OpenIdToken {
        return executeRequest(globalErrorReceiver) {
            openIdAPI.openIdToken(userId)
        }
    }
}

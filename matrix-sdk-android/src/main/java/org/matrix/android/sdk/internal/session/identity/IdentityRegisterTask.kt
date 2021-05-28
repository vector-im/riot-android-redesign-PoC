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

package org.matrix.android.sdk.internal.session.identity

import org.matrix.android.sdk.api.session.openid.OpenIdToken
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.identity.model.IdentityRegisterResponse
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface IdentityRegisterTask : Task<IdentityRegisterTask.Params, IdentityRegisterResponse> {
    data class Params(
            val identityAuthAPI: IdentityAuthAPI,
            val openIdToken: OpenIdToken
    )
}

internal class DefaultIdentityRegisterTask @Inject constructor() : IdentityRegisterTask {

    override suspend fun execute(params: IdentityRegisterTask.Params): IdentityRegisterResponse {
        return executeRequest(null) {
            params.identityAuthAPI.register(params.openIdToken)
        }
    }
}

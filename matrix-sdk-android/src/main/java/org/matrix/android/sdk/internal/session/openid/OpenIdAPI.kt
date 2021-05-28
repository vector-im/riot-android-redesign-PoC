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
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.network.NetworkConstants
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

internal interface OpenIdAPI {

    /**
     * Gets a bearer token from the homeserver that the user can
     * present to a third party in order to prove their ownership
     * of the Matrix account they are logged into.
     * Ref: https://matrix.org/docs/spec/client_server/latest#post-matrix-client-r0-user-userid-openid-request-token
     *
     * @param userId the user id
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "user/{userId}/openid/request_token")
    suspend fun openIdToken(@Path("userId") userId: String,
                            @Body body: JsonDict = emptyMap()): OpenIdToken
}

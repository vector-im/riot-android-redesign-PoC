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

package org.matrix.android.sdk.api.session.openid

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenIdToken(
        /**
         * Required. An access token the consumer may use to verify the identity of the person who generated the token.
         * This is given to the federation API GET /openid/userinfo to verify the user's identity.
         */
        @Json(name = "access_token")
        val accessToken: String,

        /**
         * Required. The string "Bearer".
         */
        @Json(name = "token_type")
        val tokenType: String,

        /**
         * Required. The homeserver domain the consumer should use when attempting to verify the user's identity.
         */
        @Json(name = "matrix_server_name")
        val matrixServerName: String,

        /**
         * Required. The number of seconds before this token expires and a new one must be generated.
         */
        @Json(name = "expires_in")
        val expiresIn: Int
)

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
package org.matrix.android.sdk.internal.session.widgets

import org.matrix.android.sdk.api.session.openid.OpenIdToken
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

internal interface WidgetsAPI {

    /**
     * register to the server
     *
     * @param body the body content (Ref: https://github.com/matrix-org/matrix-doc/pull/1961)
     */
    @POST("register")
    suspend fun register(@Body body: OpenIdToken,
                         @Query("v") version: String?): RegisterWidgetResponse

    @GET("account")
    suspend fun validateToken(@Query("scalar_token") scalarToken: String?,
                              @Query("v") version: String?)
}

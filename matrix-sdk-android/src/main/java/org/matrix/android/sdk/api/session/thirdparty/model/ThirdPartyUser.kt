/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C
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

package org.matrix.android.sdk.api.session.thirdparty.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.util.JsonDict

@JsonClass(generateAdapter = true)
data class ThirdPartyUser(
        /**
         * Required. A Matrix User ID representing a third party user.
         */
        @Json(name = "userid") val userId: String,
        /**
         * Required. The protocol ID that the third party location is a part of.
         */
        @Json(name = "protocol") val protocol: String,
        /**
         *  Required. Information used to identify this third party location.
         */
        @Json(name = "fields") val fields: JsonDict
)

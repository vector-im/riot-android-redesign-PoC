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

package org.matrix.android.sdk.api.auth.data

import android.os.Parcelable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize

@JsonClass(generateAdapter = true)
@Parcelize
data class SsoIdentityProvider(
        /**
         * The id field would be opaque with the accepted characters matching unreserved URI characters as defined in RFC3986
         * - this was chosen to avoid having to encode special characters in the URL. Max length 128.
         */
        @Json(name = "id") val id: String,
        /**
         * The name field should be the human readable string intended for printing by the client.
         */
        @Json(name = "name") val name: String?,
        /**
         * The icon field is the only optional field and should point to an icon representing the IdP.
         * If present then it must be an HTTPS URL to an image resource.
         * This should be hosted by the homeserver service provider to not leak the client's IP address unnecessarily.
         */
        @Json(name = "icon") val iconUrl: String?,

        /**
         * The `brand` field is **optional**. It allows the client to style the login
         * button to suit a particular brand. It should be a string matching the
         * "Common namespaced identifier grammar" as defined in
         * [MSC2758](https://github.com/matrix-org/matrix-doc/pull/2758).
         */
        @Json(name = "brand") val brand: String?

) : Parcelable, Comparable<SsoIdentityProvider> {

    companion object {
        const val BRAND_GOOGLE = "org.matrix.google"
        const val BRAND_GITHUB = "org.matrix.github"
        const val BRAND_APPLE = "org.matrix.apple"
        const val BRAND_FACEBOOK = "org.matrix.facebook"
        const val BRAND_TWITTER = "org.matrix.twitter"
        const val BRAND_GITLAB = "org.matrix.gitlab"
    }

    override fun compareTo(other: SsoIdentityProvider): Int {
        return other.toPriority().compareTo(toPriority())
    }

    private fun toPriority(): Int {
        return when (brand) {
            // We are on Android, so user is more likely to have a Google account
            BRAND_GOOGLE   -> 5
            // Facebook is also an important SSO provider
            BRAND_FACEBOOK -> 4
            // Twitter is more for professionals
            BRAND_TWITTER  -> 3
            // Here it's very for techie people
            BRAND_GITHUB,
            BRAND_GITLAB   -> 2
            // And finally, if the account has been created with an iPhone...
            BRAND_APPLE    -> 1
            else           -> 0
        }
    }
}

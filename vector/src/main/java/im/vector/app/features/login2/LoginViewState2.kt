/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.app.features.login2

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.PersistState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.core.extensions.toReducedUrl
import im.vector.app.features.login.LoginMode
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.auth.login.LoginProfileInfo

data class LoginViewState2(
        val isLoading: Boolean = false,

        // User choices
        @PersistState
        val signMode: SignMode2 = SignMode2.Unknown,
        @PersistState
        val userName: String? = null,
        @PersistState
        val resetPasswordEmail: String? = null,
        @PersistState
        val homeServerUrlFromUser: String? = null,

        // Can be modified after a Wellknown request
        @PersistState
        val homeServerUrl: String? = null,

        // For SSO session recovery
        @PersistState
        val deviceId: String? = null,

        // Network result
        val loginProfileInfo: Async<LoginProfileInfo> = Uninitialized,

        // Network result
        @PersistState
        val loginMode: LoginMode = LoginMode.Unknown,

        // From database
        val knownCustomHomeServersUrls: List<String> = emptyList()
) : MvRxState {

    // Pending user identifier
    fun userIdentifier(): String {
        return if (userName != null && MatrixPatterns.isUserId(userName)) {
            userName
        } else {
            "@$userName:${homeServerUrlFromUser.toReducedUrl()}"
        }
    }
}

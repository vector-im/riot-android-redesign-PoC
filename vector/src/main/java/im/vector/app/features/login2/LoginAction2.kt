/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.login2

import im.vector.app.core.platform.VectorViewModelAction
import im.vector.app.features.login.LoginConfig
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.SsoIdentityProvider
import org.matrix.android.sdk.api.auth.registration.RegisterThreePid
import org.matrix.android.sdk.internal.network.ssl.Fingerprint

sealed class LoginAction2 : VectorViewModelAction {
    // First action
    data class UpdateSignMode(val signMode: SignMode2) : LoginAction2()

    // Signin, but user wants to choose a server
    object ChooseAServerForSignin : LoginAction2()

    object EnterServerUrl : LoginAction2()
    object ChooseDefaultHomeServer : LoginAction2()
    data class UpdateHomeServer(val homeServerUrl: String) : LoginAction2()
    data class LoginWithToken(val loginToken: String) : LoginAction2()
    data class WebLoginSuccess(val credentials: Credentials) : LoginAction2()
    data class InitWith(val loginConfig: LoginConfig?) : LoginAction2()
    data class ResetPassword(val email: String, val newPassword: String) : LoginAction2()
    object ResetPasswordMailConfirmed : LoginAction2()

    // Username to Login or Register, depending on the signMode
    data class SetUserName(val username: String) : LoginAction2()

    // Password to Login or Register, depending on the signMode
    data class SetUserPassword(val password: String) : LoginAction2()

    // When user has selected a homeserver
    data class LoginWith(val login: String, val password: String) : LoginAction2()

    // Register actions
    open class RegisterAction : LoginAction2()

    data class AddThreePid(val threePid: RegisterThreePid) : RegisterAction()
    object SendAgainThreePid : RegisterAction()

    // TODO Confirm Email (from link in the email, open in the phone, intercepted by RiotX)
    data class ValidateThreePid(val code: String) : RegisterAction()

    data class CheckIfEmailHasBeenValidated(val delayMillis: Long) : RegisterAction()
    object StopEmailValidationCheck : RegisterAction()

    data class CaptchaDone(val captchaResponse: String) : RegisterAction()
    object AcceptTerms : RegisterAction()
    object RegisterDummy : RegisterAction()

    // Reset actions
    open class ResetAction : LoginAction2()

    object ResetHomeServerUrl : ResetAction()
    object ResetSignMode : ResetAction()
    object ResetSignin : ResetAction()
    object ResetSignup : ResetAction()
    object ResetResetPassword : ResetAction()

    // Homeserver history
    object ClearHomeServerHistory : LoginAction2()

    // For the soft logout case
    data class SetupSsoForSessionRecovery(val homeServerUrl: String,
                                          val deviceId: String,
                                          val ssoIdentityProviders: List<SsoIdentityProvider>?) : LoginAction2()

    data class PostViewEvent(val viewEvent: LoginViewEvents2) : LoginAction2()

    data class UserAcceptCertificate(val fingerprint: Fingerprint) : LoginAction2()

    // Account customization is over
    object Finish : LoginAction2()
}

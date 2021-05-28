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

package org.matrix.android.sdk.api.auth.registration

/**
 * Set of methods to be able to create an account on a homeserver.
 *
 * Common scenario to register an account successfully:
 *  - Call [getRegistrationFlow] to check that you application supports all the mandatory registration stages
 *  - Call [createAccount] to start the account creation
 *  - Fulfill all mandatory stages using the methods [performReCaptcha] [acceptTerms] [dummy], etc.
 *
 * More documentation can be found in the file https://github.com/vector-im/element-android/blob/main/docs/signup.md
 * and https://matrix.org/docs/spec/client_server/latest#account-registration-and-management
 */
interface RegistrationWizard {
    /**
     * Call this method to get the possible registration flow of the current homeserver.
     * It can be useful to ensure that your application implementation supports all the stages
     * required to create an account. If it is not the case, you will have to use the web fallback
     * to let the user create an account with your application.
     * See [org.matrix.android.sdk.api.auth.AuthenticationService.getFallbackUrl]
     */
    suspend fun getRegistrationFlow(): RegistrationResult

    /**
     * Can be call to check is the desired userName is available for registration on the current homeserver.
     * It may also fails if the desired userName is not correctly formatted or does not follow any restriction on
     * the homeserver. Ex: userName with only digits may be rejected.
     * @param userName the desired username. Ex: "alice"
     */
    suspend fun registrationAvailable(userName: String): RegistrationAvailability

    /**
     * This is the first method to call in order to create an account and start the registration process.
     *
     * @param userName the desired username. Ex: "alice"
     * @param password the desired password
     * @param initialDeviceDisplayName the device display name
     */
    suspend fun createAccount(userName: String?,
                              password: String?,
                              initialDeviceDisplayName: String?): RegistrationResult

    /**
     * Perform the "m.login.recaptcha" stage.
     *
     * @param response the response from ReCaptcha
     */
    suspend fun performReCaptcha(response: String): RegistrationResult

    /**
     * Perform the "m.login.terms" stage.
     */
    suspend fun acceptTerms(): RegistrationResult

    /**
     * Perform the "m.login.dummy" stage.
     */
    suspend fun dummy(): RegistrationResult

    /**
     * Perform the "m.login.email.identity" or "m.login.msisdn" stage.
     *
     * @param threePid the threePid to add to the account. If this is an email, the homeserver will send an email
     * to validate it. For a msisdn a SMS will be sent.
     */
    suspend fun addThreePid(threePid: RegisterThreePid): RegistrationResult

    /**
     * Ask the homeserver to send again the current threePid (email or msisdn).
     */
    suspend fun sendAgainThreePid(): RegistrationResult

    /**
     * Send the code received by SMS to validate a msisdn.
     * If the code is correct, the registration request will be executed to validate the msisdn.
     */
    suspend fun handleValidateThreePid(code: String): RegistrationResult

    /**
     * Useful to poll the homeserver when waiting for the email to be validated by the user.
     * Once the email is validated, this method will return successfully.
     * @param delayMillis the SDK can wait before sending the request
     */
    suspend fun checkIfEmailHasBeenValidated(delayMillis: Long): RegistrationResult

    /**
     * This is the current ThreePid, waiting for validation. The SDK will store it in database, so it can be
     * restored even if the app has been killed during the registration
     */
    val currentThreePid: String?

    /**
     * True when login and password have been sent with success to the homeserver, i.e. [createAccount] has been
     * called successfully.
     */
    val isRegistrationStarted: Boolean
}

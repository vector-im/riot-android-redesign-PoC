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

package im.vector.app.core.error

import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.call.dialpad.DialPadLookup
import im.vector.app.features.voice.VoiceFailure
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.failure.MatrixIdFailure
import org.matrix.android.sdk.api.failure.isInvalidPassword
import org.matrix.android.sdk.api.session.identity.IdentityServiceError
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

interface ErrorFormatter {
    fun toHumanReadable(throwable: Throwable?): String
}

class DefaultErrorFormatter @Inject constructor(
        private val stringProvider: StringProvider
) : ErrorFormatter {

    override fun toHumanReadable(throwable: Throwable?): String {
        return when (throwable) {
            null                                   -> null
            is IdentityServiceError                -> identityServerError(throwable)
            is Failure.NetworkConnection           -> {
                when (throwable.ioException) {
                    is SocketTimeoutException     ->
                        stringProvider.getString(R.string.error_network_timeout)
                    is SSLPeerUnverifiedException ->
                        stringProvider.getString(R.string.login_error_ssl_peer_unverified)
                    is SSLException               ->
                        stringProvider.getString(R.string.login_error_ssl_other)
                    else                          ->
                        // TODO Check network state, airplane mode, etc.
                        stringProvider.getString(R.string.error_no_network)
                }
            }
            is Failure.ServerError                 -> {
                when {
                    throwable.error.code == MatrixError.M_CONSENT_NOT_GIVEN          -> {
                        // Special case for terms and conditions
                        stringProvider.getString(R.string.error_terms_not_accepted)
                    }
                    throwable.isInvalidPassword()                                    -> {
                        stringProvider.getString(R.string.auth_invalid_login_param)
                    }
                    throwable.error.code == MatrixError.M_USER_IN_USE                -> {
                        stringProvider.getString(R.string.login_signup_error_user_in_use)
                    }
                    throwable.error.code == MatrixError.M_BAD_JSON                   -> {
                        stringProvider.getString(R.string.login_error_bad_json)
                    }
                    throwable.error.code == MatrixError.M_NOT_JSON                   -> {
                        stringProvider.getString(R.string.login_error_not_json)
                    }
                    throwable.error.code == MatrixError.M_THREEPID_DENIED            -> {
                        stringProvider.getString(R.string.login_error_threepid_denied)
                    }
                    throwable.error.code == MatrixError.M_LIMIT_EXCEEDED             -> {
                        limitExceededError(throwable.error)
                    }
                    throwable.error.code == MatrixError.M_TOO_LARGE                  -> {
                        stringProvider.getString(R.string.error_file_too_big_simple)
                    }
                    throwable.error.code == MatrixError.M_THREEPID_NOT_FOUND         -> {
                        stringProvider.getString(R.string.login_reset_password_error_not_found)
                    }
                    throwable.error.code == MatrixError.M_USER_DEACTIVATED           -> {
                        stringProvider.getString(R.string.auth_invalid_login_deactivated_account)
                    }
                    throwable.error.code == MatrixError.M_THREEPID_IN_USE
                            && throwable.error.message == "Email is already in use"  -> {
                        stringProvider.getString(R.string.account_email_already_used_error)
                    }
                    throwable.error.code == MatrixError.M_THREEPID_IN_USE
                            && throwable.error.message == "MSISDN is already in use" -> {
                        stringProvider.getString(R.string.account_phone_number_already_used_error)
                    }
                    throwable.error.code == MatrixError.M_THREEPID_AUTH_FAILED       -> {
                        stringProvider.getString(R.string.error_threepid_auth_failed)
                    }
                    else                                                             -> {
                        throwable.error.message.takeIf { it.isNotEmpty() }
                                ?: throwable.error.code.takeIf { it.isNotEmpty() }
                    }
                }
            }
            is Failure.OtherServerError            -> {
                when (throwable.httpCode) {
                    HttpURLConnection.HTTP_NOT_FOUND    ->
                        // homeserver not found
                        stringProvider.getString(R.string.login_error_no_homeserver_found)
                    HttpURLConnection.HTTP_UNAUTHORIZED ->
                        // uia errors?
                        stringProvider.getString(R.string.error_unauthorized)
                    else                                ->
                        throwable.localizedMessage
                }
            }
            is DialPadLookup.Failure.NumberIsYours ->
                stringProvider.getString(R.string.cannot_call_yourself)
            is DialPadLookup.Failure.NoResult      ->
                stringProvider.getString(R.string.call_dial_pad_lookup_error)
            is MatrixIdFailure.InvalidMatrixId     ->
                stringProvider.getString(R.string.login_signin_matrix_id_error_invalid_matrix_id)
            is VoiceFailure                        -> voiceMessageError(throwable)
            else                                   -> throwable.localizedMessage
        }
                ?: stringProvider.getString(R.string.unknown_error)
    }

    private fun voiceMessageError(throwable: VoiceFailure): String {
        return when (throwable) {
            is VoiceFailure.UnableToPlay   -> stringProvider.getString(R.string.error_voice_message_unable_to_play)
            is VoiceFailure.UnableToRecord -> stringProvider.getString(R.string.error_voice_message_unable_to_record)
        }
    }

    private fun limitExceededError(error: MatrixError): String {
        val delay = error.retryAfterMillis

        return if (delay == null) {
            stringProvider.getString(R.string.login_error_limit_exceeded)
        } else {
            // Ensure at least 1 second
            val delaySeconds = delay.toInt() / 1000 + 1
            stringProvider.getQuantityString(R.plurals.login_error_limit_exceeded_retry_after, delaySeconds, delaySeconds)
        }
    }

    private fun identityServerError(identityServiceError: IdentityServiceError): String {
        return stringProvider.getString(when (identityServiceError) {
            IdentityServiceError.OutdatedIdentityServer       -> R.string.identity_server_error_outdated_identity_server
            IdentityServiceError.OutdatedHomeServer           -> R.string.identity_server_error_outdated_home_server
            IdentityServiceError.NoIdentityServerConfigured   -> R.string.identity_server_error_no_identity_server_configured
            IdentityServiceError.TermsNotSignedException      -> R.string.identity_server_error_terms_not_signed
            IdentityServiceError.BulkLookupSha256NotSupported -> R.string.identity_server_error_bulk_sha256_not_supported
            IdentityServiceError.BindingError                 -> R.string.identity_server_error_binding_error
            IdentityServiceError.NoCurrentBindingError        -> R.string.identity_server_error_no_current_binding_error
            IdentityServiceError.UserConsentNotProvided       -> R.string.identity_server_user_consent_not_provided
        })
    }
}

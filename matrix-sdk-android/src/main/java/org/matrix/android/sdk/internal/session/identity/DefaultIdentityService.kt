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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dagger.Lazy
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.session.identity.FoundThreePid
import org.matrix.android.sdk.api.session.identity.IdentityService
import org.matrix.android.sdk.api.session.identity.IdentityServiceError
import org.matrix.android.sdk.api.session.identity.IdentityServiceListener
import org.matrix.android.sdk.api.session.identity.SharedState
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.internal.di.AuthenticatedIdentity
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificate
import org.matrix.android.sdk.internal.extensions.observeNotNull
import org.matrix.android.sdk.internal.network.RetrofitFactory
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.identity.data.IdentityStore
import org.matrix.android.sdk.internal.session.openid.GetOpenIdTokenTask
import org.matrix.android.sdk.internal.session.profile.BindThreePidsTask
import org.matrix.android.sdk.internal.session.profile.UnbindThreePidsTask
import org.matrix.android.sdk.internal.session.sync.model.accountdata.IdentityServerContent
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.internal.session.user.accountdata.UserAccountDataDataSource
import org.matrix.android.sdk.internal.session.user.accountdata.UpdateUserAccountDataTask
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.ensureProtocol
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

@SessionScope
internal class DefaultIdentityService @Inject constructor(
        private val identityStore: IdentityStore,
        private val ensureIdentityTokenTask: EnsureIdentityTokenTask,
        private val getOpenIdTokenTask: GetOpenIdTokenTask,
        private val identityBulkLookupTask: IdentityBulkLookupTask,
        private val identityRegisterTask: IdentityRegisterTask,
        private val identityPingTask: IdentityPingTask,
        private val identityDisconnectTask: IdentityDisconnectTask,
        private val identityRequestTokenForBindingTask: IdentityRequestTokenForBindingTask,
        @UnauthenticatedWithCertificate
        private val unauthenticatedOkHttpClient: Lazy<OkHttpClient>,
        @AuthenticatedIdentity
        private val okHttpClient: Lazy<OkHttpClient>,
        private val retrofitFactory: RetrofitFactory,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val bindThreePidsTask: BindThreePidsTask,
        private val submitTokenForBindingTask: IdentitySubmitTokenForBindingTask,
        private val unbindThreePidsTask: UnbindThreePidsTask,
        private val identityApiProvider: IdentityApiProvider,
        private val accountDataDataSource: UserAccountDataDataSource,
        private val homeServerCapabilitiesService: HomeServerCapabilitiesService,
        private val sessionParams: SessionParams
) : IdentityService, SessionLifecycleObserver {

    private val lifecycleOwner: LifecycleOwner = LifecycleOwner { lifecycleRegistry }
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val listeners = mutableSetOf<IdentityServiceListener>()

    override fun onSessionStarted(session: Session) {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        // Observe the account data change
        accountDataDataSource
                .getLiveAccountDataEvent(UserAccountDataTypes.TYPE_IDENTITY_SERVER)
                .observeNotNull(lifecycleOwner) {
                    notifyIdentityServerUrlChange(it.getOrNull()?.content?.toModel<IdentityServerContent>()?.baseUrl)
                }

        // Init identityApi
        updateIdentityAPI(identityStore.getIdentityData()?.identityServerUrl)
    }

    private fun notifyIdentityServerUrlChange(baseUrl: String?) {
        // This is maybe not a real change (echo of account data we are just setting)
        if (identityStore.getIdentityData()?.identityServerUrl == baseUrl) {
            Timber.d("Echo of local identity server url change, or no change")
        } else {
            // Url has changed, we have to reset our store, update internal configuration and notify listeners
            identityStore.setUrl(baseUrl)
            updateIdentityAPI(baseUrl)
            listeners.toList().forEach { tryOrNull { it.onIdentityServerChange() } }
        }
    }

    override fun onSessionStopped(session: Session) {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * First return the identity server provided during login phase.
     * If null, provide the one in wellknown configuration of the homeserver
     * Else return null
     */
    override fun getDefaultIdentityServer(): String? {
        return sessionParams.defaultIdentityServerUrl
                ?.takeIf { it.isNotEmpty() }
                ?: homeServerCapabilitiesService.getHomeServerCapabilities().defaultIdentityServerUrl
    }

    override fun getCurrentIdentityServerUrl(): String? {
        return identityStore.getIdentityData()?.identityServerUrl
    }

    override suspend fun startBindThreePid(threePid: ThreePid) {
        if (homeServerCapabilitiesService.getHomeServerCapabilities().lastVersionIdentityServerSupported.not()) {
            throw IdentityServiceError.OutdatedHomeServer
        }

        identityRequestTokenForBindingTask.execute(IdentityRequestTokenForBindingTask.Params(threePid, false))
    }

    override suspend fun cancelBindThreePid(threePid: ThreePid) {
        identityStore.deletePendingBinding(threePid)
    }

    override suspend fun sendAgainValidationCode(threePid: ThreePid) {
        identityRequestTokenForBindingTask.execute(IdentityRequestTokenForBindingTask.Params(threePid, true))
    }

    override suspend fun finalizeBindThreePid(threePid: ThreePid) {
        if (homeServerCapabilitiesService.getHomeServerCapabilities().lastVersionIdentityServerSupported.not()) {
            throw IdentityServiceError.OutdatedHomeServer
        }

        bindThreePidsTask.execute(BindThreePidsTask.Params(threePid))
    }

    override suspend fun submitValidationToken(threePid: ThreePid, code: String) {
        submitTokenForBindingTask.execute(IdentitySubmitTokenForBindingTask.Params(threePid, code))
    }

    override suspend fun unbindThreePid(threePid: ThreePid) {
        if (homeServerCapabilitiesService.getHomeServerCapabilities().lastVersionIdentityServerSupported.not()) {
            throw IdentityServiceError.OutdatedHomeServer
        }

        unbindThreePidsTask.execute(UnbindThreePidsTask.Params(threePid))
    }

    override suspend fun isValidIdentityServer(url: String) {
        val api = retrofitFactory.create(unauthenticatedOkHttpClient, url).create(IdentityAuthAPI::class.java)

        identityPingTask.execute(IdentityPingTask.Params(api))
    }

    override suspend fun disconnect() {
        identityDisconnectTask.execute(Unit)

        identityStore.setUrl(null)
        updateIdentityAPI(null)
        updateAccountData(null)
    }

    override suspend fun setNewIdentityServer(url: String): String {
        val urlCandidate = url.ensureProtocol()

        val current = getCurrentIdentityServerUrl()
        if (urlCandidate == current) {
            // Nothing to do
            Timber.d("Same URL, nothing to do")
        } else {
            // Disconnect previous one if any, first, because the token will change.
            // In case of error when configuring the new identity server, this is not a big deal,
            // we will ask for a new token on the previous Identity server
            runCatching { identityDisconnectTask.execute(Unit) }
                    .onFailure { Timber.w(it, "Unable to disconnect identity server") }

            // Try to get a token
            val token = getNewIdentityServerToken(urlCandidate)

            identityStore.setUrl(urlCandidate)
            identityStore.setToken(token)
            updateIdentityAPI(urlCandidate)

            updateAccountData(urlCandidate)
        }

        return urlCandidate
    }

    private suspend fun updateAccountData(url: String?) {
        // Also notify the listener
        withContext(coroutineDispatchers.main) {
            listeners.toList().forEach { tryOrNull { it.onIdentityServerChange() } }
        }

        updateUserAccountDataTask.execute(UpdateUserAccountDataTask.IdentityParams(
                identityContent = IdentityServerContent(baseUrl = url)
        ))
    }

    override fun getUserConsent(): Boolean {
        return identityStore.getIdentityData()?.userConsent.orFalse()
    }

    override fun setUserConsent(newValue: Boolean) {
        identityStore.setUserConsent(newValue)
    }

    override suspend fun lookUp(threePids: List<ThreePid>): List<FoundThreePid> {
        if (!getUserConsent()) {
            throw IdentityServiceError.UserConsentNotProvided
        }

        if (threePids.isEmpty()) {
            return emptyList()
        }

        return lookUpInternal(true, threePids)
    }

    override suspend fun getShareStatus(threePids: List<ThreePid>): Map<ThreePid, SharedState> {
        // Note: we do not require user consent here, because it is used for emails and phone numbers that the user has already sent
        // to the home server, and not emails and phone numbers from the contact book of the user

        if (threePids.isEmpty()) {
            return emptyMap()
        }

        val lookupResult = lookUpInternal(true, threePids)

        return threePids.associateWith { threePid ->
            // If not in lookup result, check if there is a pending binding
            if (lookupResult.firstOrNull { it.threePid == threePid } == null) {
                if (identityStore.getPendingBinding(threePid) == null) {
                    SharedState.NOT_SHARED
                } else {
                    SharedState.BINDING_IN_PROGRESS
                }
            } else {
                SharedState.SHARED
            }
        }
    }

    private suspend fun lookUpInternal(canRetry: Boolean, threePids: List<ThreePid>): List<FoundThreePid> {
        ensureIdentityTokenTask.execute(Unit)

        return try {
            identityBulkLookupTask.execute(IdentityBulkLookupTask.Params(threePids))
        } catch (throwable: Throwable) {
            // Refresh token?
            when {
                throwable.isInvalidToken() && canRetry -> {
                    identityStore.setToken(null)
                    lookUpInternal(false, threePids)
                }
                throwable.isTermsNotSigned()           -> throw IdentityServiceError.TermsNotSignedException
                else                                   -> throw throwable
            }
        }
    }

    private suspend fun getNewIdentityServerToken(url: String): String {
        val api = retrofitFactory.create(unauthenticatedOkHttpClient, url).create(IdentityAuthAPI::class.java)

        val openIdToken = getOpenIdTokenTask.execute(Unit)
        val token = identityRegisterTask.execute(IdentityRegisterTask.Params(api, openIdToken))

        return token.token
    }

    override fun addListener(listener: IdentityServiceListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: IdentityServiceListener) {
        listeners.remove(listener)
    }

    private fun updateIdentityAPI(url: String?) {
        identityApiProvider.identityApi = url
                ?.let { retrofitFactory.create(okHttpClient, it) }
                ?.create(IdentityAPI::class.java)
    }
}

private fun Throwable.isInvalidToken(): Boolean {
    return this is Failure.ServerError
            && httpCode == HttpsURLConnection.HTTP_UNAUTHORIZED /* 401 */
}

private fun Throwable.isTermsNotSigned(): Boolean {
    return this is Failure.ServerError
            && httpCode == HttpsURLConnection.HTTP_FORBIDDEN /* 403 */
            && error.code == MatrixError.M_TERMS_NOT_SIGNED
}

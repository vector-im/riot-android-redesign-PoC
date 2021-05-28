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

import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.identity.FoundThreePid
import org.matrix.android.sdk.api.session.identity.IdentityServiceError
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.identity.toMedium
import org.matrix.android.sdk.internal.crypto.tools.withOlmUtility
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.identity.data.IdentityStore
import org.matrix.android.sdk.internal.session.identity.model.IdentityHashDetailResponse
import org.matrix.android.sdk.internal.session.identity.model.IdentityLookUpParams
import org.matrix.android.sdk.internal.session.identity.model.IdentityLookUpResponse
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.base64ToBase64Url
import java.util.Locale
import javax.inject.Inject

internal interface IdentityBulkLookupTask : Task<IdentityBulkLookupTask.Params, List<FoundThreePid>> {
    data class Params(
            val threePids: List<ThreePid>
    )
}

internal class DefaultIdentityBulkLookupTask @Inject constructor(
        private val identityApiProvider: IdentityApiProvider,
        private val identityStore: IdentityStore,
        @UserId private val userId: String
) : IdentityBulkLookupTask {

    override suspend fun execute(params: IdentityBulkLookupTask.Params): List<FoundThreePid> {
        val identityAPI = getIdentityApiAndEnsureTerms(identityApiProvider, userId)
        val identityData = identityStore.getIdentityData() ?: throw IdentityServiceError.NoIdentityServerConfigured
        val pepper = identityData.hashLookupPepper
        val hashDetailResponse = if (pepper == null) {
            // We need to fetch the hash details first
            fetchHashDetails(identityAPI)
                    .also { identityStore.setHashDetails(it) }
        } else {
            IdentityHashDetailResponse(pepper, identityData.hashLookupAlgorithm)
        }

        if (hashDetailResponse.algorithms.contains(IdentityHashDetailResponse.ALGORITHM_SHA256).not()) {
            // TODO We should ask the user if he is ok to send their 3Pid in clear, but for the moment we do not do it
            // Also, what we have in cache could be outdated, the identity server maybe now supports sha256
            throw IdentityServiceError.BulkLookupSha256NotSupported
        }

        val lookUpData = lookUpInternal(identityAPI, params.threePids, hashDetailResponse, true)

        // Convert back to List<FoundThreePid>
        return handleSuccess(params.threePids, lookUpData)
    }

    data class LookUpData(
            val hashedAddresses: List<String>,
            val identityLookUpResponse: IdentityLookUpResponse
    )

    private suspend fun lookUpInternal(identityAPI: IdentityAPI,
                                       threePids: List<ThreePid>,
                                       hashDetailResponse: IdentityHashDetailResponse,
                                       canRetry: Boolean): LookUpData {
        val hashedAddresses = getHashedAddresses(threePids, hashDetailResponse.pepper)
        return try {
            LookUpData(hashedAddresses,
                    executeRequest(null) {
                        identityAPI.lookup(IdentityLookUpParams(
                                hashedAddresses,
                                IdentityHashDetailResponse.ALGORITHM_SHA256,
                                hashDetailResponse.pepper
                        ))
                    })
        } catch (failure: Throwable) {
            // Catch invalid hash pepper and retry
            if (canRetry && failure is Failure.ServerError && failure.error.code == MatrixError.M_INVALID_PEPPER) {
                // This is not documented, but the error can contain the new pepper!
                val newHashDetailResponse = if (!failure.error.newLookupPepper.isNullOrEmpty()) {
                    // Store it and use it right now
                    hashDetailResponse.copy(pepper = failure.error.newLookupPepper)
                } else {
                    // Retrieve the new hash details
                    fetchHashDetails(identityAPI)
                }
                        .also { identityStore.setHashDetails(it) }
                if (newHashDetailResponse.algorithms.contains(IdentityHashDetailResponse.ALGORITHM_SHA256).not()) {
                    // TODO We should ask the user if he is ok to send their 3Pid in clear, but for the moment we do not do it
                    throw IdentityServiceError.BulkLookupSha256NotSupported
                }
                lookUpInternal(identityAPI, threePids, newHashDetailResponse, false /* Avoid infinite loop */)
            } else {
                // Other error
                throw failure
            }
        }
    }

    private fun getHashedAddresses(threePids: List<ThreePid>, pepper: String): List<String> {
        return withOlmUtility { olmUtility ->
            threePids.map { threePid ->
                base64ToBase64Url(
                        olmUtility.sha256(threePid.value.lowercase(Locale.ROOT)
                                + " " + threePid.toMedium() + " " + pepper)
                )
            }
        }
    }

    private suspend fun fetchHashDetails(identityAPI: IdentityAPI): IdentityHashDetailResponse {
        return executeRequest(null) {
            identityAPI.hashDetails()
        }
    }

    private fun handleSuccess(threePids: List<ThreePid>, lookupData: LookUpData): List<FoundThreePid> {
        return lookupData.identityLookUpResponse.mappings.keys.map { hashedAddress ->
            FoundThreePid(
                    threePids[lookupData.hashedAddresses.indexOf(hashedAddress)],
                    lookupData.identityLookUpResponse.mappings[hashedAddress] ?: error("")
            )
        }
    }
}

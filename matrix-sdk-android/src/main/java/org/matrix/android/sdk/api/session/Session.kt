/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session

import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.failure.GlobalError
import org.matrix.android.sdk.api.federation.FederationService
import org.matrix.android.sdk.api.pushrules.PushRuleService
import org.matrix.android.sdk.api.session.account.AccountService
import org.matrix.android.sdk.api.session.accountdata.AccountDataService
import org.matrix.android.sdk.api.session.cache.CacheService
import org.matrix.android.sdk.api.session.call.CallSignalingService
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.api.session.file.ContentDownloadStateTracker
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.group.GroupService
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.session.identity.IdentityService
import org.matrix.android.sdk.api.session.initsync.InitialSyncProgressService
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.media.MediaService
import org.matrix.android.sdk.api.session.openid.OpenIdService
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.api.session.pushers.PushersService
import org.matrix.android.sdk.api.session.room.RoomDirectoryService
import org.matrix.android.sdk.api.session.room.RoomService
import org.matrix.android.sdk.api.session.search.SearchService
import org.matrix.android.sdk.api.session.securestorage.SecureStorageService
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageService
import org.matrix.android.sdk.api.session.signout.SignOutService
import org.matrix.android.sdk.api.session.space.SpaceService
import org.matrix.android.sdk.api.session.sync.FilterService
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.session.terms.TermsService
import org.matrix.android.sdk.api.session.thirdparty.ThirdPartyService
import org.matrix.android.sdk.api.session.typing.TypingUsersTracker
import org.matrix.android.sdk.api.session.user.UserService
import org.matrix.android.sdk.api.session.widgets.WidgetService

/**
 * This interface defines interactions with a session.
 * An instance of a session will be provided by the SDK.
 */
interface Session :
        RoomService,
        RoomDirectoryService,
        GroupService,
        UserService,
        CacheService,
        SignOutService,
        FilterService,
        TermsService,
        EventService,
        ProfileService,
        PushRuleService,
        PushersService,
        InitialSyncProgressService,
        HomeServerCapabilitiesService,
        SecureStorageService,
        AccountService {

    /**
     * The params associated to the session
     */
    val sessionParams: SessionParams

    /**
     * The session is valid, i.e. it has a valid token so far
     */
    val isOpenable: Boolean

    /**
     * Useful shortcut to get access to the userId
     */
    val myUserId: String
        get() = sessionParams.userId

    /**
     * The sessionId
     */
    val sessionId: String

    /**
     * This method allow to open a session. It does start some service on the background.
     */
    @MainThread
    fun open()

    /**
     * Requires a one time background sync
     */
    fun requireBackgroundSync()

    /**
     * Launches infinite periodic background syncs
     * This does not work in doze mode :/
     * If battery optimization is on it can work in app standby but that's all :/
     */
    fun startAutomaticBackgroundSync(timeOutInSeconds: Long, repeatDelayInSeconds: Long)

    fun stopAnyBackgroundSync()

    /**
     * This method start the sync thread.
     */
    fun startSync(fromForeground: Boolean)

    /**
     * This method stop the sync thread.
     */
    fun stopSync()

    /**
     * This method allows to listen the sync state.
     * @return a [LiveData] of [SyncState].
     */
    fun getSyncStateLive(): LiveData<SyncState>

    /**
     * This method returns the current sync state.
     * @return the current [SyncState].
     */
    fun getSyncState(): SyncState

    /**
     * This methods return true if an initial sync has been processed
     */
    fun hasAlreadySynced(): Boolean

    /**
     * This method allow to close a session. It does stop some services.
     */
    fun close()

    /**
     * Returns the ContentUrlResolver associated to the session.
     */
    fun contentUrlResolver(): ContentUrlResolver

    /**
     * Returns the ContentUploadProgressTracker associated with the session
     */
    fun contentUploadProgressTracker(): ContentUploadStateTracker

    /**
     * Returns the TypingUsersTracker associated with the session
     */
    fun typingUsersTracker(): TypingUsersTracker

    /**
     * Returns the ContentDownloadStateTracker associated with the session
     */
    fun contentDownloadProgressTracker(): ContentDownloadStateTracker

    /**
     * Returns the cryptoService associated with the session
     */
    fun cryptoService(): CryptoService

    /**
     * Returns the identity service associated with the session
     */
    fun identityService(): IdentityService

    /**
     * Returns the widget service associated with the session
     */
    fun widgetService(): WidgetService

    /**
     * Returns the media service associated with the session
     */
    fun mediaService(): MediaService

    /**
     * Returns the integration manager service associated with the session
     */
    fun integrationManagerService(): IntegrationManagerService

    /**
     * Returns the call signaling service associated with the session
     */
    fun callSignalingService(): CallSignalingService

    /**
     * Returns the file download service associated with the session
     */
    fun fileService(): FileService

    /**
     * Returns the permalink service associated with the session
     */
    fun permalinkService(): PermalinkService

    /**
     * Returns the search service associated with the session
     */
    fun searchService(): SearchService

    /**
     * Returns the federation service associated with the session
     */
    fun federationService(): FederationService

    /**
     * Returns the third party service associated with the session
     */
    fun thirdPartyService(): ThirdPartyService

    /**
     * Returns the space service associated with the session
     */
    fun spaceService(): SpaceService

    /**
     * Returns the open id service associated with the session
     */
    fun openIdService(): OpenIdService

    /**
     * Returns the user account data service associated with the session
     */
    fun userAccountDataService(): AccountDataService

    /**
     * Add a listener to the session.
     * @param listener the listener to add.
     */
    fun addListener(listener: Listener)

    /**
     * Remove a listener from the session.
     * @param listener the listener to remove.
     */
    fun removeListener(listener: Listener)

    /**
     * Will return a OkHttpClient which will manage pinned certificates and Proxy if configured.
     * It will not add any access-token to the request.
     * So it is exposed to let the app be able to download image with Glide or any other libraries which accept an OkHttp client.
     */
    fun getOkHttpClient(): OkHttpClient

    /**
     * A global session listener to get notified for some events.
     */
    interface Listener : SessionLifecycleObserver {
        /**
         * Called when the session received new invites to room so the client can react to it once.
         */
        fun onNewInvitedRoom(session: Session, roomId: String) = Unit

        /**
         * Possible cases:
         * - The access token is not valid anymore,
         * - a M_CONSENT_NOT_GIVEN error has been received from the homeserver
         */
        fun onGlobalError(session: Session, globalError: GlobalError) = Unit
    }

    val sharedSecretStorageService: SharedSecretStorageService

    fun getUiaSsoFallbackUrl(authenticationSessionId: String): String

    /**
     * Maintenance API, allows to print outs info on DB size to logcat
     */
    fun logDbUsageInfo()
}

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

package org.matrix.android.sdk.internal.session

import android.content.Context
import android.os.Build
import com.zhuinden.monarchy.Monarchy
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import io.realm.RealmConfiguration
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.api.crypto.MXCryptoConfig
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.api.session.accountdata.AccountDataService
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.session.initsync.InitialSyncProgressService
import org.matrix.android.sdk.api.session.openid.OpenIdService
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.session.securestorage.SecureStorageService
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageService
import org.matrix.android.sdk.api.session.typing.TypingUsersTracker
import org.matrix.android.sdk.internal.crypto.secrets.DefaultSharedSecretStorageService
import org.matrix.android.sdk.internal.crypto.tasks.DefaultRedactEventTask
import org.matrix.android.sdk.internal.crypto.tasks.RedactEventTask
import org.matrix.android.sdk.internal.crypto.verification.VerificationMessageProcessor
import org.matrix.android.sdk.internal.database.DatabaseCleaner
import org.matrix.android.sdk.internal.database.EventInsertLiveObserver
import org.matrix.android.sdk.internal.database.RealmSessionProvider
import org.matrix.android.sdk.internal.database.SessionRealmConfigurationFactory
import org.matrix.android.sdk.internal.di.Authenticated
import org.matrix.android.sdk.internal.di.CacheDirectory
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.SessionDownloadsDirectory
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.Unauthenticated
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificate
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificateWithProgress
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.di.UserMd5
import org.matrix.android.sdk.internal.network.DefaultNetworkConnectivityChecker
import org.matrix.android.sdk.internal.network.FallbackNetworkCallbackStrategy
import org.matrix.android.sdk.internal.network.GlobalErrorHandler
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.NetworkCallbackStrategy
import org.matrix.android.sdk.internal.network.NetworkConnectivityChecker
import org.matrix.android.sdk.internal.network.PreferredNetworkCallbackStrategy
import org.matrix.android.sdk.internal.network.RetrofitFactory
import org.matrix.android.sdk.internal.network.httpclient.addAccessTokenInterceptor
import org.matrix.android.sdk.internal.network.httpclient.addSocketFactory
import org.matrix.android.sdk.internal.network.interceptors.CurlLoggingInterceptor
import org.matrix.android.sdk.internal.network.token.AccessTokenProvider
import org.matrix.android.sdk.internal.network.token.HomeserverAccessTokenProvider
import org.matrix.android.sdk.internal.session.call.CallEventProcessor
import org.matrix.android.sdk.internal.session.download.DownloadProgressInterceptor
import org.matrix.android.sdk.internal.session.events.DefaultEventService
import org.matrix.android.sdk.internal.session.homeserver.DefaultHomeServerCapabilitiesService
import org.matrix.android.sdk.internal.session.identity.DefaultIdentityService
import org.matrix.android.sdk.internal.session.initsync.DefaultInitialSyncProgressService
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManager
import org.matrix.android.sdk.internal.session.openid.DefaultOpenIdService
import org.matrix.android.sdk.internal.session.permalinks.DefaultPermalinkService
import org.matrix.android.sdk.internal.session.room.EventRelationsAggregationProcessor
import org.matrix.android.sdk.internal.session.room.create.RoomCreateEventProcessor
import org.matrix.android.sdk.internal.session.room.prune.RedactionEventProcessor
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessorCoroutine
import org.matrix.android.sdk.internal.session.room.tombstone.RoomTombstoneEventProcessor
import org.matrix.android.sdk.internal.session.securestorage.DefaultSecureStorageService
import org.matrix.android.sdk.internal.session.typing.DefaultTypingUsersTracker
import org.matrix.android.sdk.internal.session.user.accountdata.UserAccountDataService
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetURLFormatter
import org.matrix.android.sdk.internal.util.md5
import retrofit2.Retrofit
import java.io.File
import javax.inject.Provider
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MockHttpInterceptor

@Module
internal abstract class SessionModule {

    @Module
    companion object {
        internal fun getKeyAlias(userMd5: String) = "session_db_$userMd5"

        /**
         * Rules:
         * Annotate methods with @SessionScope only the @Provides annotated methods with computation and logic.
         */

        @JvmStatic
        @Provides
        fun providesHomeServerConnectionConfig(sessionParams: SessionParams): HomeServerConnectionConfig {
            return sessionParams.homeServerConnectionConfig
        }

        @JvmStatic
        @Provides
        fun providesCredentials(sessionParams: SessionParams): Credentials {
            return sessionParams.credentials
        }

        @JvmStatic
        @UserId
        @Provides
        @SessionScope
        fun providesUserId(credentials: Credentials): String {
            return credentials.userId
        }

        @JvmStatic
        @DeviceId
        @Provides
        fun providesDeviceId(credentials: Credentials): String? {
            return credentials.deviceId
        }

        @JvmStatic
        @UserMd5
        @Provides
        @SessionScope
        fun providesUserMd5(@UserId userId: String): String {
            return userId.md5()
        }

        @JvmStatic
        @SessionId
        @Provides
        @SessionScope
        fun providesSessionId(credentials: Credentials): String {
            return credentials.sessionId()
        }

        @JvmStatic
        @Provides
        @SessionFilesDirectory
        fun providesFilesDir(@UserMd5 userMd5: String,
                             @SessionId sessionId: String,
                             context: Context): File {
            // Temporary code for migration
            val old = File(context.filesDir, userMd5)
            if (old.exists()) {
                old.renameTo(File(context.filesDir, sessionId))
            }

            return File(context.filesDir, sessionId)
        }

        @JvmStatic
        @Provides
        @SessionDownloadsDirectory
        fun providesDownloadsCacheDir(@SessionId sessionId: String,
                                      @CacheDirectory cacheFile: File): File {
            return File(cacheFile, "downloads/$sessionId")
        }

        @JvmStatic
        @Provides
        @SessionDatabase
        @SessionScope
        fun providesRealmConfiguration(realmConfigurationFactory: SessionRealmConfigurationFactory): RealmConfiguration {
            return realmConfigurationFactory.create()
        }

        @JvmStatic
        @Provides
        @SessionDatabase
        @SessionScope
        fun providesMonarchy(@SessionDatabase realmConfiguration: RealmConfiguration): Monarchy {
            return Monarchy.Builder()
                    .setRealmConfiguration(realmConfiguration)
                    .build()
        }

        @JvmStatic
        @Provides
        @SessionScope
        @UnauthenticatedWithCertificate
        fun providesOkHttpClientWithCertificate(@Unauthenticated okHttpClient: OkHttpClient,
                                                homeServerConnectionConfig: HomeServerConnectionConfig): OkHttpClient {
            return okHttpClient
                    .newBuilder()
                    .addSocketFactory(homeServerConnectionConfig)
                    .build()
        }

        @JvmStatic
        @Provides
        @SessionScope
        @Authenticated
        fun providesOkHttpClient(@UnauthenticatedWithCertificate okHttpClient: OkHttpClient,
                                 @Authenticated accessTokenProvider: AccessTokenProvider,
                                 @SessionId sessionId: String,
                                 @MockHttpInterceptor testInterceptor: TestInterceptor?): OkHttpClient {
            return okHttpClient
                    .newBuilder()
                    .addAccessTokenInterceptor(accessTokenProvider)
                    .apply {
                        if (testInterceptor != null) {
                            testInterceptor.sessionId = sessionId
                            addInterceptor(testInterceptor)
                        }
                    }
                    .build()
        }

        @JvmStatic
        @Provides
        @SessionScope
        @UnauthenticatedWithCertificateWithProgress
        fun providesProgressOkHttpClient(@UnauthenticatedWithCertificate okHttpClient: OkHttpClient,
                                         downloadProgressInterceptor: DownloadProgressInterceptor): OkHttpClient {
            return okHttpClient.newBuilder()
                    .apply {
                        // Remove the previous CurlLoggingInterceptor, to add it after the accessTokenInterceptor
                        val existingCurlInterceptors = interceptors().filterIsInstance<CurlLoggingInterceptor>()
                        interceptors().removeAll(existingCurlInterceptors)

                        addInterceptor(downloadProgressInterceptor)

                        // Re add eventually the curl logging interceptors
                        existingCurlInterceptors.forEach {
                            addInterceptor(it)
                        }
                    }.build()
        }

        @JvmStatic
        @Provides
        @SessionScope
        fun providesRetrofit(@Authenticated okHttpClient: Lazy<OkHttpClient>,
                             sessionParams: SessionParams,
                             retrofitFactory: RetrofitFactory): Retrofit {
            return retrofitFactory
                    .create(okHttpClient, sessionParams.homeServerConnectionConfig.homeServerUri.toString())
        }

        @JvmStatic
        @Provides
        @SessionScope
        fun providesNetworkCallbackStrategy(fallbackNetworkCallbackStrategy: Provider<FallbackNetworkCallbackStrategy>,
                                            preferredNetworkCallbackStrategy: Provider<PreferredNetworkCallbackStrategy>
        ): NetworkCallbackStrategy {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                preferredNetworkCallbackStrategy.get()
            } else {
                fallbackNetworkCallbackStrategy.get()
            }
        }

        @JvmStatic
        @Provides
        @SessionScope
        fun providesMxCryptoConfig(matrixConfiguration: MatrixConfiguration): MXCryptoConfig {
            return matrixConfiguration.cryptoConfig
        }
    }

    @Binds
    @Authenticated
    abstract fun bindAccessTokenProvider(provider: HomeserverAccessTokenProvider): AccessTokenProvider

    @Binds
    abstract fun bindSession(session: DefaultSession): Session

    @Binds
    abstract fun bindGlobalErrorReceiver(handler: GlobalErrorHandler): GlobalErrorReceiver

    @Binds
    abstract fun bindNetworkConnectivityChecker(checker: DefaultNetworkConnectivityChecker): NetworkConnectivityChecker

    @Binds
    @IntoSet
    abstract fun bindEventRedactionProcessor(processor: RedactionEventProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindEventRelationsAggregationProcessor(processor: EventRelationsAggregationProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindRoomTombstoneEventProcessor(processor: RoomTombstoneEventProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindRoomCreateEventProcessor(processor: RoomCreateEventProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindVerificationMessageProcessor(processor: VerificationMessageProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindCallEventProcessor(processor: CallEventProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindEventInsertObserver(observer: EventInsertLiveObserver): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindIntegrationManager(manager: IntegrationManager): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindWidgetUrlFormatter(formatter: DefaultWidgetURLFormatter): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindIdentityService(service: DefaultIdentityService): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindDatabaseCleaner(cleaner: DatabaseCleaner): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindRealmSessionProvider(provider: RealmSessionProvider): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindSessionCoroutineScopeHolder(holder: SessionCoroutineScopeHolder): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindEventSenderProcessorAsSessionLifecycleObserver(processor: EventSenderProcessorCoroutine): SessionLifecycleObserver

    @Binds
    abstract fun bindInitialSyncProgressService(service: DefaultInitialSyncProgressService): InitialSyncProgressService

    @Binds
    abstract fun bindSecureStorageService(service: DefaultSecureStorageService): SecureStorageService

    @Binds
    abstract fun bindHomeServerCapabilitiesService(service: DefaultHomeServerCapabilitiesService): HomeServerCapabilitiesService

    @Binds
    abstract fun bindAccountDataService(service: UserAccountDataService): AccountDataService

    @Binds
    abstract fun bindEventService(service: DefaultEventService): EventService

    @Binds
    abstract fun bindSharedSecretStorageService(service: DefaultSharedSecretStorageService): SharedSecretStorageService

    @Binds
    abstract fun bindPermalinkService(service: DefaultPermalinkService): PermalinkService

    @Binds
    abstract fun bindOpenIdTokenService(service: DefaultOpenIdService): OpenIdService

    @Binds
    abstract fun bindTypingUsersTracker(tracker: DefaultTypingUsersTracker): TypingUsersTracker

    @Binds
    abstract fun bindRedactEventTask(task: DefaultRedactEventTask): RedactEventTask

    @Binds
    abstract fun bindEventSenderProcessor(processor: EventSenderProcessorCoroutine): EventSenderProcessor
}

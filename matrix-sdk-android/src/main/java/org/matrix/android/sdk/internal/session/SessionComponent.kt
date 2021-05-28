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

import dagger.BindsInstance
import dagger.Component
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.internal.crypto.CancelGossipRequestWorker
import org.matrix.android.sdk.internal.crypto.CryptoModule
import org.matrix.android.sdk.internal.crypto.SendGossipRequestWorker
import org.matrix.android.sdk.internal.crypto.SendGossipWorker
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustWorker
import org.matrix.android.sdk.internal.crypto.verification.SendVerificationMessageWorker
import org.matrix.android.sdk.internal.di.MatrixComponent
import org.matrix.android.sdk.internal.federation.FederationModule
import org.matrix.android.sdk.internal.network.NetworkConnectivityChecker
import org.matrix.android.sdk.internal.session.account.AccountModule
import org.matrix.android.sdk.internal.session.cache.CacheModule
import org.matrix.android.sdk.internal.session.call.CallModule
import org.matrix.android.sdk.internal.session.content.ContentModule
import org.matrix.android.sdk.internal.session.content.UploadContentWorker
import org.matrix.android.sdk.internal.session.filter.FilterModule
import org.matrix.android.sdk.internal.session.group.GetGroupDataWorker
import org.matrix.android.sdk.internal.session.group.GroupModule
import org.matrix.android.sdk.internal.session.homeserver.HomeServerCapabilitiesModule
import org.matrix.android.sdk.internal.session.identity.IdentityModule
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManagerModule
import org.matrix.android.sdk.internal.session.media.MediaModule
import org.matrix.android.sdk.internal.session.openid.OpenIdModule
import org.matrix.android.sdk.internal.session.profile.ProfileModule
import org.matrix.android.sdk.internal.session.pushers.AddHttpPusherWorker
import org.matrix.android.sdk.internal.session.pushers.PushersModule
import org.matrix.android.sdk.internal.session.room.RoomModule
import org.matrix.android.sdk.internal.session.room.relation.SendRelationWorker
import org.matrix.android.sdk.internal.session.room.send.MultipleEventSendingDispatcherWorker
import org.matrix.android.sdk.internal.session.room.send.RedactEventWorker
import org.matrix.android.sdk.internal.session.room.send.SendEventWorker
import org.matrix.android.sdk.internal.session.search.SearchModule
import org.matrix.android.sdk.internal.session.signout.SignOutModule
import org.matrix.android.sdk.internal.session.space.SpaceModule
import org.matrix.android.sdk.internal.session.sync.SyncModule
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.session.sync.job.SyncWorker
import org.matrix.android.sdk.internal.session.terms.TermsModule
import org.matrix.android.sdk.internal.session.thirdparty.ThirdPartyModule
import org.matrix.android.sdk.internal.session.user.UserModule
import org.matrix.android.sdk.internal.session.user.accountdata.AccountDataModule
import org.matrix.android.sdk.internal.session.widgets.WidgetModule
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.system.SystemModule

@Component(dependencies = [MatrixComponent::class],
        modules = [
            SessionModule::class,
            RoomModule::class,
            SyncModule::class,
            HomeServerCapabilitiesModule::class,
            SignOutModule::class,
            GroupModule::class,
            UserModule::class,
            FilterModule::class,
            GroupModule::class,
            ContentModule::class,
            CacheModule::class,
            MediaModule::class,
            CryptoModule::class,
            SystemModule::class,
            PushersModule::class,
            OpenIdModule::class,
            WidgetModule::class,
            IntegrationManagerModule::class,
            IdentityModule::class,
            TermsModule::class,
            AccountDataModule::class,
            ProfileModule::class,
            AccountModule::class,
            FederationModule::class,
            CallModule::class,
            SearchModule::class,
            ThirdPartyModule::class,
            SpaceModule::class
        ]
)
@SessionScope
internal interface SessionComponent {

    fun coroutineDispatchers(): MatrixCoroutineDispatchers

    fun session(): Session

    fun syncTask(): SyncTask

    fun syncTokenStore(): SyncTokenStore

    fun networkConnectivityChecker(): NetworkConnectivityChecker

    fun taskExecutor(): TaskExecutor

    fun inject(worker: SendEventWorker)

    fun inject(worker: SendRelationWorker)

    fun inject(worker: MultipleEventSendingDispatcherWorker)

    fun inject(worker: RedactEventWorker)

    fun inject(worker: GetGroupDataWorker)

    fun inject(worker: UploadContentWorker)

    fun inject(worker: SyncWorker)

    fun inject(worker: AddHttpPusherWorker)

    fun inject(worker: SendVerificationMessageWorker)

    fun inject(worker: SendGossipRequestWorker)

    fun inject(worker: CancelGossipRequestWorker)

    fun inject(worker: SendGossipWorker)

    fun inject(worker: UpdateTrustWorker)

    @Component.Factory
    interface Factory {
        fun create(
                matrixComponent: MatrixComponent,
                @BindsInstance sessionParams: SessionParams): SessionComponent
    }
}

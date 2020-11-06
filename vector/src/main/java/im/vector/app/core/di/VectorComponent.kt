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

package im.vector.app.core.di

import android.content.Context
import android.content.res.Resources
import dagger.BindsInstance
import dagger.Component
import im.vector.app.ActiveSessionDataSource
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.EmojiCompatWrapper
import im.vector.app.VectorApplication
import im.vector.app.core.dialogs.UnrecognizedCertificateDialog
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.utils.AssetReader
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.call.WebRtcPeerConnectionManager
import im.vector.app.features.configuration.VectorConfiguration
import im.vector.app.features.crypto.keysrequest.KeyRequestHandler
import im.vector.app.features.crypto.verification.IncomingVerificationRequestHandler
import im.vector.app.features.grouplist.SelectedGroupDataSource
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.HomeRoomListDataSource
import im.vector.app.features.home.room.detail.RoomDetailPendingActionStore
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.login.ReAuthHelper
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.notifications.NotifiableEventResolver
import im.vector.app.features.notifications.NotificationBroadcastReceiver
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.notifications.PushRuleTriggerListener
import im.vector.app.features.pin.PinCodeStore
import im.vector.app.features.pin.PinLocker
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.rageshake.BugReporter
import im.vector.app.features.rageshake.VectorFileLogger
import im.vector.app.features.rageshake.VectorUncaughtExceptionHandler
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.session.SessionListener
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.ui.UiStateRepository
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import javax.inject.Singleton

@Component(modules = [VectorModule::class])
@Singleton
interface VectorComponent {

    fun inject(notificationBroadcastReceiver: NotificationBroadcastReceiver)

    fun inject(vectorApplication: VectorApplication)

    fun matrix(): Matrix

    fun matrixItemColorProvider(): MatrixItemColorProvider

    fun sessionListener(): SessionListener

    fun currentSession(): Session

    fun notificationUtils(): NotificationUtils

    fun notificationDrawerManager(): NotificationDrawerManager

    fun appContext(): Context

    fun resources(): Resources

    fun assetReader(): AssetReader

    fun dimensionConverter(): DimensionConverter

    fun vectorConfiguration(): VectorConfiguration

    fun avatarRenderer(): AvatarRenderer

    fun activeSessionHolder(): ActiveSessionHolder

    fun unrecognizedCertificateDialog(): UnrecognizedCertificateDialog

    fun emojiCompatFontProvider(): EmojiCompatFontProvider

    fun emojiCompatWrapper(): EmojiCompatWrapper

    fun eventHtmlRenderer(): EventHtmlRenderer

    fun vectorHtmlCompressor(): VectorHtmlCompressor

    fun navigator(): Navigator

    fun errorFormatter(): ErrorFormatter

    fun homeRoomListObservableStore(): HomeRoomListDataSource

    fun selectedGroupStore(): SelectedGroupDataSource

    fun roomDetailPendingActionStore(): RoomDetailPendingActionStore

    fun activeSessionObservableStore(): ActiveSessionDataSource

    fun incomingVerificationRequestHandler(): IncomingVerificationRequestHandler

    fun incomingKeyRequestHandler(): KeyRequestHandler

    fun authenticationService(): AuthenticationService

    fun rawService(): RawService

    fun bugReporter(): BugReporter

    fun vectorUncaughtExceptionHandler(): VectorUncaughtExceptionHandler

    fun pushRuleTriggerListener(): PushRuleTriggerListener

    fun pusherManager(): PushersManager

    fun notifiableEventResolver(): NotifiableEventResolver

    fun vectorPreferences(): VectorPreferences

    fun vectorFileLogger(): VectorFileLogger

    fun uiStateRepository(): UiStateRepository

    fun pinCodeStore(): PinCodeStore

    fun emojiDataSource(): EmojiDataSource

    fun alertManager(): PopupAlertManager

    fun reAuthHelper(): ReAuthHelper

    fun analyticsEngine(): AnalyticsEngine

    fun pinLocker(): PinLocker

    fun webRtcPeerConnectionManager(): WebRtcPeerConnectionManager

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance context: Context): VectorComponent
    }
}

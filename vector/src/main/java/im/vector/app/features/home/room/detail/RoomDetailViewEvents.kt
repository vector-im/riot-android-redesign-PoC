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

package im.vector.app.features.home.room.detail

import android.net.Uri
import androidx.annotation.StringRes
import im.vector.app.core.platform.VectorViewEvents
import im.vector.app.features.command.Command
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.internal.crypto.model.event.WithHeldCode
import java.io.File

/**
 * Transient events for RoomDetail
 */
sealed class RoomDetailViewEvents : VectorViewEvents {
    data class Failure(val throwable: Throwable) : RoomDetailViewEvents()
    data class OnNewTimelineEvents(val eventIds: List<String>) : RoomDetailViewEvents()

    data class ActionSuccess(val action: RoomDetailAction) : RoomDetailViewEvents()
    data class ActionFailure(val action: RoomDetailAction, val throwable: Throwable) : RoomDetailViewEvents()

    data class ShowMessage(val message: String) : RoomDetailViewEvents()
    data class ShowInfoOkDialog(val message: String) : RoomDetailViewEvents()
    data class ShowE2EErrorMessage(val withHeldCode: WithHeldCode?) : RoomDetailViewEvents()

    data class NavigateToEvent(val eventId: String) : RoomDetailViewEvents()
    data class JoinJitsiConference(val widget: Widget, val withVideo: Boolean) : RoomDetailViewEvents()

    object ShowWaitingView: RoomDetailViewEvents()
    object HideWaitingView: RoomDetailViewEvents()

    data class FileTooBigError(
            val filename: String,
            val fileSizeInBytes: Long,
            val homeServerLimitInBytes: Long
    ) : RoomDetailViewEvents()

    data class DownloadFileState(
            val mimeType: String?,
            val file: File?,
            val throwable: Throwable?
    ) : RoomDetailViewEvents()

    data class OpenFile(
            val mimeType: String?,
            val uri: Uri?,
            val throwable: Throwable?
    ) : RoomDetailViewEvents()

    abstract class SendMessageResult : RoomDetailViewEvents()

    object DisplayPromptForIntegrationManager : RoomDetailViewEvents()

    object DisplayEnableIntegrationsWarning : RoomDetailViewEvents()

    data class OpenRoomMemberProfile(val userId: String) : RoomDetailViewEvents()

    data class OpenStickerPicker(val widget: Widget) : RoomDetailViewEvents()

    object OpenIntegrationManager : RoomDetailViewEvents()
    object OpenActiveWidgetBottomSheet : RoomDetailViewEvents()
    data class RequestNativeWidgetPermission(val widget: Widget,
                                             val domain: String,
                                             val grantedEvents: RoomDetailViewEvents) : RoomDetailViewEvents()

    object MessageSent : SendMessageResult()
    data class JoinRoomCommandSuccess(val roomId: String) : SendMessageResult()
    class SlashCommandError(val command: Command) : SendMessageResult()
    class SlashCommandUnknown(val command: String) : SendMessageResult()
    data class SlashCommandHandled(@StringRes val messageRes: Int? = null) : SendMessageResult()
    object SlashCommandResultOk : SendMessageResult()
    class SlashCommandResultError(val throwable: Throwable) : SendMessageResult()
    // TODO Remove
    object SlashCommandNotImplemented : SendMessageResult()
}

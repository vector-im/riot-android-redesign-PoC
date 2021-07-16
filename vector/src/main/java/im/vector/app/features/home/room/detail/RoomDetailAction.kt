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
import android.view.View
import im.vector.app.core.platform.VectorViewModelAction
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.api.util.MatrixItem

sealed class RoomDetailAction : VectorViewModelAction {
    data class UserIsTyping(val isTyping: Boolean) : RoomDetailAction()
    data class SaveDraft(val draft: String) : RoomDetailAction()
    data class SendSticker(val stickerContent: MessageStickerContent) : RoomDetailAction()
    data class SendMessage(val text: CharSequence, val autoMarkdown: Boolean) : RoomDetailAction()
    data class SendMedia(val attachments: List<ContentAttachmentData>, val compressBeforeSending: Boolean) : RoomDetailAction()
    data class TimelineEventTurnsVisible(val event: TimelineEvent) : RoomDetailAction()
    data class TimelineEventTurnsInvisible(val event: TimelineEvent) : RoomDetailAction()
    data class LoadMoreTimelineEvents(val direction: Timeline.Direction) : RoomDetailAction()
    data class SendReaction(val targetEventId: String, val reaction: String) : RoomDetailAction()
    data class UndoReaction(val targetEventId: String, val reaction: String, val reason: String? = "") : RoomDetailAction()
    data class RedactAction(val targetEventId: String, val reason: String? = "") : RoomDetailAction()
    data class UpdateQuickReactAction(val targetEventId: String, val selectedReaction: String, val add: Boolean) : RoomDetailAction()
    data class NavigateToEvent(val eventId: String, val highlight: Boolean) : RoomDetailAction()
    object MarkAllAsRead : RoomDetailAction()
    data class DownloadOrOpen(val eventId: String, val senderId: String?, val messageFileContent: MessageWithAttachmentContent) : RoomDetailAction()
    object JoinAndOpenReplacementRoom : RoomDetailAction()
    object AcceptInvite : RoomDetailAction()
    object RejectInvite : RoomDetailAction()

    object EnterTrackingUnreadMessagesState : RoomDetailAction()
    object ExitTrackingUnreadMessagesState : RoomDetailAction()

    data class EnterEditMode(val eventId: String, val text: String) : RoomDetailAction()
    data class EnterQuoteMode(val eventId: String, val text: String) : RoomDetailAction()
    data class EnterReplyMode(val eventId: String, val text: String) : RoomDetailAction()
    data class EnterRegularMode(val text: String, val fromSharing: Boolean) : RoomDetailAction()

    data class ResendMessage(val eventId: String) : RoomDetailAction()
    data class RemoveFailedEcho(val eventId: String) : RoomDetailAction()
    data class CancelSend(val eventId: String, val force: Boolean) : RoomDetailAction()

    data class ReplyToOptions(val eventId: String, val optionIndex: Int, val optionValue: String) : RoomDetailAction()

    data class ReportContent(
            val eventId: String,
            val senderId: String?,
            val reason: String,
            val spam: Boolean = false,
            val inappropriate: Boolean = false) : RoomDetailAction()

    data class IgnoreUser(val userId: String?) : RoomDetailAction()

    object ResendAll : RoomDetailAction()

    data class StartCall(val isVideo: Boolean) : RoomDetailAction()
    data class AcceptCall(val callId: String): RoomDetailAction()
    object EndCall : RoomDetailAction()

    data class AcceptVerificationRequest(val transactionId: String, val otherUserId: String) : RoomDetailAction()
    data class DeclineVerificationRequest(val transactionId: String, val otherUserId: String) : RoomDetailAction()
    data class RequestVerification(val userId: String) : RoomDetailAction()
    data class ResumeVerification(val transactionId: String, val otherUserId: String?) : RoomDetailAction()
    data class TapOnFailedToDecrypt(val eventId: String) : RoomDetailAction()
    data class ReRequestKeys(val eventId: String) : RoomDetailAction()

    object SelectStickerAttachment : RoomDetailAction()
    object OpenIntegrationManager : RoomDetailAction()
    object ManageIntegrations : RoomDetailAction()
    data class AddJitsiWidget(val withVideo: Boolean) : RoomDetailAction()
    data class RemoveWidget(val widgetId: String) : RoomDetailAction()
    data class EnsureNativeWidgetAllowed(val widget: Widget,
                                         val userJustAccepted: Boolean,
                                         val grantedEvents: RoomDetailViewEvents) : RoomDetailAction()

    data class OpenOrCreateDm(val userId: String) : RoomDetailAction()
    data class JumpToReadReceipt(val userId: String) : RoomDetailAction()
    object QuickActionInvitePeople : RoomDetailAction()
    object QuickActionSetAvatar : RoomDetailAction()
    data class SetAvatarAction(val newAvatarUri: Uri, val newAvatarFileName: String) : RoomDetailAction()
    object QuickActionSetTopic : RoomDetailAction()
    data class ShowRoomAvatarFullScreen(val matrixItem: MatrixItem?, val transitionView: View?) : RoomDetailAction()

    // Preview URL
    data class DoNotShowPreviewUrlFor(val eventId: String, val url: String) : RoomDetailAction()

    data class ComposerFocusChange(val focused: Boolean) : RoomDetailAction()

    // Failed messages
    object RemoveAllFailedMessages : RoomDetailAction()

    data class RoomUpgradeSuccess(val replacementRoomId: String): RoomDetailAction()

    // Voice Message
    object StartRecordingVoiceMessage : RoomDetailAction()
    data class EndRecordingVoiceMessage(val isCancelled: Boolean) : RoomDetailAction()
    object PauseRecordingVoiceMessage : RoomDetailAction()
    data class PlayOrPauseVoicePlayback(val eventId: String, val messageAudioContent: MessageAudioContent) : RoomDetailAction()
    object PlayOrPauseRecordingPlayback : RoomDetailAction()
    object EndAllVoiceActions : RoomDetailAction()
}

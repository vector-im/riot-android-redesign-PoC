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
import androidx.annotation.IdRes
import androidx.lifecycle.viewModelScope
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.ViewModelContext
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.core.utils.subscribeLogError
import im.vector.app.features.call.WebRtcPeerConnectionManager
import im.vector.app.features.command.CommandParser
import im.vector.app.features.command.ParsedCommand
import im.vector.app.features.crypto.verification.SupportedVerificationMethodsProvider
import im.vector.app.features.home.room.detail.composer.rainbow.RainbowGenerator
import im.vector.app.features.home.room.detail.sticker.StickerPickerActionHandler
import im.vector.app.features.home.room.detail.timeline.helper.RoomSummaryHolder
import im.vector.app.features.home.room.detail.timeline.helper.TimelineSettingsFactory
import im.vector.app.features.home.room.typing.TypingHelper
import im.vector.app.features.powerlevel.PowerLevelsObservableFactory
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.settings.VectorLocale
import im.vector.app.features.settings.VectorPreferences
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.NoOpMatrixCallback
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.OptionItem
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.tombstone.RoomTombstoneContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getTextEditableContent
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.internal.crypto.model.event.EncryptedEventContent
import org.matrix.android.sdk.internal.crypto.model.event.WithHeldCode
import org.matrix.android.sdk.internal.util.awaitCallback
import org.matrix.android.sdk.rx.rx
import org.matrix.android.sdk.rx.unwrap
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RoomDetailViewModel @AssistedInject constructor(
        @Assisted private val initialState: RoomDetailViewState,
        private val vectorPreferences: VectorPreferences,
        private val stringProvider: StringProvider,
        private val rainbowGenerator: RainbowGenerator,
        private val session: Session,
        private val rawService: RawService,
        private val supportedVerificationMethodsProvider: SupportedVerificationMethodsProvider,
        private val stickerPickerActionHandler: StickerPickerActionHandler,
        private val roomSummaryHolder: RoomSummaryHolder,
        private val typingHelper: TypingHelper,
        private val webRtcPeerConnectionManager: WebRtcPeerConnectionManager,
        timelineSettingsFactory: TimelineSettingsFactory,
        private val analyticsEngine: AnalyticsEngine
) : VectorViewModel<RoomDetailViewState, RoomDetailAction, RoomDetailViewEvents>(initialState), Timeline.Listener {

    private val room = session.getRoom(initialState.roomId)!!
    private val eventId = initialState.eventId
    private val invisibleEventsObservable = BehaviorRelay.create<RoomDetailAction.TimelineEventTurnsInvisible>()
    private val visibleEventsObservable = BehaviorRelay.create<RoomDetailAction.TimelineEventTurnsVisible>()
    private val timelineSettings = timelineSettingsFactory.create()
    private var timelineEvents = PublishRelay.create<List<TimelineEvent>>()
    val timeline = room.createTimeline(eventId, timelineSettings)

    // Slot to keep a pending action during permission request
    var pendingAction: RoomDetailAction? = null

    // Slot to keep a pending uri during permission request
    var pendingUri: Uri? = null

    // Slot to store if we want to prevent preview of attachment
    var preventAttachmentPreview = false

    private var trackUnreadMessages = AtomicBoolean(false)
    private var mostRecentDisplayedEvent: TimelineEvent? = null

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: RoomDetailViewState): RoomDetailViewModel
    }

    companion object : MvRxViewModelFactory<RoomDetailViewModel, RoomDetailViewState> {

        const val PAGINATION_COUNT = 50

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: RoomDetailViewState): RoomDetailViewModel? {
            val fragment: RoomDetailFragment = (viewModelContext as FragmentViewModelContext).fragment()

            return fragment.roomDetailViewModelFactory.create(state)
        }
    }

    init {
        timeline.start()
        timeline.addListener(this)
        observeRoomSummary()
        observeMembershipChanges()
        observeSummaryState()
        getUnreadState()
        observeSyncState()
        observeEventDisplayedActions()
        getDraftIfAny()
        observeUnreadState()
        observeMyRoomMember()
        observeActiveRoomWidgets()
        observePowerLevel()
        room.getRoomSummaryLive()
        room.markAsRead(ReadService.MarkAsReadParams.READ_RECEIPT, NoOpMatrixCallback())
        room.rx().loadRoomMembersIfNeeded().subscribeLogError().disposeOnClear()
        // Inform the SDK that the room is displayed
        session.onRoomDisplayed(initialState.roomId)
    }

    private fun observePowerLevel() {
        PowerLevelsObservableFactory(room).createObservable()
                .subscribe {
                    val canSendMessage = PowerLevelsHelper(it).isUserAllowedToSend(session.myUserId, false, EventType.MESSAGE)
                    val canInvite = PowerLevelsHelper(it).isUserAbleToInvite(session.myUserId)
                    val isAllowedToManageWidgets = session.widgetService().hasPermissionsToHandleWidgets(room.roomId)
                    val isAllowedToStartWebRTCCall = PowerLevelsHelper(it).isUserAllowedToSend(session.myUserId, false, EventType.CALL_INVITE)
                    setState {
                        copy(
                                canSendMessage = canSendMessage,
                                canInvite = canInvite,
                                isAllowedToManageWidgets = isAllowedToManageWidgets,
                                isAllowedToStartWebRTCCall = isAllowedToStartWebRTCCall
                        )
                    }
                }
                .disposeOnClear()
    }

    private fun observeActiveRoomWidgets() {
        session.rx()
                .liveRoomWidgets(
                        roomId = initialState.roomId,
                        widgetId = QueryStringValue.NoCondition
                )
                .map { widgets ->
                    widgets.filter { it.isActive }
                }
                .execute {
                    copy(activeRoomWidgets = it)
                }
    }

    private fun observeMyRoomMember() {
        val queryParams = roomMemberQueryParams {
            this.userId = QueryStringValue.Equals(session.myUserId, QueryStringValue.Case.SENSITIVE)
        }
        room.rx()
                .liveRoomMembers(queryParams)
                .map {
                    it.firstOrNull().toOptional()
                }
                .unwrap()
                .execute {
                    copy(myRoomMember = it)
                }
    }

    fun getOtherUserIds() = room.roomSummary()?.otherMemberIds

    override fun handle(action: RoomDetailAction) {
        when (action) {
            is RoomDetailAction.UserIsTyping                     -> handleUserIsTyping(action)
            is RoomDetailAction.SaveDraft                        -> handleSaveDraft(action)
            is RoomDetailAction.SendMessage                      -> handleSendMessage(action)
            is RoomDetailAction.SendMedia                        -> handleSendMedia(action)
            is RoomDetailAction.SendSticker                      -> handleSendSticker(action)
            is RoomDetailAction.TimelineEventTurnsVisible        -> handleEventVisible(action)
            is RoomDetailAction.TimelineEventTurnsInvisible      -> handleEventInvisible(action)
            is RoomDetailAction.LoadMoreTimelineEvents           -> handleLoadMore(action)
            is RoomDetailAction.SendReaction                     -> handleSendReaction(action)
            is RoomDetailAction.AcceptInvite                     -> handleAcceptInvite()
            is RoomDetailAction.RejectInvite                     -> handleRejectInvite()
            is RoomDetailAction.RedactAction                     -> handleRedactEvent(action)
            is RoomDetailAction.UndoReaction                     -> handleUndoReact(action)
            is RoomDetailAction.UpdateQuickReactAction           -> handleUpdateQuickReaction(action)
            is RoomDetailAction.EnterRegularMode                 -> handleEnterRegularMode(action)
            is RoomDetailAction.EnterEditMode                    -> handleEditAction(action)
            is RoomDetailAction.EnterQuoteMode                   -> handleQuoteAction(action)
            is RoomDetailAction.EnterReplyMode                   -> handleReplyAction(action)
            is RoomDetailAction.DownloadOrOpen                   -> handleOpenOrDownloadFile(action)
            is RoomDetailAction.NavigateToEvent                  -> handleNavigateToEvent(action)
            is RoomDetailAction.HandleTombstoneEvent             -> handleTombstoneEvent(action)
            is RoomDetailAction.ResendMessage                    -> handleResendEvent(action)
            is RoomDetailAction.RemoveFailedEcho                 -> handleRemove(action)
            is RoomDetailAction.ResendAll                        -> handleResendAll()
            is RoomDetailAction.MarkAllAsRead                    -> handleMarkAllAsRead()
            is RoomDetailAction.ReportContent                    -> handleReportContent(action)
            is RoomDetailAction.IgnoreUser                       -> handleIgnoreUser(action)
            is RoomDetailAction.EnterTrackingUnreadMessagesState -> startTrackingUnreadMessages()
            is RoomDetailAction.ExitTrackingUnreadMessagesState  -> stopTrackingUnreadMessages()
            is RoomDetailAction.ReplyToOptions                   -> handleReplyToOptions(action)
            is RoomDetailAction.AcceptVerificationRequest        -> handleAcceptVerification(action)
            is RoomDetailAction.DeclineVerificationRequest       -> handleDeclineVerification(action)
            is RoomDetailAction.RequestVerification              -> handleRequestVerification(action)
            is RoomDetailAction.ResumeVerification               -> handleResumeRequestVerification(action)
            is RoomDetailAction.ReRequestKeys                    -> handleReRequestKeys(action)
            is RoomDetailAction.TapOnFailedToDecrypt             -> handleTapOnFailedToDecrypt(action)
            is RoomDetailAction.SelectStickerAttachment          -> handleSelectStickerAttachment()
            is RoomDetailAction.OpenIntegrationManager           -> handleOpenIntegrationManager()
            is RoomDetailAction.StartCall                        -> handleStartCall(action)
            is RoomDetailAction.EndCall                          -> handleEndCall()
            is RoomDetailAction.ManageIntegrations               -> handleManageIntegrations()
            is RoomDetailAction.AddJitsiWidget                   -> handleAddJitsiConference(action)
            is RoomDetailAction.RemoveWidget                     -> handleDeleteWidget(action.widgetId)
            is RoomDetailAction.EnsureNativeWidgetAllowed        -> handleCheckWidgetAllowed(action)
            is RoomDetailAction.CancelSend                       -> handleCancel(action)
            is RoomDetailAction.OpenOrCreateDm                   -> handleOpenOrCreateDm(action)
            is RoomDetailAction.JumpToReadReceipt                -> handleJumpToReadReceipt(action)
            RoomDetailAction.ReportView                          -> handleReportView()
        }.exhaustive
    }

    private fun handleOpenOrCreateDm(action: RoomDetailAction.OpenOrCreateDm) {
        val existingDmRoomId = session.getExistingDirectRoomWithUser(action.userId)
        if (existingDmRoomId == null) {
            // First create a direct room
            viewModelScope.launch(Dispatchers.IO) {
                val roomId = awaitCallback<String> {
                    session.createDirectRoom(action.userId, it)
                }
                _viewEvents.post(RoomDetailViewEvents.OpenRoom(roomId))
            }
        } else {
            if (existingDmRoomId != initialState.roomId) {
                _viewEvents.post(RoomDetailViewEvents.OpenRoom(existingDmRoomId))
            }
        }
    }

    private fun handleReportView() {
        viewModelScope.launch {
            val roomSummary = room.roomSummary()
            val joinRules = room.getStateEvent(EventType.STATE_ROOM_JOIN_RULES, QueryStringValue.IsNotEmpty)
                    ?.content.toModel<RoomJoinRulesContent>()
                    ?.joinRules
            analyticsEngine.report(
                    AnalyticsEngine.AnalyticEvent.RoomView(
                            roomId = room.roomId,
                            isEncrypted = roomSummary?.isEncrypted ?: false,
                            memberCount = roomSummary?.joinedMembersCount ?: 0,
                            isPublic = joinRules == RoomJoinRules.PUBLIC
                    )
            )
        }
    }

    private fun handleJumpToReadReceipt(action: RoomDetailAction.JumpToReadReceipt) {
        room.getUserReadReceipt(action.userId)
                ?.let { handleNavigateToEvent(RoomDetailAction.NavigateToEvent(it, true)) }
    }

    private fun handleSendSticker(action: RoomDetailAction.SendSticker) {
        room.sendEvent(EventType.STICKER, action.stickerContent.toContent())
    }

    private fun handleStartCall(action: RoomDetailAction.StartCall) {
        room.roomSummary()?.otherMemberIds?.firstOrNull()?.let {
            webRtcPeerConnectionManager.startOutgoingCall(room.roomId, it, action.isVideo)
        }
        viewModelScope.launch {
            analyticsEngine.report(AnalyticsEngine.AnalyticEvent.StartCall(
                    roomId = room.roomId,
                    isVideo = action.isVideo
            ))
        }
    }

    private fun handleEndCall() {
        webRtcPeerConnectionManager.endCall()
    }

    private fun handleSelectStickerAttachment() {
        viewModelScope.launch {
            val viewEvent = stickerPickerActionHandler.handle()
            _viewEvents.post(viewEvent)
        }
    }

    private fun handleOpenIntegrationManager() {
        viewModelScope.launch {
            val viewEvent = withContext(Dispatchers.Default) {
                if (isIntegrationEnabled()) {
                    RoomDetailViewEvents.OpenIntegrationManager
                } else {
                    RoomDetailViewEvents.DisplayEnableIntegrationsWarning
                }
            }
            _viewEvents.post(viewEvent)
        }
    }

    private fun handleManageIntegrations() = withState { state ->
        if (state.activeRoomWidgets().isNullOrEmpty()) {
            // Directly open integration manager screen
            handleOpenIntegrationManager()
        } else {
            // Display bottomsheet with widget list
            _viewEvents.post(RoomDetailViewEvents.OpenActiveWidgetBottomSheet)
        }
    }

    private fun handleAddJitsiConference(action: RoomDetailAction.AddJitsiWidget) {
        _viewEvents.post(RoomDetailViewEvents.ShowWaitingView)
        viewModelScope.launch(Dispatchers.IO) {
            // Build data for a jitsi widget
            val widgetId: String = WidgetType.Jitsi.preferred + "_" + session.myUserId + "_" + System.currentTimeMillis()

            // Create a random enough jitsi conference id
            // Note: the jitsi server automatically creates conference when the conference
            // id does not exist yet
            var widgetSessionId = UUID.randomUUID().toString()

            if (widgetSessionId.length > 8) {
                widgetSessionId = widgetSessionId.substring(0, 7)
            }
            val roomId: String = room.roomId
            val confId = roomId.substring(1, roomId.indexOf(":") - 1) + widgetSessionId.toLowerCase(VectorLocale.applicationLocale)

            val preferredJitsiDomain = tryOrNull {
                rawService.getElementWellknown(session.myUserId)
                        ?.jitsiServer
                        ?.preferredDomain
            }
            val jitsiDomain = preferredJitsiDomain ?: stringProvider.getString(R.string.preferred_jitsi_domain)

            // We use the default element wrapper for this widget
            // https://github.com/vector-im/element-web/blob/develop/docs/jitsi-dev.md
            val url = "https://app.element.io/jitsi.html" +
                    "?confId=$confId" +
                    "#conferenceDomain=\$domain" +
                    "&conferenceId=\$conferenceId" +
                    "&isAudioOnly=${!action.withVideo}" +
                    "&displayName=\$matrix_display_name" +
                    "&avatarUrl=\$matrix_avatar_url" +
                    "&userId=\$matrix_user_id"

            val widgetEventContent = mapOf(
                    "url" to url,
                    "type" to WidgetType.Jitsi.legacy,
                    "data" to mapOf(
                            "conferenceId" to confId,
                            "domain" to jitsiDomain,
                            "isAudioOnly" to !action.withVideo
                    ),
                    "creatorUserId" to session.myUserId,
                    "id" to widgetId,
                    "name" to "jitsi"
            )

            try {
                val widget = awaitCallback<Widget> {
                    session.widgetService().createRoomWidget(roomId, widgetId, widgetEventContent, it)
                }
                _viewEvents.post(RoomDetailViewEvents.JoinJitsiConference(widget, action.withVideo))
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.failed_to_add_widget)))
            } finally {
                _viewEvents.post(RoomDetailViewEvents.HideWaitingView)
            }
        }

        viewModelScope.launch {
            analyticsEngine.report(
                    AnalyticsEngine.AnalyticEvent.StartJitsiConf(
                            roomId = room.roomId,
                            isVideo = action.withVideo
                    )
            )
        }
    }

    private fun handleDeleteWidget(widgetId: String) {
        _viewEvents.post(RoomDetailViewEvents.ShowWaitingView)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                awaitCallback<Unit> { session.widgetService().destroyRoomWidget(room.roomId, widgetId, it) }
                // local echo
                setState {
                    copy(
                            activeRoomWidgets = when (activeRoomWidgets) {
                                is Success -> {
                                    Success(activeRoomWidgets.invoke().filter { it.widgetId != widgetId })
                                }
                                else       -> activeRoomWidgets
                            }
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.failed_to_remove_widget)))
            } finally {
                _viewEvents.post(RoomDetailViewEvents.HideWaitingView)
            }
        }
    }

    private fun handleCheckWidgetAllowed(action: RoomDetailAction.EnsureNativeWidgetAllowed) {
        val widget = action.widget
        val domain = action.widget.widgetContent.data["domain"] as? String ?: ""
        val isAllowed = action.userJustAccepted || if (widget.type == WidgetType.Jitsi) {
            widget.senderInfo?.userId == session.myUserId
                    || session.integrationManagerService().isNativeWidgetDomainAllowed(
                    action.widget.type.preferred,
                    domain
            )
        } else false

        if (isAllowed) {
            _viewEvents.post(action.grantedEvents)
        } else {
            // we need to request permission
            _viewEvents.post(RoomDetailViewEvents.RequestNativeWidgetPermission(widget, domain, action.grantedEvents))
        }
    }

    private fun startTrackingUnreadMessages() {
        trackUnreadMessages.set(true)
        setState { copy(canShowJumpToReadMarker = false) }
    }

    private fun stopTrackingUnreadMessages() {
        if (trackUnreadMessages.getAndSet(false)) {
            mostRecentDisplayedEvent?.root?.eventId?.also {
                room.setReadMarker(it, callback = NoOpMatrixCallback())
            }
            mostRecentDisplayedEvent = null
        }
        setState { copy(canShowJumpToReadMarker = true) }
    }

    private fun handleEventInvisible(action: RoomDetailAction.TimelineEventTurnsInvisible) {
        invisibleEventsObservable.accept(action)
    }

    fun getMember(userId: String): RoomMemberSummary? {
        return room.getRoomMember(userId)
    }

    /**
     * Convert a send mode to a draft and save the draft
     */
    private fun handleSaveDraft(action: RoomDetailAction.SaveDraft) = withState {
        when {
            it.sendMode is SendMode.REGULAR && !it.sendMode.fromSharing -> {
                setState { copy(sendMode = it.sendMode.copy(action.draft)) }
                room.saveDraft(UserDraft.REGULAR(action.draft), NoOpMatrixCallback())
            }
            it.sendMode is SendMode.REPLY                               -> {
                setState { copy(sendMode = it.sendMode.copy(text = action.draft)) }
                room.saveDraft(UserDraft.REPLY(it.sendMode.timelineEvent.root.eventId!!, action.draft), NoOpMatrixCallback())
            }
            it.sendMode is SendMode.QUOTE                               -> {
                setState { copy(sendMode = it.sendMode.copy(text = action.draft)) }
                room.saveDraft(UserDraft.QUOTE(it.sendMode.timelineEvent.root.eventId!!, action.draft), NoOpMatrixCallback())
            }
            it.sendMode is SendMode.EDIT                                -> {
                setState { copy(sendMode = it.sendMode.copy(text = action.draft)) }
                room.saveDraft(UserDraft.EDIT(it.sendMode.timelineEvent.root.eventId!!, action.draft), NoOpMatrixCallback())
            }
        }
    }

    private fun getDraftIfAny() {
        val currentDraft = room.getDraft() ?: return
        setState {
            copy(
                    // Create a sendMode from a draft and retrieve the TimelineEvent
                    sendMode = when (currentDraft) {
                        is UserDraft.REGULAR -> SendMode.REGULAR(currentDraft.text, false)
                        is UserDraft.QUOTE   -> {
                            room.getTimeLineEvent(currentDraft.linkedEventId)?.let { timelineEvent ->
                                SendMode.QUOTE(timelineEvent, currentDraft.text)
                            }
                        }
                        is UserDraft.REPLY   -> {
                            room.getTimeLineEvent(currentDraft.linkedEventId)?.let { timelineEvent ->
                                SendMode.REPLY(timelineEvent, currentDraft.text)
                            }
                        }
                        is UserDraft.EDIT    -> {
                            room.getTimeLineEvent(currentDraft.linkedEventId)?.let { timelineEvent ->
                                SendMode.EDIT(timelineEvent, currentDraft.text)
                            }
                        }
                    } ?: SendMode.REGULAR("", fromSharing = false)
            )
        }
    }

    private fun handleUserIsTyping(action: RoomDetailAction.UserIsTyping) {
        if (vectorPreferences.sendTypingNotifs()) {
            if (action.isTyping) {
                room.userIsTyping()
            } else {
                room.userStopsTyping()
            }
        }
    }

    private fun handleTombstoneEvent(action: RoomDetailAction.HandleTombstoneEvent) {
        val tombstoneContent = action.event.getClearContent().toModel<RoomTombstoneContent>() ?: return

        val roomId = tombstoneContent.replacementRoomId ?: ""
        val isRoomJoined = session.getRoom(roomId)?.roomSummary()?.membership == Membership.JOIN
        if (isRoomJoined) {
            setState { copy(tombstoneEventHandling = Success(roomId)) }
        } else {
            val viaServers = MatrixPatterns.extractServerNameFromId(action.event.senderId)
                    ?.let { listOf(it) }
                    .orEmpty()
            session.rx()
                    .joinRoom(roomId, viaServers = viaServers)
                    .map { roomId }
                    .execute {
                        copy(tombstoneEventHandling = it)
                    }
        }
    }

    private fun isIntegrationEnabled() = session.integrationManagerService().isIntegrationEnabled()

    fun isMenuItemVisible(@IdRes itemId: Int): Boolean = com.airbnb.mvrx.withState(this) { state ->
        if (state.asyncRoomSummary()?.membership != Membership.JOIN) {
            return@withState false
        }
        when (itemId) {
            R.id.resend_all       -> state.asyncRoomSummary()?.hasFailedSending == true
            R.id.timeline_setting -> true
            R.id.invite           -> state.canInvite
            R.id.clear_all        -> state.asyncRoomSummary()?.hasFailedSending == true
            R.id.open_matrix_apps -> true
            R.id.voice_call,
            R.id.video_call       -> true // always show for discoverability
            R.id.hangup_call      -> webRtcPeerConnectionManager.currentCall != null
            R.id.search           -> true
            else                  -> false
        }
    }

// PRIVATE METHODS *****************************************************************************

    private fun handleSendMessage(action: RoomDetailAction.SendMessage) {
        withState { state ->
            when (state.sendMode) {
                is SendMode.REGULAR -> {
                    when (val slashCommandResult = CommandParser.parseSplashCommand(action.text)) {
                        is ParsedCommand.ErrorNotACommand         -> {
                            // Send the text message to the room
                            room.sendTextMessage(action.text, autoMarkdown = action.autoMarkdown)
                            viewModelScope.launch {
                                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendText(room.roomId))
                            }
                            _viewEvents.post(RoomDetailViewEvents.MessageSent)
                            popDraft()
                        }
                        is ParsedCommand.ErrorSyntax              -> {
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandError(slashCommandResult.command))
                        }
                        is ParsedCommand.ErrorEmptySlashCommand   -> {
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandUnknown("/"))
                        }
                        is ParsedCommand.ErrorUnknownSlashCommand -> {
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandUnknown(slashCommandResult.slashCommand))
                        }
                        is ParsedCommand.SendPlainText            -> {
                            // Send the text message to the room, without markdown
                            room.sendTextMessage(slashCommandResult.message, autoMarkdown = false)
                            _viewEvents.post(RoomDetailViewEvents.MessageSent)
                            viewModelScope.launch {
                                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendText(room.roomId))
                            }
                            popDraft()
                        }
                        is ParsedCommand.Invite                   -> {
                            handleInviteSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.Invite3Pid               -> {
                            handleInvite3pidSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.SetUserPowerLevel        -> {
                            handleSetUserPowerLevel(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.ClearScalarToken         -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.SetMarkdown              -> {
                            vectorPreferences.setMarkdownEnabled(slashCommandResult.enable)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled(
                                    if (slashCommandResult.enable) R.string.markdown_has_been_enabled else R.string.markdown_has_been_disabled))
                            popDraft()
                        }
                        is ParsedCommand.UnbanUser                -> {
                            handleUnbanSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.BanUser                  -> {
                            handleBanSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.KickUser                 -> {
                            handleKickSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.JoinRoom                 -> {
                            handleJoinToAnotherRoomSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.PartRoom                 -> {
                            // TODO
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.SendEmote                -> {
                            room.sendTextMessage(slashCommandResult.message, msgType = MessageType.MSGTYPE_EMOTE, autoMarkdown = action.autoMarkdown)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendRainbow              -> {
                            slashCommandResult.message.toString().let {
                                room.sendFormattedTextMessage(it, rainbowGenerator.generate(it))
                                viewModelScope.launch {
                                    analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendText(room.roomId))
                                }
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendRainbowEmote         -> {
                            slashCommandResult.message.toString().let {
                                room.sendFormattedTextMessage(it, rainbowGenerator.generate(it), MessageType.MSGTYPE_EMOTE)
                                viewModelScope.launch {
                                    analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendText(room.roomId))
                                }
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendSpoiler              -> {
                            room.sendFormattedTextMessage(
                                    "[${stringProvider.getString(R.string.spoiler)}](${slashCommandResult.message})",
                                    "<span data-mx-spoiler>${slashCommandResult.message}</span>"
                            )
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendShrug                -> {
                            val sequence = buildString {
                                append("¯\\_(ツ)_/¯")
                                if (slashCommandResult.message.isNotEmpty()) {
                                    append(" ")
                                    append(slashCommandResult.message)
                                }
                            }
                            room.sendTextMessage(sequence)
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendPoll                 -> {
                            room.sendPoll(slashCommandResult.question, slashCommandResult.options.mapIndexed { index, s -> OptionItem(s, "$index. $s") })
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.ChangeTopic              -> {
                            handleChangeTopicSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.ChangeDisplayName        -> {
                            handleChangeDisplayNameSlashCommand(slashCommandResult)
                            popDraft()
                        }
                        is ParsedCommand.DiscardSession           -> {
                            if (room.isEncrypted()) {
                                session.cryptoService().discardOutboundSession(room.roomId)
                                _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                                popDraft()
                            } else {
                                _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                                _viewEvents.post(
                                        RoomDetailViewEvents
                                                .ShowMessage(stringProvider.getString(R.string.command_description_discard_session_not_handled))
                                )
                            }
                        }
                    }.exhaustive
                }
                is SendMode.EDIT    -> {
                    // is original event a reply?
                    val inReplyTo = state.sendMode.timelineEvent.root.getClearContent().toModel<MessageContent>()?.relatesTo?.inReplyTo?.eventId
                            ?: state.sendMode.timelineEvent.root.content.toModel<EncryptedEventContent>()?.relatesTo?.inReplyTo?.eventId
                    if (inReplyTo != null) {
                        // TODO check if same content?
                        room.getTimeLineEvent(inReplyTo)?.let {
                            room.editReply(state.sendMode.timelineEvent, it, action.text.toString())
                        }
                    } else {
                        val messageContent: MessageContent? =
                                state.sendMode.timelineEvent.annotations?.editSummary?.aggregatedContent.toModel()
                                        ?: state.sendMode.timelineEvent.root.getClearContent().toModel()
                        val existingBody = messageContent?.body ?: ""
                        if (existingBody != action.text) {
                            room.editTextMessage(state.sendMode.timelineEvent.root.eventId ?: "",
                                    messageContent?.msgType ?: MessageType.MSGTYPE_TEXT,
                                    action.text,
                                    action.autoMarkdown)
                            viewModelScope.launch {
                                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendEdit(room.roomId))
                            }
                        } else {
                            Timber.w("Same message content, do not send edition")
                        }
                    }
                    _viewEvents.post(RoomDetailViewEvents.MessageSent)
                    popDraft()
                }
                is SendMode.QUOTE   -> {
                    val messageContent: MessageContent? =
                            state.sendMode.timelineEvent.annotations?.editSummary?.aggregatedContent.toModel()
                                    ?: state.sendMode.timelineEvent.root.getClearContent().toModel()
                    val textMsg = messageContent?.body

                    val finalText = legacyRiotQuoteText(textMsg, action.text.toString())

                    // TODO check for pills?

                    // TODO Refactor this, just temporary for quotes
                    val parser = Parser.builder().build()
                    val document = parser.parse(finalText)
                    val renderer = HtmlRenderer.builder().build()
                    val htmlText = renderer.render(document)
                    if (finalText == htmlText) {
                        room.sendTextMessage(finalText)
                    } else {
                        room.sendFormattedTextMessage(finalText, htmlText)
                    }
                    viewModelScope.launch {
                        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendText(room.roomId))
                    }
                    _viewEvents.post(RoomDetailViewEvents.MessageSent)
                    popDraft()
                }
                is SendMode.REPLY   -> {
                    state.sendMode.timelineEvent.let {
                        room.replyToMessage(it, action.text.toString(), action.autoMarkdown)
                        viewModelScope.launch {
                            analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendReply(room.roomId))
                        }

                        _viewEvents.post(RoomDetailViewEvents.MessageSent)
                        popDraft()
                    }
                }
            }.exhaustive
        }
    }

    private fun popDraft() = withState {
        if (it.sendMode is SendMode.REGULAR && it.sendMode.fromSharing) {
            // If we were sharing, we want to get back our last value from draft
            getDraftIfAny()
        } else {
            // Otherwise we clear the composer and remove the draft from db
            setState { copy(sendMode = SendMode.REGULAR("", false)) }
            room.deleteDraft(NoOpMatrixCallback())
        }
    }

    private fun handleJoinToAnotherRoomSlashCommand(command: ParsedCommand.JoinRoom) {
        session.joinRoom(command.roomAlias, command.reason, emptyList(), object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                session.getRoomSummary(command.roomAlias)
                        ?.roomId
                        ?.let {
                            _viewEvents.post(RoomDetailViewEvents.JoinRoomCommandSuccess(it))
                        }
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
            }
        })
    }

    private fun legacyRiotQuoteText(quotedText: String?, myText: String): String {
        val messageParagraphs = quotedText?.split("\n\n".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray()
        return buildString {
            if (messageParagraphs != null) {
                for (i in messageParagraphs.indices) {
                    if (messageParagraphs[i].isNotBlank()) {
                        append("> ")
                        append(messageParagraphs[i])
                    }

                    if (i != messageParagraphs.lastIndex) {
                        append("\n\n")
                    }
                }
            }
            append("\n\n")
            append(myText)
        }
    }

    private fun handleChangeTopicSlashCommand(changeTopic: ParsedCommand.ChangeTopic) {
        launchSlashCommandFlow {
            room.updateTopic(changeTopic.topic, it)
        }
    }

    private fun handleInviteSlashCommand(invite: ParsedCommand.Invite) {
        launchSlashCommandFlow {
            room.invite(invite.userId, invite.reason, it)
        }
    }

    private fun handleInvite3pidSlashCommand(invite: ParsedCommand.Invite3Pid) {
        launchSlashCommandFlow {
            room.invite3pid(invite.threePid, it)
        }
    }

    private fun handleSetUserPowerLevel(setUserPowerLevel: ParsedCommand.SetUserPowerLevel) {
        val currentPowerLevelsContent = room.getStateEvent(EventType.STATE_ROOM_POWER_LEVELS)
                ?.content
                ?.toModel<PowerLevelsContent>() ?: return

        launchSlashCommandFlow {
            currentPowerLevelsContent.setUserPowerLevel(setUserPowerLevel.userId, setUserPowerLevel.powerLevel)
            room.sendStateEvent(EventType.STATE_ROOM_POWER_LEVELS, null, currentPowerLevelsContent.toContent(), it)
        }
    }

    private fun handleChangeDisplayNameSlashCommand(changeDisplayName: ParsedCommand.ChangeDisplayName) {
        launchSlashCommandFlow {
            session.setDisplayName(session.myUserId, changeDisplayName.displayName, it)
        }
    }

    private fun handleKickSlashCommand(kick: ParsedCommand.KickUser) {
        launchSlashCommandFlow {
            room.kick(kick.userId, kick.reason, it)
        }
    }

    private fun handleBanSlashCommand(ban: ParsedCommand.BanUser) {
        launchSlashCommandFlow {
            room.ban(ban.userId, ban.reason, it)
        }
    }

    private fun handleUnbanSlashCommand(unban: ParsedCommand.UnbanUser) {
        launchSlashCommandFlow {
            room.unban(unban.userId, unban.reason, it)
        }
    }

    private fun launchSlashCommandFlow(lambda: (MatrixCallback<Unit>) -> Unit) {
        _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
        val matrixCallback = object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultOk)
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
            }
        }
        lambda.invoke(matrixCallback)
    }

    private fun handleSendReaction(action: RoomDetailAction.SendReaction) {
        room.sendReaction(action.targetEventId, action.reaction)
    }

    private fun handleRedactEvent(action: RoomDetailAction.RedactAction) {
        val event = room.getTimeLineEvent(action.targetEventId) ?: return
        room.redactEvent(event.root, action.reason)
    }

    private fun handleUndoReact(action: RoomDetailAction.UndoReaction) {
        room.undoReaction(action.targetEventId, action.reaction)
    }

    private fun handleUpdateQuickReaction(action: RoomDetailAction.UpdateQuickReactAction) {
        if (action.add) {
            room.sendReaction(action.targetEventId, action.selectedReaction)
        } else {
            room.undoReaction(action.targetEventId, action.selectedReaction)
        }
    }

    private fun handleSendMedia(action: RoomDetailAction.SendMedia) {
        val attachments = action.attachments
        val homeServerCapabilities = session.getHomeServerCapabilities()
        val maxUploadFileSize = homeServerCapabilities.maxUploadFileSize

        if (maxUploadFileSize == HomeServerCapabilities.MAX_UPLOAD_FILE_SIZE_UNKNOWN) {
            // Unknown limitation
            room.sendMedias(attachments, action.compressBeforeSending, emptySet())
        } else {
            when (val tooBigFile = attachments.find { it.size > maxUploadFileSize }) {
                null -> room.sendMedias(attachments, action.compressBeforeSending, emptySet())
                else -> _viewEvents.post(RoomDetailViewEvents.FileTooBigError(
                        tooBigFile.name ?: tooBigFile.queryUri.toString(),
                        tooBigFile.size,
                        maxUploadFileSize
                ))
            }
        }
        viewModelScope.launch {
            attachments.forEach {
                analyticsEngine.report(AnalyticsEngine.AnalyticEvent.SendMedia(roomId = room.roomId, type = it.type))
            }
        }
    }

    private fun handleEventVisible(action: RoomDetailAction.TimelineEventTurnsVisible) {
        viewModelScope.launch(Dispatchers.Default) {
            if (action.event.root.sendState.isSent()) { // ignore pending/local events
                visibleEventsObservable.accept(action)
            }
            // We need to update this with the related m.replace also (to move read receipt)
            action.event.annotations?.editSummary?.sourceEvents?.forEach {
                room.getTimeLineEvent(it)?.let { event ->
                    visibleEventsObservable.accept(RoomDetailAction.TimelineEventTurnsVisible(event))
                }
            }
        }
    }

    private fun handleLoadMore(action: RoomDetailAction.LoadMoreTimelineEvents) {
        timeline.paginate(action.direction, PAGINATION_COUNT)
    }

    private fun handleRejectInvite() {
        room.leave(null, NoOpMatrixCallback())
    }

    private fun handleAcceptInvite() {
        room.join(callback = NoOpMatrixCallback())
    }

    private fun handleEditAction(action: RoomDetailAction.EnterEditMode) {
        room.getTimeLineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.EDIT(timelineEvent, timelineEvent.getTextEditableContent() ?: "")) }
        }
    }

    private fun handleQuoteAction(action: RoomDetailAction.EnterQuoteMode) {
        room.getTimeLineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.QUOTE(timelineEvent, action.text)) }
        }
    }

    private fun handleReplyAction(action: RoomDetailAction.EnterReplyMode) {
        room.getTimeLineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.REPLY(timelineEvent, action.text)) }
        }
    }

    private fun handleEnterRegularMode(action: RoomDetailAction.EnterRegularMode) = setState {
        copy(sendMode = SendMode.REGULAR(action.text, action.fromSharing))
    }

    private fun handleOpenOrDownloadFile(action: RoomDetailAction.DownloadOrOpen) {
        val mxcUrl = action.messageFileContent.getFileUrl()
        val isLocalSendingFile = action.senderId == session.myUserId
                && mxcUrl?.startsWith("content://") ?: false
        val isDownloaded = mxcUrl?.let { session.fileService().isFileInCache(it, action.messageFileContent.mimeType) } ?: false
        if (isLocalSendingFile) {
            tryOrNull { Uri.parse(mxcUrl) }?.let {
                _viewEvents.post(RoomDetailViewEvents.OpenFile(
                        action.messageFileContent.mimeType,
                        it,
                        null
                ))
            }
        } else if (isDownloaded) {
            // we can open it
            session.fileService().getTemporarySharableURI(mxcUrl!!, action.messageFileContent.mimeType)?.let { uri ->
                _viewEvents.post(RoomDetailViewEvents.OpenFile(
                        action.messageFileContent.mimeType,
                        uri,
                        null
                ))
            }
        } else {
            session.fileService().downloadFile(
                    downloadMode = FileService.DownloadMode.FOR_INTERNAL_USE,
                    id = action.eventId,
                    fileName = action.messageFileContent.getFileName(),
                    mimeType = action.messageFileContent.mimeType,
                    url = mxcUrl,
                    elementToDecrypt = action.messageFileContent.encryptedFileInfo?.toElementToDecrypt(),
                    callback = object : MatrixCallback<File> {
                        override fun onSuccess(data: File) {
                            _viewEvents.post(RoomDetailViewEvents.DownloadFileState(
                                    action.messageFileContent.mimeType,
                                    data,
                                    null
                            ))
                        }

                        override fun onFailure(failure: Throwable) {
                            _viewEvents.post(RoomDetailViewEvents.DownloadFileState(
                                    action.messageFileContent.mimeType,
                                    null,
                                    failure
                            ))
                        }
                    })
        }
    }

    private fun handleNavigateToEvent(action: RoomDetailAction.NavigateToEvent) {
        stopTrackingUnreadMessages()
        val targetEventId: String = action.eventId
        val correctedEventId = timeline.getFirstDisplayableEventId(targetEventId) ?: targetEventId
        val indexOfEvent = timeline.getIndexOfEvent(correctedEventId)
        if (indexOfEvent == null) {
            // Event is not already in RAM
            timeline.restartWithEventId(targetEventId)
        }
        if (action.highlight) {
            setState { copy(highlightedEventId = correctedEventId) }
        }
        _viewEvents.post(RoomDetailViewEvents.NavigateToEvent(correctedEventId))
    }

    private fun handleResendEvent(action: RoomDetailAction.ResendMessage) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be UNDELIVERED or Failed
            if (!it.root.sendState.hasFailed()) {
                Timber.e("Cannot resend message, it is not failed, Cancel first")
                return
            }
            when {
                it.root.isTextMessage()       -> room.resendTextMessage(it)
                it.root.isAttachmentMessage() -> room.resendMediaMessage(it)
                else                          -> {
                    // TODO
                }
            }
        }
    }

    private fun handleRemove(action: RoomDetailAction.RemoveFailedEcho) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be UNDELIVERED or Failed
            if (!it.root.sendState.hasFailed()) {
                Timber.e("Cannot resend message, it is not failed, Cancel first")
                return
            }
            room.deleteFailedEcho(it)
        }
    }

    private fun handleCancel(action: RoomDetailAction.CancelSend) {
        val targetEventId = action.eventId
        room.getTimeLineEvent(targetEventId)?.let {
            // State must be in one of the sending states
            if (!it.root.sendState.isSending()) {
                Timber.e("Cannot cancel message, it is not sending")
                return
            }
            room.cancelSend(targetEventId)
        }
    }

    private fun handleResendAll() {
        room.resendAllFailedMessages()
    }

    private fun observeEventDisplayedActions() {
        // We are buffering scroll events for one second
        // and keep the most recent one to set the read receipt on.
        visibleEventsObservable
                .buffer(1, TimeUnit.SECONDS)
                .filter { it.isNotEmpty() }
                .subscribeBy(onNext = { actions ->
                    val bufferedMostRecentDisplayedEvent = actions.maxByOrNull { it.event.displayIndex }?.event ?: return@subscribeBy
                    val globalMostRecentDisplayedEvent = mostRecentDisplayedEvent
                    if (trackUnreadMessages.get()) {
                        if (globalMostRecentDisplayedEvent == null) {
                            mostRecentDisplayedEvent = bufferedMostRecentDisplayedEvent
                        } else if (bufferedMostRecentDisplayedEvent.displayIndex > globalMostRecentDisplayedEvent.displayIndex) {
                            mostRecentDisplayedEvent = bufferedMostRecentDisplayedEvent
                        }
                    }
                    bufferedMostRecentDisplayedEvent.root.eventId?.let { eventId ->
                        room.setReadReceipt(eventId, callback = NoOpMatrixCallback())
                    }
                })
                .disposeOnClear()
    }

    private fun handleMarkAllAsRead() {
        room.markAsRead(ReadService.MarkAsReadParams.BOTH, NoOpMatrixCallback())
    }

    private fun handleReportContent(action: RoomDetailAction.ReportContent) {
        room.reportContent(action.eventId, -100, action.reason, object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        })
    }

    private fun handleIgnoreUser(action: RoomDetailAction.IgnoreUser) {
        if (action.userId.isNullOrEmpty()) {
            return
        }

        session.ignoreUserIds(listOf(action.userId), object : MatrixCallback<Unit> {
            override fun onSuccess(data: Unit) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
            }

            override fun onFailure(failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        })
    }

    private fun handleAcceptVerification(action: RoomDetailAction.AcceptVerificationRequest) {
        Timber.v("## SAS handleAcceptVerification ${action.otherUserId},  roomId:${room.roomId}, txId:${action.transactionId}")
        if (session.cryptoService().verificationService().readyPendingVerificationInDMs(
                        supportedVerificationMethodsProvider.provide(),
                        action.otherUserId,
                        room.roomId,
                        action.transactionId)) {
            _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
        } else {
            // TODO
        }
    }

    private fun handleDeclineVerification(action: RoomDetailAction.DeclineVerificationRequest) {
        session.cryptoService().verificationService().declineVerificationRequestInDMs(
                action.otherUserId,
                action.transactionId,
                room.roomId)
    }

    private fun handleRequestVerification(action: RoomDetailAction.RequestVerification) {
        if (action.userId == session.myUserId) return
        _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
    }

    private fun handleResumeRequestVerification(action: RoomDetailAction.ResumeVerification) {
        // Check if this request is still active and handled by me
        session.cryptoService().verificationService().getExistingVerificationRequestInRoom(room.roomId, action.transactionId)?.let {
            if (it.handledByOtherSession) return
            if (!it.isFinished) {
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action.copy(
                        otherUserId = it.otherUserId
                )))
            }
        }
    }

    private fun handleReRequestKeys(action: RoomDetailAction.ReRequestKeys) {
        // Check if this request is still active and handled by me
        room.getTimeLineEvent(action.eventId)?.let {
            session.cryptoService().reRequestRoomKeyForEvent(it.root)
            _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.e2e_re_request_encryption_key_dialog_content)))
        }
    }

    private fun handleTapOnFailedToDecrypt(action: RoomDetailAction.TapOnFailedToDecrypt) {
        room.getTimeLineEvent(action.eventId)?.let {
            val code = when (it.root.mCryptoError) {
                MXCryptoError.ErrorType.KEYS_WITHHELD -> {
                    WithHeldCode.fromCode(it.root.mCryptoErrorReason)
                }
                else                                  -> null
            }

            _viewEvents.post(RoomDetailViewEvents.ShowE2EErrorMessage(code))
        }
    }

    private fun handleReplyToOptions(action: RoomDetailAction.ReplyToOptions) {
        // Do not allow to reply to unsent local echo
        if (LocalEcho.isLocalEchoId(action.eventId)) return
        room.sendOptionsReply(action.eventId, action.optionIndex, action.optionValue)
    }

    private fun observeSyncState() {
        session.rx()
                .liveSyncState()
                .subscribe { syncState ->
                    setState {
                        copy(syncState = syncState)
                    }
                }
                .disposeOnClear()
    }

    private fun observeRoomSummary() {
        room.rx().liveRoomSummary()
                .unwrap()
                .execute { async ->
                    copy(
                            asyncRoomSummary = async
                    )
                }
    }

    private fun getUnreadState() {
        Observable
                .combineLatest<List<TimelineEvent>, RoomSummary, UnreadState>(
                        timelineEvents.observeOn(Schedulers.computation()),
                        room.rx().liveRoomSummary().unwrap(),
                        BiFunction { timelineEvents, roomSummary ->
                            computeUnreadState(timelineEvents, roomSummary)
                        }
                )
                // We don't want live update of unread so we skip when we already had a HasUnread or HasNoUnread
                .distinctUntilChanged { previous, current ->
                    when {
                        previous is UnreadState.Unknown || previous is UnreadState.ReadMarkerNotLoaded -> false
                        current is UnreadState.HasUnread || current is UnreadState.HasNoUnread         -> true
                        else                                                                           -> false
                    }
                }
                .subscribe {
                    setState { copy(unreadState = it) }
                }
                .disposeOnClear()
    }

    private fun computeUnreadState(events: List<TimelineEvent>, roomSummary: RoomSummary): UnreadState {
        if (events.isEmpty()) return UnreadState.Unknown
        val readMarkerIdSnapshot = roomSummary.readMarkerId ?: return UnreadState.Unknown
        val firstDisplayableEventId = timeline.getFirstDisplayableEventId(readMarkerIdSnapshot)
        val firstDisplayableEventIndex = timeline.getIndexOfEvent(firstDisplayableEventId)
        if (firstDisplayableEventId == null || firstDisplayableEventIndex == null) {
            return if (timeline.isLive) {
                UnreadState.ReadMarkerNotLoaded(readMarkerIdSnapshot)
            } else {
                UnreadState.Unknown
            }
        }
        for (i in (firstDisplayableEventIndex - 1) downTo 0) {
            val timelineEvent = events.getOrNull(i) ?: return UnreadState.Unknown
            val eventId = timelineEvent.root.eventId ?: return UnreadState.Unknown
            val isFromMe = timelineEvent.root.senderId == session.myUserId
            if (!isFromMe) {
                return UnreadState.HasUnread(eventId)
            }
        }
        return UnreadState.HasNoUnread
    }

    private fun observeUnreadState() {
        selectSubscribe(RoomDetailViewState::unreadState) {
            Timber.v("Unread state: $it")
            if (it is UnreadState.HasNoUnread) {
                startTrackingUnreadMessages()
            }
        }
    }

    private fun observeMembershipChanges() {
        session.rx()
                .liveRoomChangeMembershipState()
                .map {
                    it[initialState.roomId] ?: ChangeMembershipState.Unknown
                }
                .distinctUntilChanged()
                .subscribe {
                    setState { copy(changeMembershipState = it) }
                }
                .disposeOnClear()
    }

    private fun observeSummaryState() {
        asyncSubscribe(RoomDetailViewState::asyncRoomSummary) { summary ->
            roomSummaryHolder.set(summary)
            setState {
                val typingMessage = typingHelper.getTypingMessage(summary.typingUsers)
                copy(typingMessage = typingMessage)
            }
            if (summary.membership == Membership.INVITE) {
                summary.inviterId?.let { inviterId ->
                    session.getUser(inviterId)
                }?.also {
                    setState { copy(asyncInviter = Success(it)) }
                }
            }
            room.getStateEvent(EventType.STATE_ROOM_TOMBSTONE)?.also {
                setState { copy(tombstoneEvent = it) }
            }
        }
    }

    override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
        timelineEvents.accept(snapshot)
    }

    override fun onTimelineFailure(throwable: Throwable) {
        // If we have a critical timeline issue, we get back to live.
        timeline.restartWithEventId(null)
        _viewEvents.post(RoomDetailViewEvents.Failure(throwable))
    }

    override fun onNewTimelineEvents(eventIds: List<String>) {
        Timber.v("On new timeline events: $eventIds")
        _viewEvents.post(RoomDetailViewEvents.OnNewTimelineEvents(eventIds))
    }

    override fun onCleared() {
        roomSummaryHolder.clear()
        timeline.dispose()
        timeline.removeAllListeners()
        if (vectorPreferences.sendTypingNotifs()) {
            room.userStopsTyping()
        }
        super.onCleared()
    }
}

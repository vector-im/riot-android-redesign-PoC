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
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.ViewModelContext
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.mvrx.runCatchingToAsync
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.call.conference.JitsiService
import im.vector.app.features.call.dialpad.DialPadLookup
import im.vector.app.features.call.lookup.CallProtocolsChecker
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.command.CommandParser
import im.vector.app.features.command.ParsedCommand
import im.vector.app.features.createdirect.DirectRoomHelper
import im.vector.app.features.crypto.keysrequest.OutboundSessionKeySharingStrategy
import im.vector.app.features.crypto.verification.SupportedVerificationMethodsProvider
import im.vector.app.features.home.room.detail.composer.rainbow.RainbowGenerator
import im.vector.app.features.home.room.detail.sticker.StickerPickerActionHandler
import im.vector.app.features.home.room.detail.timeline.helper.RoomSummariesHolder
import im.vector.app.features.home.room.detail.timeline.helper.TimelineSettingsFactory
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.typing.TypingHelper
import im.vector.app.features.powerlevel.PowerLevelsObservableFactory
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import io.reactivex.Observable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.OptionItem
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.tombstone.RoomTombstoneContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.room.timeline.getRelationContent
import org.matrix.android.sdk.api.session.room.timeline.getTextEditableContent
import org.matrix.android.sdk.api.session.space.CreateSpaceParams
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.crypto.model.event.WithHeldCode
import org.matrix.android.sdk.rx.rx
import org.matrix.android.sdk.rx.unwrap
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RoomDetailViewModel @AssistedInject constructor(
        @Assisted private val initialState: RoomDetailViewState,
        private val vectorPreferences: VectorPreferences,
        private val stringProvider: StringProvider,
        private val rainbowGenerator: RainbowGenerator,
        private val session: Session,
        private val supportedVerificationMethodsProvider: SupportedVerificationMethodsProvider,
        private val stickerPickerActionHandler: StickerPickerActionHandler,
        private val roomSummariesHolder: RoomSummariesHolder,
        private val typingHelper: TypingHelper,
        private val callManager: WebRtcCallManager,
        private val chatEffectManager: ChatEffectManager,
        private val directRoomHelper: DirectRoomHelper,
        private val jitsiService: JitsiService,
        timelineSettingsFactory: TimelineSettingsFactory
) : VectorViewModel<RoomDetailViewState, RoomDetailAction, RoomDetailViewEvents>(initialState),
        Timeline.Listener, ChatEffectManager.Delegate, CallProtocolsChecker.Listener {

    private val room = session.getRoom(initialState.roomId)!!
    private val eventId = initialState.eventId
    private val invisibleEventsObservable = BehaviorRelay.create<RoomDetailAction.TimelineEventTurnsInvisible>()
    private val visibleEventsObservable = BehaviorRelay.create<RoomDetailAction.TimelineEventTurnsVisible>()
    private val timelineSettings = timelineSettingsFactory.create()
    private var timelineEvents = PublishRelay.create<List<TimelineEvent>>()
    val timeline = room.createTimeline(eventId, timelineSettings)

    // Same lifecycle than the ViewModel (survive to screen rotation)
    val previewUrlRetriever = PreviewUrlRetriever(session, viewModelScope)

    // Slot to keep a pending action during permission request
    var pendingAction: RoomDetailAction? = null

    // Slot to keep a pending event during permission request
    var pendingEvent: RoomDetailViewEvents? = null

    private var trackUnreadMessages = AtomicBoolean(false)
    private var mostRecentDisplayedEvent: TimelineEvent? = null

    private var prepareToEncrypt: Async<Unit> = Uninitialized

    @AssistedFactory
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
        loadDraftIfAny()
        observeUnreadState()
        observeMyRoomMember()
        observeActiveRoomWidgets()
        observePowerLevel()
        updateShowDialerOptionState()
        room.getRoomSummaryLive()
        viewModelScope.launch(Dispatchers.IO) {
            tryOrNull { room.markAsRead(ReadService.MarkAsReadParams.READ_RECEIPT) }
        }
        // Inform the SDK that the room is displayed
        viewModelScope.launch(Dispatchers.IO) {
            tryOrNull { session.onRoomDisplayed(initialState.roomId) }
        }
        callManager.addProtocolsCheckerListener(this)
        callManager.checkForProtocolsSupportIfNeeded()
        chatEffectManager.delegate = this

        // Ensure to share the outbound session keys with all members
        if (OutboundSessionKeySharingStrategy.WhenEnteringRoom == BuildConfig.outboundSessionKeySharingStrategy && room.isEncrypted()) {
            prepareForEncryption()
        }
    }

    private fun prepareForEncryption() {
        // check if there is not already a call made, or if there has been an error
        if (prepareToEncrypt.shouldLoad) {
            prepareToEncrypt = Loading()
            viewModelScope.launch {
                runCatching {
                    room.prepareToEncrypt()
                }.fold({
                    prepareToEncrypt = Success(Unit)
                }, {
                    prepareToEncrypt = Fail(it)
                })
            }
        }
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
            is RoomDetailAction.ComposerFocusChange              -> handleComposerFocusChange(action)
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
            is RoomDetailAction.StartCallWithPhoneNumber         -> handleStartCallWithPhoneNumber(action)
            is RoomDetailAction.StartCall                        -> handleStartCall(action)
            is RoomDetailAction.AcceptCall                       -> handleAcceptCall(action)
            is RoomDetailAction.EndCall                          -> handleEndCall()
            is RoomDetailAction.ManageIntegrations               -> handleManageIntegrations()
            is RoomDetailAction.AddJitsiWidget                   -> handleAddJitsiConference(action)
            is RoomDetailAction.RemoveWidget                     -> handleDeleteWidget(action.widgetId)
            is RoomDetailAction.EnsureNativeWidgetAllowed        -> handleCheckWidgetAllowed(action)
            is RoomDetailAction.CancelSend                       -> handleCancel(action)
            is RoomDetailAction.OpenOrCreateDm                   -> handleOpenOrCreateDm(action)
            is RoomDetailAction.JumpToReadReceipt                -> handleJumpToReadReceipt(action)
            RoomDetailAction.QuickActionInvitePeople             -> handleInvitePeople()
            RoomDetailAction.QuickActionSetAvatar                -> handleQuickSetAvatar()
            is RoomDetailAction.SetAvatarAction                  -> handleSetNewAvatar(action)
            RoomDetailAction.QuickActionSetTopic                 -> _viewEvents.post(RoomDetailViewEvents.OpenRoomSettings)
            is RoomDetailAction.ShowRoomAvatarFullScreen         -> {
                _viewEvents.post(
                        RoomDetailViewEvents.ShowRoomAvatarFullScreen(action.matrixItem, action.transitionView)
                )
            }
            is RoomDetailAction.DoNotShowPreviewUrlFor           -> handleDoNotShowPreviewUrlFor(action)
            RoomDetailAction.RemoveAllFailedMessages             -> handleRemoveAllFailedMessages()
            RoomDetailAction.ResendAll                           -> handleResendAll()
        }.exhaustive
    }

    private fun handleStartCallWithPhoneNumber(action: RoomDetailAction.StartCallWithPhoneNumber) {
        viewModelScope.launch {
            try {
                val result = DialPadLookup(session, callManager, directRoomHelper).lookupPhoneNumber(action.phoneNumber)
                callManager.startOutgoingCall(result.roomId, result.userId, action.videoCall)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        }
    }

    private fun handleAcceptCall(action: RoomDetailAction.AcceptCall) {
        callManager.getCallById(action.callId)?.also {
            _viewEvents.post(RoomDetailViewEvents.DisplayAndAcceptCall(it))
        }
    }

    private fun handleDoNotShowPreviewUrlFor(action: RoomDetailAction.DoNotShowPreviewUrlFor) {
        previewUrlRetriever.doNotShowPreviewUrlFor(action.eventId, action.url)
    }

    private fun handleSetNewAvatar(action: RoomDetailAction.SetAvatarAction) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                room.updateAvatar(action.newAvatarUri, action.newAvatarFileName)
                _viewEvents.post(RoomDetailViewEvents.ActionSuccess(action))
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
            }
        }
    }

    private fun handleInvitePeople() {
        _viewEvents.post(RoomDetailViewEvents.OpenInvitePeople)
    }

    private fun handleQuickSetAvatar() {
        _viewEvents.post(RoomDetailViewEvents.OpenSetRoomAvatarDialog)
    }

    private fun handleOpenOrCreateDm(action: RoomDetailAction.OpenOrCreateDm) {
        viewModelScope.launch {
            val roomId = try {
                directRoomHelper.ensureDMExists(action.userId)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ActionFailure(action, failure))
                return@launch
            }
            if (roomId != initialState.roomId) {
                _viewEvents.post(RoomDetailViewEvents.OpenRoom(roomId = roomId))
            }
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
        viewModelScope.launch {
            room.roomSummary()?.otherMemberIds?.firstOrNull()?.let {
                callManager.startOutgoingCall(room.roomId, it, action.isVideo)
            }
        }
    }

    private fun handleEndCall() {
        callManager.endCallForRoom(initialState.roomId)
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
            try {
                val widget = jitsiService.createJitsiWidget(room.roomId, action.withVideo)
                _viewEvents.post(RoomDetailViewEvents.JoinJitsiConference(widget, action.withVideo))
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.ShowMessage(stringProvider.getString(R.string.failed_to_add_widget)))
            } finally {
                _viewEvents.post(RoomDetailViewEvents.HideWaitingView)
            }
        }
    }

    private fun handleDeleteWidget(widgetId: String) {
        _viewEvents.post(RoomDetailViewEvents.ShowWaitingView)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                session.widgetService().destroyRoomWidget(room.roomId, widgetId)
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
                session.coroutineScope.launch {
                    tryOrNull { room.setReadMarker(it) }
                }
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
        session.coroutineScope.launch {
            when {
                it.sendMode is SendMode.REGULAR && !it.sendMode.fromSharing -> {
                    setState { copy(sendMode = it.sendMode.copy(action.draft)) }
                    room.saveDraft(UserDraft.REGULAR(action.draft))
                }
                it.sendMode is SendMode.REPLY                               -> {
                    setState { copy(sendMode = it.sendMode.copy(text = action.draft)) }
                    room.saveDraft(UserDraft.REPLY(it.sendMode.timelineEvent.root.eventId!!, action.draft))
                }
                it.sendMode is SendMode.QUOTE                               -> {
                    setState { copy(sendMode = it.sendMode.copy(text = action.draft)) }
                    room.saveDraft(UserDraft.QUOTE(it.sendMode.timelineEvent.root.eventId!!, action.draft))
                }
                it.sendMode is SendMode.EDIT                                -> {
                    setState { copy(sendMode = it.sendMode.copy(text = action.draft)) }
                    room.saveDraft(UserDraft.EDIT(it.sendMode.timelineEvent.root.eventId!!, action.draft))
                }
            }
        }
    }

    private fun loadDraftIfAny() {
        val currentDraft = room.getDraft()
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
                        else                 -> null
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

    private fun handleComposerFocusChange(action: RoomDetailAction.ComposerFocusChange) {
        // Ensure outbound session keys
        if (OutboundSessionKeySharingStrategy.WhenTyping == BuildConfig.outboundSessionKeySharingStrategy && room.isEncrypted()) {
            if (action.focused) {
                // Should we add some rate limit here, or do it only once per model lifecycle?
                prepareForEncryption()
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
            viewModelScope.launch {
                val result = runCatchingToAsync {
                    session.joinRoom(roomId, viaServers = viaServers)
                    roomId
                }
                setState {
                    copy(tombstoneEventHandling = result)
                }
            }
        }
    }

    private fun isIntegrationEnabled() = session.integrationManagerService().isIntegrationEnabled()

    fun isMenuItemVisible(@IdRes itemId: Int): Boolean = com.airbnb.mvrx.withState(this) { state ->
        if (state.asyncRoomSummary()?.membership != Membership.JOIN) {
            return@withState false
        }
        when (itemId) {
            R.id.timeline_setting -> true
            R.id.invite           -> state.canInvite
            R.id.open_matrix_apps -> true
            R.id.voice_call,
            R.id.video_call       -> callManager.getCallsByRoomId(state.roomId).isEmpty()
            R.id.hangup_call      -> callManager.getCallsByRoomId(state.roomId).isNotEmpty()
            R.id.search           -> true
            R.id.dev_tools        -> vectorPreferences.developerMode()
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
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.SendRainbowEmote         -> {
                            slashCommandResult.message.toString().let {
                                room.sendFormattedTextMessage(it, rainbowGenerator.generate(it), MessageType.MSGTYPE_EMOTE)
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
                        is ParsedCommand.SendChatEffect           -> {
                            sendChatEffect(slashCommandResult)
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
                        is ParsedCommand.CreateSpace              -> {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val params = CreateSpaceParams().apply {
                                        name = slashCommandResult.name
                                        invitedUserIds.addAll(slashCommandResult.invitees)
                                    }
                                    val spaceId = session.spaceService().createSpace(params)
                                    session.spaceService().getSpace(spaceId)
                                            ?.addChildren(
                                                    state.roomId,
                                                    null,
                                                    null,
                                                    true
                                            )
                                } catch (failure: Throwable) {
                                    _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.AddToSpace               -> {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    session.spaceService().getSpace(slashCommandResult.spaceId)
                                            ?.addChildren(
                                                    room.roomId,
                                                    null,
                                                    null,
                                                    false
                                            )
                                } catch (failure: Throwable) {
                                    _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.JoinSpace                -> {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    session.spaceService().joinSpace(slashCommandResult.spaceIdOrAlias)
                                } catch (failure: Throwable) {
                                    _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                        is ParsedCommand.LeaveRoom                -> {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    session.getRoom(slashCommandResult.roomId)?.leave(null)
                                } catch (failure: Throwable) {
                                    _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
                            popDraft()
                        }
                    }.exhaustive
                }
                is SendMode.EDIT    -> {
                    // is original event a reply?
                    val inReplyTo = state.sendMode.timelineEvent.getRelationContent()?.inReplyTo?.eventId
                    if (inReplyTo != null) {
                        // TODO check if same content?
                        room.getTimeLineEvent(inReplyTo)?.let {
                            room.editReply(state.sendMode.timelineEvent, it, action.text.toString())
                        }
                    } else {
                        val messageContent = state.sendMode.timelineEvent.getLastMessageContent()
                        val existingBody = messageContent?.body ?: ""
                        if (existingBody != action.text) {
                            room.editTextMessage(state.sendMode.timelineEvent,
                                    messageContent?.msgType ?: MessageType.MSGTYPE_TEXT,
                                    action.text,
                                    action.autoMarkdown)
                        } else {
                            Timber.w("Same message content, do not send edition")
                        }
                    }
                    _viewEvents.post(RoomDetailViewEvents.MessageSent)
                    popDraft()
                }
                is SendMode.QUOTE   -> {
                    val messageContent = state.sendMode.timelineEvent.getLastMessageContent()
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
                    _viewEvents.post(RoomDetailViewEvents.MessageSent)
                    popDraft()
                }
                is SendMode.REPLY   -> {
                    state.sendMode.timelineEvent.let {
                        room.replyToMessage(it, action.text.toString(), action.autoMarkdown)
                        _viewEvents.post(RoomDetailViewEvents.MessageSent)
                        popDraft()
                    }
                }
            }.exhaustive
        }
    }

    private fun sendChatEffect(sendChatEffect: ParsedCommand.SendChatEffect) {
        // If message is blank, convert to an emote, with default message
        if (sendChatEffect.message.isBlank()) {
            val defaultMessage = stringProvider.getString(when (sendChatEffect.chatEffect) {
                ChatEffect.CONFETTI -> R.string.default_message_emote_confetti
                ChatEffect.SNOW     -> R.string.default_message_emote_snow
            })
            room.sendTextMessage(defaultMessage, MessageType.MSGTYPE_EMOTE)
        } else {
            room.sendTextMessage(sendChatEffect.message, sendChatEffect.chatEffect.toMessageType())
        }
    }

    private fun popDraft() = withState {
        if (it.sendMode is SendMode.REGULAR && it.sendMode.fromSharing) {
            // If we were sharing, we want to get back our last value from draft
            loadDraftIfAny()
        } else {
            // Otherwise we clear the composer and remove the draft from db
            setState { copy(sendMode = SendMode.REGULAR("", false)) }
            viewModelScope.launch {
                room.deleteDraft()
            }
        }
    }

    private fun handleJoinToAnotherRoomSlashCommand(command: ParsedCommand.JoinRoom) {
        viewModelScope.launch {
            try {
                session.joinRoom(command.roomAlias, command.reason, emptyList())
            } catch (failure: Throwable) {
                _viewEvents.post(RoomDetailViewEvents.SlashCommandResultError(failure))
                return@launch
            }
            session.getRoomSummary(command.roomAlias)
                    ?.roomId
                    ?.let {
                        _viewEvents.post(RoomDetailViewEvents.JoinRoomCommandSuccess(it))
                    }
        }
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
        launchSlashCommandFlowSuspendable {
            room.updateTopic(changeTopic.topic)
        }
    }

    private fun handleInviteSlashCommand(invite: ParsedCommand.Invite) {
        launchSlashCommandFlowSuspendable {
            room.invite(invite.userId, invite.reason)
        }
    }

    private fun handleInvite3pidSlashCommand(invite: ParsedCommand.Invite3Pid) {
        launchSlashCommandFlowSuspendable {
            room.invite3pid(invite.threePid)
        }
    }

    private fun handleSetUserPowerLevel(setUserPowerLevel: ParsedCommand.SetUserPowerLevel) {
        val newPowerLevelsContent = room.getStateEvent(EventType.STATE_ROOM_POWER_LEVELS)
                ?.content
                ?.toModel<PowerLevelsContent>()
                ?.setUserPowerLevel(setUserPowerLevel.userId, setUserPowerLevel.powerLevel)
                ?.toContent()
                ?: return

        launchSlashCommandFlowSuspendable {
            room.sendStateEvent(EventType.STATE_ROOM_POWER_LEVELS, null, newPowerLevelsContent)
        }
    }

    private fun handleChangeDisplayNameSlashCommand(changeDisplayName: ParsedCommand.ChangeDisplayName) {
        launchSlashCommandFlowSuspendable {
            session.setDisplayName(session.myUserId, changeDisplayName.displayName)
        }
    }

    private fun handleKickSlashCommand(kick: ParsedCommand.KickUser) {
        launchSlashCommandFlowSuspendable {
            room.kick(kick.userId, kick.reason)
        }
    }

    private fun handleBanSlashCommand(ban: ParsedCommand.BanUser) {
        launchSlashCommandFlowSuspendable {
            room.ban(ban.userId, ban.reason)
        }
    }

    private fun handleUnbanSlashCommand(unban: ParsedCommand.UnbanUser) {
        launchSlashCommandFlowSuspendable {
            room.unban(unban.userId, unban.reason)
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

    private fun launchSlashCommandFlowSuspendable(block: suspend () -> Unit) {
        _viewEvents.post(RoomDetailViewEvents.SlashCommandHandled())
        viewModelScope.launch {
            val event = try {
                block()
                RoomDetailViewEvents.SlashCommandResultOk
            } catch (failure: Exception) {
                RoomDetailViewEvents.SlashCommandResultError(failure)
            }
            _viewEvents.post(event)
        }
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
        room.sendMedias(action.attachments, action.compressBeforeSending, emptySet())
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

            // handle chat effects here
            if (vectorPreferences.chatEffectsEnabled()) {
                chatEffectManager.checkForEffect(action.event)
            }
        }
    }

    override fun shouldStartEffect(effect: ChatEffect) {
        _viewEvents.post(RoomDetailViewEvents.StartChatEffect(effect))
    }

    override fun stopEffects() {
        _viewEvents.post(RoomDetailViewEvents.StopChatEffects)
    }

    private fun handleLoadMore(action: RoomDetailAction.LoadMoreTimelineEvents) {
        timeline.paginate(action.direction, PAGINATION_COUNT)
    }

    private fun handleRejectInvite() {
        viewModelScope.launch {
            tryOrNull { room.leave(null) }
        }
    }

    private fun handleAcceptInvite() {
        viewModelScope.launch {
            tryOrNull { room.join() }
        }
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
        val mxcUrl = action.messageFileContent.getFileUrl() ?: return
        val isLocalSendingFile = action.senderId == session.myUserId
                && mxcUrl.startsWith("content://")
        if (isLocalSendingFile) {
            tryOrNull { Uri.parse(mxcUrl) }?.let {
                _viewEvents.post(RoomDetailViewEvents.OpenFile(
                        it,
                        action.messageFileContent.mimeType
                ))
            }
        } else {
            viewModelScope.launch {
                val fileState = session.fileService().fileState(action.messageFileContent)
                var canOpen = fileState is FileService.FileState.InCache && fileState.decryptedFileInCache
                if (!canOpen) {
                    // First download, or download and decrypt, or decrypt from cache
                    val result = runCatching {
                        session.fileService().downloadFile(messageContent = action.messageFileContent)
                    }

                    _viewEvents.post(RoomDetailViewEvents.DownloadFileState(
                            action.messageFileContent.mimeType,
                            result.getOrNull(),
                            result.exceptionOrNull()
                    ))
                    canOpen = result.isSuccess
                }

                if (canOpen) {
                    // We can now open the file
                    session.fileService().getTemporarySharableURI(action.messageFileContent)?.let { uri ->
                        _viewEvents.post(RoomDetailViewEvents.OpenFile(
                                uri,
                                action.messageFileContent.mimeType
                        ))
                    }
                }
            }
        }
    }

    private fun handleNavigateToEvent(action: RoomDetailAction.NavigateToEvent) {
        stopTrackingUnreadMessages()
        val targetEventId: String = action.eventId
        val indexOfEvent = timeline.getIndexOfEvent(targetEventId)
        if (indexOfEvent == null) {
            // Event is not already in RAM
            timeline.restartWithEventId(targetEventId)
        }
        if (action.highlight) {
            setState { copy(highlightedEventId = targetEventId) }
        }
        _viewEvents.post(RoomDetailViewEvents.NavigateToEvent(targetEventId))
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
        if (action.force) {
            room.cancelSend(action.eventId)
            return
        }
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

    private fun handleRemoveAllFailedMessages() {
        room.cancelAllFailedMessages()
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
                        session.coroutineScope.launch {
                            tryOrNull { room.setReadReceipt(eventId) }
                        }
                    }
                })
                .disposeOnClear()
    }

    private fun handleMarkAllAsRead() {
        viewModelScope.launch {
            tryOrNull { room.markAsRead(ReadService.MarkAsReadParams.BOTH) }
        }
    }

    private fun handleReportContent(action: RoomDetailAction.ReportContent) {
        viewModelScope.launch {
            val event = try {
                room.reportContent(action.eventId, -100, action.reason)
                RoomDetailViewEvents.ActionSuccess(action)
            } catch (failure: Exception) {
                RoomDetailViewEvents.ActionFailure(action, failure)
            }
            _viewEvents.post(event)
        }
    }

    private fun handleIgnoreUser(action: RoomDetailAction.IgnoreUser) {
        if (action.userId.isNullOrEmpty()) {
            return
        }

        viewModelScope.launch {
            val event = try {
                session.ignoreUserIds(listOf(action.userId))
                RoomDetailViewEvents.ActionSuccess(action)
            } catch (failure: Throwable) {
                RoomDetailViewEvents.ActionFailure(action, failure)
            }
            _viewEvents.post(event)
        }
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
                        { timelineEvents, roomSummary ->
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
                    Timber.v("Unread state: $it")
                    setState { copy(unreadState = it) }
                }
                .disposeOnClear()
    }

    private fun computeUnreadState(events: List<TimelineEvent>, roomSummary: RoomSummary): UnreadState {
        if (events.isEmpty()) return UnreadState.Unknown
        val readMarkerIdSnapshot = roomSummary.readMarkerId ?: return UnreadState.Unknown
        val firstDisplayableEventIndex = timeline.getIndexOfEvent(readMarkerIdSnapshot)
                ?: return if (timeline.isLive) {
                    UnreadState.ReadMarkerNotLoaded(readMarkerIdSnapshot)
                } else {
                    UnreadState.Unknown
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
            roomSummariesHolder.set(summary)
            setState {
                val typingMessage = typingHelper.getTypingMessage(summary.typingUsers)
                copy(
                        typingMessage = typingMessage,
                        hasFailedSending = summary.hasFailedSending
                )
            }
            if (summary.membership == Membership.INVITE) {
                summary.inviterId?.let { inviterId ->
                    session.getRoomMember(inviterId, summary.roomId)
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

        // PreviewUrl
        if (vectorPreferences.showUrlPreviews()) {
            withState { state ->
                snapshot
                        .takeIf { state.asyncRoomSummary.invoke()?.isEncrypted == false }
                        ?.forEach {
                            previewUrlRetriever.getPreviewUrl(it)
                        }
            }
        }
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

    override fun onPSTNSupportUpdated() {
        updateShowDialerOptionState()
    }

    private fun updateShowDialerOptionState() {
        setState {
            copy(showDialerOption = callManager.supportsPSTNProtocol)
        }
    }

    override fun onCleared() {
        roomSummariesHolder.remove(room.roomId)
        timeline.dispose()
        timeline.removeAllListeners()
        if (vectorPreferences.sendTypingNotifs()) {
            room.userStopsTyping()
        }
        chatEffectManager.delegate = null
        chatEffectManager.dispose()
        callManager.removeProtocolsCheckerListener(this)
        super.onCleared()
    }
}

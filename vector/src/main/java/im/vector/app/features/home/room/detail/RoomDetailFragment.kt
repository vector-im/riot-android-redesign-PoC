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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.Spannable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.text.buildSpannedString
import androidx.core.text.toSpannable
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.view.forEach
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.airbnb.epoxy.addGlidePreloader
import com.airbnb.epoxy.glidePreloader
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.jakewharton.rxbinding3.view.focusChanges
import com.jakewharton.rxbinding3.widget.textChanges
import com.vanniktech.emoji.EmojiPopup
import im.vector.app.R
import im.vector.app.core.dialogs.ConfirmationDialogBuilder
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.withColoredButton
import im.vector.app.core.epoxy.LayoutManagerStateRestorer
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.extensions.showKeyboard
import im.vector.app.core.extensions.trackItemsVisibilityChange
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.ui.views.ActiveConferenceView
import im.vector.app.core.ui.views.CurrentCallsView
import im.vector.app.core.ui.views.FailedMessagesWarningView
import im.vector.app.core.ui.views.JumpToReadMarkerView
import im.vector.app.core.ui.views.KnownCallsViewHolder
import im.vector.app.core.ui.views.NotificationAreaView
import im.vector.app.core.utils.Debouncer
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.core.utils.KeyboardStateUtils
import im.vector.app.core.utils.PERMISSIONS_FOR_WRITING_FILES
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.colorizeMatchingText
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.createJSonViewerStyleProvider
import im.vector.app.core.utils.createUIHandler
import im.vector.app.core.utils.isValidUrl
import im.vector.app.core.utils.openUrlInExternalBrowser
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.core.utils.saveMedia
import im.vector.app.core.utils.shareMedia
import im.vector.app.core.utils.shareText
import im.vector.app.core.utils.startInstallFromSourceIntent
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogReportContentBinding
import im.vector.app.databinding.FragmentRoomDetailBinding
import im.vector.app.features.attachments.AttachmentTypeSelectorView
import im.vector.app.features.attachments.AttachmentsHelper
import im.vector.app.features.attachments.ContactAttachment
import im.vector.app.features.attachments.preview.AttachmentsPreviewActivity
import im.vector.app.features.attachments.preview.AttachmentsPreviewArgs
import im.vector.app.features.attachments.toGroupedContentAttachmentData
import im.vector.app.features.call.SharedKnownCallsViewModel
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.conference.JitsiCallViewModel
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.command.Command
import im.vector.app.features.crypto.keysbackup.restore.KeysBackupRestoreActivity
import im.vector.app.features.crypto.verification.VerificationBottomSheet
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.composer.TextComposerView
import im.vector.app.features.home.room.detail.readreceipts.DisplayReadReceiptsBottomSheet
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.action.EventSharedAction
import im.vector.app.features.home.room.detail.timeline.action.MessageActionsBottomSheet
import im.vector.app.features.home.room.detail.timeline.action.MessageSharedActionViewModel
import im.vector.app.features.home.room.detail.timeline.edithistory.ViewEditHistoryBottomSheet
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.image.buildImageContentRendererData
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.MessageFileItem
import im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.reactions.ViewReactionsBottomSheet
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.home.room.detail.widget.RoomWidgetsBottomSheet
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.invite.VectorInviteView
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.permalink.NavigationInterceptor
import im.vector.app.features.permalink.PermalinkHandler
import im.vector.app.features.reactions.EmojiReactionPickerActivity
import im.vector.app.features.roomprofile.RoomProfileActivity
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.app.features.share.SharedData
import im.vector.app.features.spaces.share.ShareSpaceBottomSheet
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.widgets.WidgetActivity
import im.vector.app.features.widgets.WidgetArgs
import im.vector.app.features.widgets.WidgetKind
import im.vector.app.features.widgets.permissions.RoomWidgetPermissionBottomSheet
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import nl.dionsegijn.konfetti.models.Shape
import nl.dionsegijn.konfetti.models.Size
import org.billcarsonfr.jsonviewer.JSonViewerDialog
import org.commonmark.parser.Parser
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationRequestContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.internal.crypto.model.event.EncryptedEventContent
import org.matrix.android.sdk.internal.crypto.model.event.WithHeldCode
import timber.log.Timber
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@Parcelize
data class RoomDetailArgs(
        val roomId: String,
        val eventId: String? = null,
        val sharedData: SharedData? = null,
        val openShareSpaceForId: String? = null
) : Parcelable

class RoomDetailFragment @Inject constructor(
        private val session: Session,
        private val avatarRenderer: AvatarRenderer,
        private val timelineEventController: TimelineEventController,
        autoCompleterFactory: AutoCompleter.Factory,
        private val permalinkHandler: PermalinkHandler,
        private val notificationDrawerManager: NotificationDrawerManager,
        val roomDetailViewModelFactory: RoomDetailViewModel.Factory,
        private val eventHtmlRenderer: EventHtmlRenderer,
        private val vectorPreferences: VectorPreferences,
        private val colorProvider: ColorProvider,
        private val dimensionConverter: DimensionConverter,
        private val notificationUtils: NotificationUtils,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val imageContentRenderer: ImageContentRenderer,
        private val roomDetailPendingActionStore: RoomDetailPendingActionStore,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val callManager: WebRtcCallManager
) :
        VectorBaseFragment<FragmentRoomDetailBinding>(),
        TimelineEventController.Callback,
        VectorInviteView.Callback,
        JumpToReadMarkerView.Callback,
        AttachmentTypeSelectorView.Callback,
        AttachmentsHelper.Callback,
        GalleryOrCameraDialogHelper.Listener,
        CurrentCallsView.Callback {

    companion object {
        /**
         * Sanitize the display name.
         *
         * @param displayName the display name to sanitize
         * @return the sanitized display name
         */
        private fun sanitizeDisplayName(displayName: String): String {
            if (displayName.endsWith(ircPattern)) {
                return displayName.substring(0, displayName.length - ircPattern.length)
            }

            return displayName
        }

        private const val ircPattern = " (IRC)"
    }

    private val galleryOrCameraDialogHelper = GalleryOrCameraDialogHelper(this, colorProvider)

    private val roomDetailArgs: RoomDetailArgs by args()
    private val glideRequests by lazy {
        GlideApp.with(this)
    }
    private val pillsPostProcessor by lazy {
        pillsPostProcessorFactory.create(roomDetailArgs.roomId)
    }

    private val autoCompleter: AutoCompleter by lazy {
        autoCompleterFactory.create(roomDetailArgs.roomId)
    }
    private val roomDetailViewModel: RoomDetailViewModel by fragmentViewModel()
    private val debouncer = Debouncer(createUIHandler())

    private lateinit var scrollOnNewMessageCallback: ScrollOnNewMessageCallback
    private lateinit var scrollOnHighlightedEventCallback: ScrollOnHighlightedEventCallback

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomDetailBinding {
        return FragmentRoomDetailBinding.inflate(inflater, container, false)
    }

    override fun getMenuRes() = R.menu.menu_timeline

    private lateinit var sharedActionViewModel: MessageSharedActionViewModel
    private lateinit var knownCallsViewModel: SharedKnownCallsViewModel

    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var jumpToBottomViewVisibilityManager: JumpToBottomViewVisibilityManager
    private var modelBuildListener: OnModelBuildFinishedListener? = null

    private lateinit var attachmentsHelper: AttachmentsHelper
    private lateinit var keyboardStateUtils: KeyboardStateUtils
    private lateinit var callActionsHandler: StartCallActionsHandler

    private lateinit var attachmentTypeSelector: AttachmentTypeSelectorView

    private var lockSendButton = false
    private val knownCallsViewHolder = KnownCallsViewHolder()

    private lateinit var emojiPopup: EmojiPopup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedActionViewModel = activityViewModelProvider.get(MessageSharedActionViewModel::class.java)
        knownCallsViewModel = activityViewModelProvider.get(SharedKnownCallsViewModel::class.java)
        attachmentsHelper = AttachmentsHelper(requireContext(), this).register()
        callActionsHandler = StartCallActionsHandler(
                roomId = roomDetailArgs.roomId,
                fragment = this,
                vectorPreferences = vectorPreferences,
                roomDetailViewModel = roomDetailViewModel,
                callManager = callManager,
                startCallActivityResultLauncher = startCallActivityResultLauncher,
                showDialogWithMessage = ::showDialogWithMessage,
                onTapToReturnToCall = ::onTapToReturnToCall
        ).register()
        keyboardStateUtils = KeyboardStateUtils(requireActivity())
        setupToolbar(views.roomToolbar)
        setupRecyclerView()
        setupComposer()
        setupInviteView()
        setupNotificationView()
        setupJumpToReadMarkerView()
        setupActiveCallView()
        setupJumpToBottomView()
        setupConfBannerView()
        setupEmojiPopup()
        setupFailedMessagesWarningView()

        views.roomToolbarContentView.debouncedClicks {
            navigator.openRoomProfile(requireActivity(), roomDetailArgs.roomId)
        }

        sharedActionViewModel
                .observe()
                .subscribe {
                    handleActions(it)
                }
                .disposeOnDestroyView()

        knownCallsViewModel
                .liveKnownCalls
                .observe(viewLifecycleOwner, {
                    knownCallsViewHolder.updateCall(callManager.getCurrentCall(), it)
                    invalidateOptionsMenu()
                })

        roomDetailViewModel.selectSubscribe(this, RoomDetailViewState::tombstoneEventHandling, uniqueOnly("tombstoneEventHandling")) {
            renderTombstoneEventHandling(it)
        }

        roomDetailViewModel.selectSubscribe(RoomDetailViewState::sendMode, RoomDetailViewState::canSendMessage) { mode, canSend ->
            if (!canSend) {
                return@selectSubscribe
            }
            when (mode) {
                is SendMode.REGULAR -> renderRegularMode(mode.text)
                is SendMode.EDIT    -> renderSpecialMode(mode.timelineEvent, R.drawable.ic_edit, R.string.edit, mode.text)
                is SendMode.QUOTE   -> renderSpecialMode(mode.timelineEvent, R.drawable.ic_quote, R.string.quote, mode.text)
                is SendMode.REPLY   -> renderSpecialMode(mode.timelineEvent, R.drawable.ic_reply, R.string.reply, mode.text)
            }
        }

        roomDetailViewModel.selectSubscribe(RoomDetailViewState::syncState) { syncState ->
            views.syncStateView.render(syncState)
        }

        roomDetailViewModel.observeViewEvents {
            when (it) {
                is RoomDetailViewEvents.Failure                          -> showErrorInSnackbar(it.throwable)
                is RoomDetailViewEvents.OnNewTimelineEvents              -> scrollOnNewMessageCallback.addNewTimelineEventIds(it.eventIds)
                is RoomDetailViewEvents.ActionSuccess                    -> displayRoomDetailActionSuccess(it)
                is RoomDetailViewEvents.ActionFailure                    -> displayRoomDetailActionFailure(it)
                is RoomDetailViewEvents.ShowMessage                      -> showSnackWithMessage(it.message)
                is RoomDetailViewEvents.NavigateToEvent                  -> navigateToEvent(it)
                is RoomDetailViewEvents.DownloadFileState                -> handleDownloadFileState(it)
                is RoomDetailViewEvents.JoinRoomCommandSuccess           -> handleJoinedToAnotherRoom(it)
                is RoomDetailViewEvents.SendMessageResult                -> renderSendMessageResult(it)
                is RoomDetailViewEvents.ShowE2EErrorMessage              -> displayE2eError(it.withHeldCode)
                RoomDetailViewEvents.DisplayPromptForIntegrationManager  -> displayPromptForIntegrationManager()
                is RoomDetailViewEvents.OpenStickerPicker                -> openStickerPicker(it)
                is RoomDetailViewEvents.DisplayEnableIntegrationsWarning -> displayDisabledIntegrationDialog()
                is RoomDetailViewEvents.OpenIntegrationManager           -> openIntegrationManager()
                is RoomDetailViewEvents.OpenFile                         -> startOpenFileIntent(it)
                RoomDetailViewEvents.OpenActiveWidgetBottomSheet         -> onViewWidgetsClicked()
                is RoomDetailViewEvents.ShowInfoOkDialog                 -> showDialogWithMessage(it.message)
                is RoomDetailViewEvents.JoinJitsiConference              -> joinJitsiRoom(it.widget, it.withVideo)
                RoomDetailViewEvents.ShowWaitingView                     -> vectorBaseActivity.showWaitingView()
                RoomDetailViewEvents.HideWaitingView                     -> vectorBaseActivity.hideWaitingView()
                is RoomDetailViewEvents.RequestNativeWidgetPermission    -> requestNativeWidgetPermission(it)
                is RoomDetailViewEvents.OpenRoom                         -> handleOpenRoom(it)
                RoomDetailViewEvents.OpenInvitePeople                    -> navigator.openInviteUsersToRoom(requireContext(), roomDetailArgs.roomId)
                RoomDetailViewEvents.OpenSetRoomAvatarDialog             -> galleryOrCameraDialogHelper.show()
                RoomDetailViewEvents.OpenRoomSettings                    -> handleOpenRoomSettings()
                is RoomDetailViewEvents.ShowRoomAvatarFullScreen         -> it.matrixItem?.let { item ->
                    navigator.openBigImageViewer(requireActivity(), it.view, item)
                }
                is RoomDetailViewEvents.StartChatEffect                  -> handleChatEffect(it.type)
                RoomDetailViewEvents.StopChatEffects                     -> handleStopChatEffects()
                is RoomDetailViewEvents.DisplayAndAcceptCall             -> acceptIncomingCall(it)
            }.exhaustive
        }

        if (savedInstanceState == null) {
            handleShareData()
            handleSpaceShare()
        }
    }

    private fun acceptIncomingCall(event: RoomDetailViewEvents.DisplayAndAcceptCall) {
        val intent = VectorCallActivity.newIntent(
                context = vectorBaseActivity,
                call = event.call,
                mode = VectorCallActivity.INCOMING_ACCEPT
        )
        startActivity(intent)
    }

    private fun handleChatEffect(chatEffect: ChatEffect) {
        when (chatEffect) {
            ChatEffect.CONFETTI -> {
                views.viewKonfetti.isVisible = true
                views.viewKonfetti.build()
                        .addColors(Color.YELLOW, Color.GREEN, Color.MAGENTA)
                        .setDirection(0.0, 359.0)
                        .setSpeed(2f, 5f)
                        .setFadeOutEnabled(true)
                        .setTimeToLive(2000L)
                        .addShapes(Shape.Square, Shape.Circle)
                        .addSizes(Size(12))
                        .setPosition(-50f, views.viewKonfetti.width + 50f, -50f, -50f)
                        .streamFor(150, 3000L)
            }
            ChatEffect.SNOW     -> {
                views.viewSnowFall.isVisible = true
                views.viewSnowFall.restartFalling()
            }
        }
    }

    private fun handleStopChatEffects() {
        TransitionManager.beginDelayedTransition(views.rootConstraintLayout)
        views.viewSnowFall.isVisible = false
        // when gone the effect is a bit buggy
        views.viewKonfetti.isInvisible = true
    }

    override fun onImageReady(uri: Uri?) {
        uri ?: return
        roomDetailViewModel.handle(
                RoomDetailAction.SetAvatarAction(
                        newAvatarUri = uri,
                        newAvatarFileName = getFilenameFromUri(requireContext(), uri) ?: UUID.randomUUID().toString()
                )
        )
    }

    private fun handleOpenRoomSettings() {
        navigator.openRoomProfile(
                requireContext(),
                roomDetailArgs.roomId,
                RoomProfileActivity.EXTRA_DIRECT_ACCESS_ROOM_SETTINGS
        )
    }

    private fun handleOpenRoom(openRoom: RoomDetailViewEvents.OpenRoom) {
        navigator.openRoom(requireContext(), openRoom.roomId, null)
    }

    private fun requestNativeWidgetPermission(it: RoomDetailViewEvents.RequestNativeWidgetPermission) {
        val tag = RoomWidgetPermissionBottomSheet::class.java.name
        val dFrag = childFragmentManager.findFragmentByTag(tag) as? RoomWidgetPermissionBottomSheet
        if (dFrag != null && dFrag.dialog?.isShowing == true && !dFrag.isRemoving) {
            return
        } else {
            RoomWidgetPermissionBottomSheet.newInstance(
                    WidgetArgs(
                            baseUrl = it.domain,
                            kind = WidgetKind.ROOM,
                            roomId = roomDetailArgs.roomId,
                            widgetId = it.widget.widgetId
                    )
            ).apply {
                directListener = { granted ->
                    if (granted) {
                        roomDetailViewModel.handle(RoomDetailAction.EnsureNativeWidgetAllowed(
                                widget = it.widget,
                                userJustAccepted = true,
                                grantedEvents = it.grantedEvents
                        ))
                    }
                }
            }
                    .show(childFragmentManager, tag)
        }
    }

    private val integrationManagerActivityResultLauncher = registerStartForActivityResult {
        // Noop
    }

    private fun openIntegrationManager(screen: String? = null) {
        navigator.openIntegrationManager(
                context = requireContext(),
                activityResultLauncher = integrationManagerActivityResultLauncher,
                roomId = roomDetailArgs.roomId,
                integId = null,
                screen = screen
        )
    }

    private fun setupConfBannerView() {
        views.activeConferenceView.callback = object : ActiveConferenceView.Callback {
            override fun onTapJoinAudio(jitsiWidget: Widget) {
                // need to check if allowed first
                roomDetailViewModel.handle(RoomDetailAction.EnsureNativeWidgetAllowed(
                        widget = jitsiWidget,
                        userJustAccepted = false,
                        grantedEvents = RoomDetailViewEvents.JoinJitsiConference(jitsiWidget, false))
                )
            }

            override fun onTapJoinVideo(jitsiWidget: Widget) {
                roomDetailViewModel.handle(RoomDetailAction.EnsureNativeWidgetAllowed(
                        widget = jitsiWidget,
                        userJustAccepted = false,
                        grantedEvents = RoomDetailViewEvents.JoinJitsiConference(jitsiWidget, true))
                )
            }

            override fun onDelete(jitsiWidget: Widget) {
                roomDetailViewModel.handle(RoomDetailAction.RemoveWidget(jitsiWidget.widgetId))
            }
        }
    }

    private fun setupEmojiPopup() {
        emojiPopup = EmojiPopup
                .Builder
                .fromRootView(views.rootConstraintLayout)
                .setKeyboardAnimationStyle(R.style.emoji_fade_animation_style)
                .setOnEmojiPopupShownListener {
                    views.composerLayout.views.composerEmojiButton.let {
                        it.setImageResource(R.drawable.ic_keyboard)
                        it.contentDescription = getString(R.string.a11y_close_emoji_picker)
                    }
                }
                .setOnEmojiPopupDismissListener {
                    views.composerLayout.views.composerEmojiButton.let {
                        it.setImageResource(R.drawable.ic_insert_emoji)
                        it.contentDescription = getString(R.string.a11y_open_emoji_picker)
                    }
                }
                .build(views.composerLayout.views.composerEditText)

        views.composerLayout.views.composerEmojiButton.debouncedClicks {
            emojiPopup.toggle()
        }
    }

    private fun setupFailedMessagesWarningView() {
        views.failedMessagesWarningView.callback = object : FailedMessagesWarningView.Callback {
            override fun onDeleteAllClicked() {
                AlertDialog.Builder(requireContext())
                        .setTitle(R.string.event_status_delete_all_failed_dialog_title)
                        .setMessage(getString(R.string.event_status_delete_all_failed_dialog_message))
                        .setNegativeButton(R.string.no, null)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            roomDetailViewModel.handle(RoomDetailAction.RemoveAllFailedMessages)
                        }
                        .show()
            }

            override fun onRetryClicked() {
                roomDetailViewModel.handle(RoomDetailAction.ResendAll)
            }
        }
    }

    private fun joinJitsiRoom(jitsiWidget: Widget, enableVideo: Boolean) {
        navigator.openRoomWidget(requireContext(), roomDetailArgs.roomId, jitsiWidget, mapOf(JitsiCallViewModel.ENABLE_VIDEO_OPTION to enableVideo))
    }

    private fun openStickerPicker(event: RoomDetailViewEvents.OpenStickerPicker) {
        navigator.openStickerPicker(requireContext(), stickerActivityResultLauncher, roomDetailArgs.roomId, event.widget)
    }

    private fun startOpenFileIntent(action: RoomDetailViewEvents.OpenFile) {
        if (action.mimeType == MimeTypes.Apk) {
            installApk(action)
        } else {
            openFile(action)
        }
    }

    private fun openFile(action: RoomDetailViewEvents.OpenFile) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(action.uri, action.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(requireActivity().packageManager) != null) {
            requireActivity().startActivity(intent)
        } else {
            requireActivity().toast(R.string.error_no_external_application_found)
        }
    }

    private fun installApk(action: RoomDetailViewEvents.OpenFile) {
        val safeContext = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!safeContext.packageManager.canRequestPackageInstalls()) {
                roomDetailViewModel.pendingEvent = action
                startInstallFromSourceIntent(safeContext, installApkActivityResultLauncher)
            } else {
                openFile(action)
            }
        } else {
            openFile(action)
        }
    }

    private val installApkActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            roomDetailViewModel.pendingEvent?.let {
                if (it is RoomDetailViewEvents.OpenFile) {
                    openFile(it)
                }
            }
        } else {
            // User cancelled
        }
        roomDetailViewModel.pendingEvent = null
    }

    private fun displayPromptForIntegrationManager() {
        // The Sticker picker widget is not installed yet. Propose the user to install it
        val builder = AlertDialog.Builder(requireContext())
        val v: View = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_no_sticker_pack, null)
        builder
                .setView(v)
                .setPositiveButton(R.string.yes) { _, _ ->
                    // Open integration manager, to the sticker installation page
                    openIntegrationManager(
                            screen = WidgetType.StickerPicker.preferred
                    )
                }
                .setNegativeButton(R.string.no, null)
                .show()
    }

    private fun handleJoinedToAnotherRoom(action: RoomDetailViewEvents.JoinRoomCommandSuccess) {
        updateComposerText("")
        lockSendButton = false
        navigator.openRoom(vectorBaseActivity, action.roomId)
    }

    private fun handleShareData() {
        when (val sharedData = roomDetailArgs.sharedData) {
            is SharedData.Text        -> {
                roomDetailViewModel.handle(RoomDetailAction.EnterRegularMode(sharedData.text, fromSharing = true))
            }
            is SharedData.Attachments -> {
                // open share edition
                onContentAttachmentsReady(sharedData.attachmentData)
            }
            null                      -> Timber.v("No share data to process")
        }.exhaustive
    }

    private fun handleSpaceShare() {
        roomDetailArgs.openShareSpaceForId?.let { spaceId ->
            ShareSpaceBottomSheet.show(childFragmentManager, spaceId, true)
            view?.post {
                handleChatEffect(ChatEffect.CONFETTI)
            }
        }
    }

    override fun onDestroyView() {
        timelineEventController.callback = null
        timelineEventController.removeModelBuildListener(modelBuildListener)
        views.activeCallView.callback = null
        modelBuildListener = null
        autoCompleter.clear()
        debouncer.cancelAll()
        views.timelineRecyclerView.cleanup()
        emojiPopup.dismiss()

        super.onDestroyView()
    }

    override fun onDestroy() {
        knownCallsViewHolder.unBind()
        roomDetailViewModel.handle(RoomDetailAction.ExitTrackingUnreadMessagesState)
        super.onDestroy()
    }

    private fun setupJumpToBottomView() {
        views.jumpToBottomView.visibility = View.INVISIBLE
        views.jumpToBottomView.debouncedClicks {
            roomDetailViewModel.handle(RoomDetailAction.ExitTrackingUnreadMessagesState)
            views.jumpToBottomView.visibility = View.INVISIBLE
            if (!roomDetailViewModel.timeline.isLive) {
                scrollOnNewMessageCallback.forceScrollOnNextUpdate()
                roomDetailViewModel.timeline.restartWithEventId(null)
            } else {
                layoutManager.scrollToPosition(0)
            }
        }

        jumpToBottomViewVisibilityManager = JumpToBottomViewVisibilityManager(
                views.jumpToBottomView,
                debouncer,
                views.timelineRecyclerView,
                layoutManager
        )
    }

    private fun setupJumpToReadMarkerView() {
        views.jumpToReadMarkerView.callback = this
    }

    private fun setupActiveCallView() {
        knownCallsViewHolder.bind(
                views.activeCallPiP,
                views.activeCallView,
                views.activeCallPiPWrap,
                this
        )
    }

    private fun navigateToEvent(action: RoomDetailViewEvents.NavigateToEvent) {
        val scrollPosition = timelineEventController.searchPositionOfEvent(action.eventId)
        if (scrollPosition == null) {
            scrollOnHighlightedEventCallback.scheduleScrollTo(action.eventId)
        } else {
            views.timelineRecyclerView.stopScroll()
            layoutManager.scrollToPosition(scrollPosition)
        }
    }

    private fun handleDownloadFileState(action: RoomDetailViewEvents.DownloadFileState) {
        val activity = requireActivity()
        if (action.throwable != null) {
            activity.toast(errorFormatter.toHumanReadable(action.throwable))
        }
//        else if (action.file != null) {
//            addEntryToDownloadManager(activity, action.file, action.mimeType ?: "application/octet-stream")?.let {
//                // This is a temporary solution to help users find downloaded files
//                // there is a better way to do that
//                // On android Q+ this method returns the file URI, on older
//                // it returns null, and the download manager handles the notification
//                notificationUtils.buildDownloadFileNotification(
//                        it,
//                        action.file.name ?: "file",
//                        action.mimeType ?: "application/octet-stream"
//                ).let { notification ->
//                    notificationUtils.showNotificationMessage("DL", action.file.absolutePath.hashCode(), notification)
//                }
//            }
//        }
    }

    private fun setupNotificationView() {
        views.notificationAreaView.delegate = object : NotificationAreaView.Delegate {
            override fun onTombstoneEventClicked(tombstoneEvent: Event) {
                roomDetailViewModel.handle(RoomDetailAction.HandleTombstoneEvent(tombstoneEvent))
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        // We use a custom layout for this menu item, so we need to set a ClickListener
        menu.findItem(R.id.open_matrix_apps)?.let { menuItem ->
            menuItem.actionView.setOnClickListener {
                onOptionsItemSelected(menuItem)
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.forEach {
            it.isVisible = roomDetailViewModel.isMenuItemVisible(it.itemId)
        }
        withState(roomDetailViewModel) { state ->
            // Set the visual state of the call buttons (voice/video) to enabled/disabled according to user permissions
            val callButtonsEnabled = when (state.asyncRoomSummary.invoke()?.joinedMembersCount) {
                1    -> false
                2    -> state.isAllowedToStartWebRTCCall
                else -> state.isAllowedToManageWidgets
            }
            setOf(R.id.voice_call, R.id.video_call).forEach {
                menu.findItem(it).icon?.alpha = if (callButtonsEnabled) 0xFF else 0x40
            }

            val matrixAppsMenuItem = menu.findItem(R.id.open_matrix_apps)
            val widgetsCount = state.activeRoomWidgets.invoke()?.size ?: 0
            if (widgetsCount > 0) {
                val actionView = matrixAppsMenuItem.actionView
                actionView
                        .findViewById<ImageView>(R.id.action_view_icon_image)
                        .setColorFilter(ContextCompat.getColor(requireContext(), R.color.riotx_accent))
                actionView.findViewById<TextView>(R.id.cart_badge).setTextOrHide("$widgetsCount")
                matrixAppsMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            } else {
                // icon should be default color no badge
                val actionView = matrixAppsMenuItem.actionView
                actionView
                        .findViewById<ImageView>(R.id.action_view_icon_image)
                        .setColorFilter(ThemeUtils.getColor(requireContext(), R.attr.riotx_text_secondary))
                actionView.findViewById<TextView>(R.id.cart_badge).isVisible = false
                matrixAppsMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.invite           -> {
                navigator.openInviteUsersToRoom(requireActivity(), roomDetailArgs.roomId)
                true
            }
            R.id.timeline_setting -> {
                navigator.openRoomProfile(requireActivity(), roomDetailArgs.roomId)
                true
            }
            R.id.open_matrix_apps -> {
                roomDetailViewModel.handle(RoomDetailAction.ManageIntegrations)
                true
            }
            R.id.voice_call       -> {
                callActionsHandler.onVoiceCallClicked()
                true
            }
            R.id.video_call       -> {
                callActionsHandler.onVideoCallClicked()
                true
            }
            R.id.hangup_call      -> {
                roomDetailViewModel.handle(RoomDetailAction.EndCall)
                true
            }
            R.id.search           -> {
                handleSearchAction()
                true
            }
            R.id.dev_tools        -> {
                navigator.openDevTools(requireContext(), roomDetailArgs.roomId)
                true
            }
            else                  -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleSearchAction() {
        if (session.getRoom(roomDetailArgs.roomId)?.isEncrypted() == false) {
            navigator.openSearch(requireContext(), roomDetailArgs.roomId)
        } else {
            showDialogWithMessage(getString(R.string.search_is_not_supported_in_e2e_room))
        }
    }

    private fun displayDisabledIntegrationDialog() {
        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.disabled_integration_dialog_title)
                .setMessage(R.string.disabled_integration_dialog_content)
                .setPositiveButton(R.string.settings) { _, _ ->
                    navigator.openSettings(requireActivity(), VectorSettingsActivity.EXTRA_DIRECT_ACCESS_GENERAL)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun renderRegularMode(text: String) {
        autoCompleter.exitSpecialMode()
        views.composerLayout.collapse()

        updateComposerText(text)
        views.composerLayout.views.sendButton.contentDescription = getString(R.string.send)
    }

    private fun renderSpecialMode(event: TimelineEvent,
                                  @DrawableRes iconRes: Int,
                                  @StringRes descriptionRes: Int,
                                  defaultContent: String) {
        autoCompleter.enterSpecialMode()
        // switch to expanded bar
        views.composerLayout.views.composerRelatedMessageTitle.apply {
            text = event.senderInfo.disambiguatedDisplayName
            setTextColor(matrixItemColorProvider.getColor(MatrixItem.UserItem(event.root.senderId ?: "@")))
        }

        val messageContent: MessageContent? = event.getLastMessageContent()
        val nonFormattedBody = messageContent?.body ?: ""
        var formattedBody: CharSequence? = null
        if (messageContent is MessageTextContent && messageContent.format == MessageFormat.FORMAT_MATRIX_HTML) {
            val parser = Parser.builder().build()
            val document = parser.parse(messageContent.formattedBody ?: messageContent.body)
            formattedBody = eventHtmlRenderer.render(document, pillsPostProcessor)
        }
        views.composerLayout.views.composerRelatedMessageContent.text = (formattedBody ?: nonFormattedBody)

        // Image Event
        val data = event.buildImageContentRendererData(dimensionConverter.dpToPx(66))
        val isImageVisible = if (data != null) {
            imageContentRenderer.render(data, ImageContentRenderer.Mode.THUMBNAIL, views.composerLayout.views.composerRelatedMessageImage)
            true
        } else {
            false
        }

        updateComposerText(defaultContent)

        views.composerLayout.views.composerRelatedMessageActionIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), iconRes))
        views.composerLayout.views.sendButton.contentDescription = getString(descriptionRes)

        avatarRenderer.render(event.senderInfo.toMatrixItem(), views.composerLayout.views.composerRelatedMessageAvatar)

        views.composerLayout.expand {
            if (isAdded) {
                // need to do it here also when not using quick reply
                focusComposerAndShowKeyboard()
                views.composerLayout.views.composerRelatedMessageImage.isVisible = isImageVisible
            }
        }
        focusComposerAndShowKeyboard()
    }

    private fun updateComposerText(text: String) {
        // Do not update if this is the same text to avoid the cursor to move
        if (text != views.composerLayout.text.toString()) {
            // Ignore update to avoid saving a draft
            views.composerLayout.views.composerEditText.setText(text)
            views.composerLayout.views.composerEditText.setSelection(views.composerLayout.text?.length ?: 0)
        }
    }

    override fun onResume() {
        super.onResume()
        notificationDrawerManager.setCurrentRoom(roomDetailArgs.roomId)
        roomDetailPendingActionStore.data?.let { handlePendingAction(it) }
        roomDetailPendingActionStore.data = null
    }

    private fun handlePendingAction(roomDetailPendingAction: RoomDetailPendingAction) {
        when (roomDetailPendingAction) {
            is RoomDetailPendingAction.JumpToReadReceipt ->
                roomDetailViewModel.handle(RoomDetailAction.JumpToReadReceipt(roomDetailPendingAction.userId))
            is RoomDetailPendingAction.MentionUser       ->
                insertUserDisplayNameInTextEditor(roomDetailPendingAction.userId)
            is RoomDetailPendingAction.OpenOrCreateDm    ->
                roomDetailViewModel.handle(RoomDetailAction.OpenOrCreateDm(roomDetailPendingAction.userId))
        }.exhaustive
    }

    override fun onPause() {
        super.onPause()

        notificationDrawerManager.setCurrentRoom(null)

        roomDetailViewModel.handle(RoomDetailAction.SaveDraft(views.composerLayout.text.toString()))
    }

    private val attachmentFileActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onFileResult(it.data)
        }
    }

    private val attachmentAudioActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onAudioResult(it.data)
        }
    }

    private val attachmentContactActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onContactResult(it.data)
        }
    }

    private val attachmentMediaActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onMediaResult(it.data)
        }
    }

    private val attachmentCameraActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onCameraResult()
        }
    }

    private val attachmentCameraVideoActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onCameraVideoResult()
        }
    }

    private val contentAttachmentActivityResultLauncher = registerStartForActivityResult { activityResult ->
        val data = activityResult.data ?: return@registerStartForActivityResult
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val sendData = AttachmentsPreviewActivity.getOutput(data)
            val keepOriginalSize = AttachmentsPreviewActivity.getKeepOriginalSize(data)
            roomDetailViewModel.handle(RoomDetailAction.SendMedia(sendData, !keepOriginalSize))
        }
    }

    private val emojiActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val eventId = EmojiReactionPickerActivity.getOutputEventId(activityResult.data)
            val reaction = EmojiReactionPickerActivity.getOutputReaction(activityResult.data)
            if (eventId != null && reaction != null) {
                roomDetailViewModel.handle(RoomDetailAction.SendReaction(eventId, reaction))
            }
        }
    }

    private val stickerActivityResultLauncher = registerStartForActivityResult { activityResult ->
        val data = activityResult.data ?: return@registerStartForActivityResult
        if (activityResult.resultCode == Activity.RESULT_OK) {
            WidgetActivity.getOutput(data).toModel<MessageStickerContent>()
                    ?.let { content ->
                        roomDetailViewModel.handle(RoomDetailAction.SendSticker(content))
                    }
        }
    }

    private val startCallActivityResultLauncher = registerForPermissionsResult { allGranted ->
        if (allGranted) {
            (roomDetailViewModel.pendingAction as? RoomDetailAction.StartCall)?.let {
                roomDetailViewModel.pendingAction = null
                roomDetailViewModel.handle(it)
            }
        } else {
            context?.toast(R.string.permissions_action_not_performed_missing_permissions)
            cleanUpAfterPermissionNotGranted()
        }
    }

// PRIVATE METHODS *****************************************************************************

    private fun setupRecyclerView() {
        timelineEventController.callback = this
        timelineEventController.timeline = roomDetailViewModel.timeline

        views.timelineRecyclerView.trackItemsVisibilityChange()
        layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, true)
        val stateRestorer = LayoutManagerStateRestorer(layoutManager).register()
        scrollOnNewMessageCallback = ScrollOnNewMessageCallback(layoutManager, timelineEventController)
        scrollOnHighlightedEventCallback = ScrollOnHighlightedEventCallback(views.timelineRecyclerView, layoutManager, timelineEventController)
        views.timelineRecyclerView.layoutManager = layoutManager
        views.timelineRecyclerView.itemAnimator = null
        views.timelineRecyclerView.setHasFixedSize(true)
        modelBuildListener = OnModelBuildFinishedListener {
            it.dispatchTo(stateRestorer)
            it.dispatchTo(scrollOnNewMessageCallback)
            it.dispatchTo(scrollOnHighlightedEventCallback)
            updateJumpToReadMarkerViewVisibility()
            jumpToBottomViewVisibilityManager.maybeShowJumpToBottomViewVisibilityWithDelay()
        }
        timelineEventController.addModelBuildListener(modelBuildListener)
        views.timelineRecyclerView.adapter = timelineEventController.adapter

        if (vectorPreferences.swipeToReplyIsEnabled()) {
            val quickReplyHandler = object : RoomMessageTouchHelperCallback.QuickReplayHandler {
                override fun performQuickReplyOnHolder(model: EpoxyModel<*>) {
                    (model as? AbsMessageItem)?.attributes?.informationData?.let {
                        val eventId = it.eventId
                        roomDetailViewModel.handle(RoomDetailAction.EnterReplyMode(eventId, views.composerLayout.text.toString()))
                    }
                }

                override fun canSwipeModel(model: EpoxyModel<*>): Boolean {
                    val canSendMessage = withState(roomDetailViewModel) {
                        it.canSendMessage
                    }
                    if (!canSendMessage) {
                        return false
                    }
                    return when (model) {
                        is MessageFileItem,
                        is MessageImageVideoItem,
                        is MessageTextItem -> {
                            return (model as AbsMessageItem).attributes.informationData.sendState == SendState.SYNCED
                        }
                        else               -> false
                    }
                }
            }
            val swipeCallback = RoomMessageTouchHelperCallback(requireContext(), R.drawable.ic_reply, quickReplyHandler)
            val touchHelper = ItemTouchHelper(swipeCallback)
            touchHelper.attachToRecyclerView(views.timelineRecyclerView)
        }
        views.timelineRecyclerView.addGlidePreloader(
                epoxyController = timelineEventController,
                requestManager = GlideApp.with(this),
                preloader = glidePreloader { requestManager, epoxyModel: MessageImageVideoItem, _ ->
                    imageContentRenderer.createGlideRequest(
                            epoxyModel.mediaData,
                            ImageContentRenderer.Mode.THUMBNAIL,
                            requestManager as GlideRequests
                    )
                })
    }

    private fun updateJumpToReadMarkerViewVisibility() {
        viewLifecycleOwner.lifecycleScope.launchWhenResumed {
            withState(roomDetailViewModel) {
                val showJumpToUnreadBanner = when (it.unreadState) {
                    UnreadState.Unknown,
                    UnreadState.HasNoUnread            -> false
                    is UnreadState.ReadMarkerNotLoaded -> true
                    is UnreadState.HasUnread           -> {
                        if (it.canShowJumpToReadMarker) {
                            val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                            val positionOfReadMarker = timelineEventController.getPositionOfReadMarker()
                            if (positionOfReadMarker == null) {
                                false
                            } else {
                                positionOfReadMarker > lastVisibleItem
                            }
                        } else {
                            false
                        }
                    }
                }
                views.jumpToReadMarkerView.isVisible = showJumpToUnreadBanner
            }
        }
    }

    private fun setupComposer() {
        val composerEditText = views.composerLayout.views.composerEditText
        autoCompleter.setup(composerEditText)

        observerUserTyping()

        if (vectorPreferences.sendMessageWithEnter()) {
            // imeOptions="actionSend" only works with single line, so we remove multiline inputType
            composerEditText.inputType = composerEditText.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE.inv()
            composerEditText.imeOptions = EditorInfo.IME_ACTION_SEND
        }

        composerEditText.setOnEditorActionListener { v, actionId, keyEvent ->
            val imeActionId = actionId and EditorInfo.IME_MASK_ACTION
            if (EditorInfo.IME_ACTION_DONE == imeActionId || EditorInfo.IME_ACTION_SEND == imeActionId) {
                sendTextMessage(v.text)
                true
            }
            // Add external keyboard functionality (to send messages)
            else if (null != keyEvent
                    && !keyEvent.isShiftPressed
                    && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                    && resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS) {
                sendTextMessage(v.text)
                true
            } else false
        }

        views.composerLayout.views.composerEmojiButton.isVisible = vectorPreferences.showEmojiKeyboard()

        views.composerLayout.callback = object : TextComposerView.Callback {
            override fun onAddAttachment() {
                if (!::attachmentTypeSelector.isInitialized) {
                    attachmentTypeSelector = AttachmentTypeSelectorView(vectorBaseActivity, vectorBaseActivity.layoutInflater, this@RoomDetailFragment)
                }
                attachmentTypeSelector.show(views.composerLayout.views.attachmentButton, keyboardStateUtils.isKeyboardShowing)
            }

            override fun onSendMessage(text: CharSequence) {
                sendTextMessage(text)
            }

            override fun onCloseRelatedMessage() {
                roomDetailViewModel.handle(RoomDetailAction.EnterRegularMode(views.composerLayout.text.toString(), false))
            }

            override fun onRichContentSelected(contentUri: Uri): Boolean {
                return sendUri(contentUri)
            }
        }
    }

    private fun sendTextMessage(text: CharSequence) {
        if (lockSendButton) {
            Timber.w("Send button is locked")
            return
        }
        if (text.isNotBlank()) {
            // We collapse ASAP, if not there will be a slight anoying delay
            views.composerLayout.collapse(true)
            lockSendButton = true
            roomDetailViewModel.handle(RoomDetailAction.SendMessage(text, vectorPreferences.isMarkdownEnabled()))
        }
    }

    private fun observerUserTyping() {
        views.composerLayout.views.composerEditText.textChanges()
                .skipInitialValue()
                .debounce(300, TimeUnit.MILLISECONDS)
                .map { it.isNotEmpty() }
                .subscribe {
                    Timber.d("Typing: User is typing: $it")
                    roomDetailViewModel.handle(RoomDetailAction.UserIsTyping(it))
                }
                .disposeOnDestroyView()

        views.composerLayout.views.composerEditText.focusChanges()
                .subscribe {
                    roomDetailViewModel.handle(RoomDetailAction.ComposerFocusChange(it))
                }
                .disposeOnDestroyView()
    }

    private fun sendUri(uri: Uri): Boolean {
        val shareIntent = Intent(Intent.ACTION_SEND, uri)
        val isHandled = attachmentsHelper.handleShareIntent(requireContext(), shareIntent)
        if (!isHandled) {
            Toast.makeText(requireContext(), R.string.error_handling_incoming_share, Toast.LENGTH_SHORT).show()
        }
        return isHandled
    }

    private fun setupInviteView() {
        views.inviteView.callback = this
    }

    override fun invalidate() = withState(roomDetailViewModel) { state ->
        invalidateOptionsMenu()
        val summary = state.asyncRoomSummary()
        renderToolbar(summary, state.typingMessage)
        views.activeConferenceView.render(state)
        views.failedMessagesWarningView.render(state.hasFailedSending)
        val inviter = state.asyncInviter()
        if (summary?.membership == Membership.JOIN) {
            views.jumpToBottomView.count = summary.notificationCount
            views.jumpToBottomView.drawBadge = summary.hasUnreadMessages
            timelineEventController.update(state)
            views.inviteView.visibility = View.GONE
            if (state.tombstoneEvent == null) {
                if (state.canSendMessage) {
                    views.composerLayout.visibility = View.VISIBLE
                    views.composerLayout.setRoomEncrypted(summary.isEncrypted, summary.roomEncryptionTrustLevel)
                    views.notificationAreaView.render(NotificationAreaView.State.Hidden)
                } else {
                    views.composerLayout.visibility = View.GONE
                    views.notificationAreaView.render(NotificationAreaView.State.NoPermissionToPost)
                }
            } else {
                views.composerLayout.visibility = View.GONE
                views.notificationAreaView.render(NotificationAreaView.State.Tombstone(state.tombstoneEvent))
            }
        } else if (summary?.membership == Membership.INVITE && inviter != null) {
            views.inviteView.visibility = View.VISIBLE
            views.inviteView.render(inviter, VectorInviteView.Mode.LARGE, state.changeMembershipState)
            // Intercept click event
            views.inviteView.setOnClickListener { }
        } else if (state.asyncInviter.complete) {
            vectorBaseActivity.finish()
        }
    }

    private fun renderToolbar(roomSummary: RoomSummary?, typingMessage: String?) {
        if (roomSummary == null) {
            views.roomToolbarContentView.isClickable = false
        } else {
            views.roomToolbarContentView.isClickable = roomSummary.membership == Membership.JOIN
            views.roomToolbarTitleView.text = roomSummary.displayName
            avatarRenderer.render(roomSummary.toMatrixItem(), views.roomToolbarAvatarImageView)

            renderSubTitle(typingMessage, roomSummary.topic)
            views.roomToolbarDecorationImageView.render(roomSummary.roomEncryptionTrustLevel)
        }
    }

    private fun renderSubTitle(typingMessage: String?, topic: String) {
        // TODO Temporary place to put typing data
        val subtitle = typingMessage?.takeIf { it.isNotBlank() } ?: topic
        views.roomToolbarSubtitleView.apply {
            setTextOrHide(subtitle)
            if (typingMessage.isNullOrBlank()) {
                setTextColor(ThemeUtils.getColor(requireContext(), R.attr.vctr_toolbar_secondary_text_color))
                setTypeface(null, Typeface.NORMAL)
            } else {
                setTextColor(ContextCompat.getColor(requireContext(), R.color.riotx_accent))
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun renderTombstoneEventHandling(async: Async<String>) {
        when (async) {
            is Loading -> {
                // TODO Better handling progress
                vectorBaseActivity.showWaitingView(getString(R.string.joining_room))
            }
            is Success -> {
                navigator.openRoom(vectorBaseActivity, async())
                vectorBaseActivity.finish()
            }
            is Fail    -> {
                vectorBaseActivity.hideWaitingView()
                vectorBaseActivity.toast(errorFormatter.toHumanReadable(async.error))
            }
        }
    }

    private fun renderSendMessageResult(sendMessageResult: RoomDetailViewEvents.SendMessageResult) {
        when (sendMessageResult) {
            is RoomDetailViewEvents.SlashCommandHandled        -> {
                sendMessageResult.messageRes?.let { showSnackWithMessage(getString(it)) }
            }
            is RoomDetailViewEvents.SlashCommandError          -> {
                displayCommandError(getString(R.string.command_problem_with_parameters, sendMessageResult.command.command))
            }
            is RoomDetailViewEvents.SlashCommandUnknown        -> {
                displayCommandError(getString(R.string.unrecognized_command, sendMessageResult.command))
            }
            is RoomDetailViewEvents.SlashCommandResultOk       -> {
                updateComposerText("")
            }
            is RoomDetailViewEvents.SlashCommandResultError    -> {
                displayCommandError(errorFormatter.toHumanReadable(sendMessageResult.throwable))
            }
            is RoomDetailViewEvents.SlashCommandNotImplemented -> {
                displayCommandError(getString(R.string.not_implemented))
            }
        } // .exhaustive

        lockSendButton = false
    }

    private fun displayCommandError(message: String) {
        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.command_error)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    private fun displayE2eError(withHeldCode: WithHeldCode?) {
        val msgId = when (withHeldCode) {
            WithHeldCode.BLACKLISTED -> R.string.crypto_error_withheld_blacklisted
            WithHeldCode.UNVERIFIED  -> R.string.crypto_error_withheld_unverified
            WithHeldCode.UNAUTHORISED,
            WithHeldCode.UNAVAILABLE -> R.string.crypto_error_withheld_generic
            else                     -> R.string.notice_crypto_unable_to_decrypt_friendly_desc
        }
        AlertDialog.Builder(requireActivity())
                .setMessage(msgId)
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    private fun promptReasonToReportContent(action: EventSharedAction.ReportContentCustom) {
        val inflater = requireActivity().layoutInflater
        val layout = inflater.inflate(R.layout.dialog_report_content, null)
        val views = DialogReportContentBinding.bind(layout)

        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.report_content_custom_title)
                .setView(layout)
                .setPositiveButton(R.string.report_content_custom_submit) { _, _ ->
                    val reason = views.dialogReportContentInput.text.toString()
                    roomDetailViewModel.handle(RoomDetailAction.ReportContent(action.eventId, action.senderId, reason))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun promptConfirmationToRedactEvent(action: EventSharedAction.Redact) {
        ConfirmationDialogBuilder
                .show(
                        activity = requireActivity(),
                        askForReason = action.askForReason,
                        confirmationRes = R.string.delete_event_dialog_content,
                        positiveRes = R.string.remove,
                        reasonHintRes = R.string.delete_event_dialog_reason_hint,
                        titleRes = R.string.delete_event_dialog_title
                ) { reason ->
                    roomDetailViewModel.handle(RoomDetailAction.RedactAction(action.eventId, reason))
                }
    }

    private fun displayRoomDetailActionFailure(result: RoomDetailViewEvents.ActionFailure) {
        AlertDialog.Builder(requireActivity())
                .setTitle(R.string.dialog_title_error)
                .setMessage(errorFormatter.toHumanReadable(result.throwable))
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    private fun displayRoomDetailActionSuccess(result: RoomDetailViewEvents.ActionSuccess) {
        when (val data = result.action) {
            is RoomDetailAction.ReportContent             -> {
                when {
                    data.spam          -> {
                        AlertDialog.Builder(requireActivity())
                                .setTitle(R.string.content_reported_as_spam_title)
                                .setMessage(R.string.content_reported_as_spam_content)
                                .setPositiveButton(R.string.ok, null)
                                .setNegativeButton(R.string.block_user) { _, _ ->
                                    roomDetailViewModel.handle(RoomDetailAction.IgnoreUser(data.senderId))
                                }
                                .show()
                                .withColoredButton(DialogInterface.BUTTON_NEGATIVE)
                    }
                    data.inappropriate -> {
                        AlertDialog.Builder(requireActivity())
                                .setTitle(R.string.content_reported_as_inappropriate_title)
                                .setMessage(R.string.content_reported_as_inappropriate_content)
                                .setPositiveButton(R.string.ok, null)
                                .setNegativeButton(R.string.block_user) { _, _ ->
                                    roomDetailViewModel.handle(RoomDetailAction.IgnoreUser(data.senderId))
                                }
                                .show()
                                .withColoredButton(DialogInterface.BUTTON_NEGATIVE)
                    }
                    else               -> {
                        AlertDialog.Builder(requireActivity())
                                .setTitle(R.string.content_reported_title)
                                .setMessage(R.string.content_reported_content)
                                .setPositiveButton(R.string.ok, null)
                                .setNegativeButton(R.string.block_user) { _, _ ->
                                    roomDetailViewModel.handle(RoomDetailAction.IgnoreUser(data.senderId))
                                }
                                .show()
                                .withColoredButton(DialogInterface.BUTTON_NEGATIVE)
                    }
                }
            }
            is RoomDetailAction.RequestVerification       -> {
                Timber.v("## SAS RequestVerification action")
                VerificationBottomSheet.withArgs(
                        roomDetailArgs.roomId,
                        data.userId
                ).show(parentFragmentManager, "REQ")
            }
            is RoomDetailAction.AcceptVerificationRequest -> {
                Timber.v("## SAS AcceptVerificationRequest action")
                VerificationBottomSheet.withArgs(
                        roomDetailArgs.roomId,
                        data.otherUserId,
                        data.transactionId
                ).show(parentFragmentManager, "REQ")
            }
            is RoomDetailAction.ResumeVerification        -> {
                val otherUserId = data.otherUserId ?: return
                VerificationBottomSheet().apply {
                    arguments = Bundle().apply {
                        putParcelable(MvRx.KEY_ARG, VerificationBottomSheet.VerificationArgs(
                                otherUserId, data.transactionId, roomId = roomDetailArgs.roomId))
                    }
                }.show(parentFragmentManager, "REQ")
            }
        }
    }

// TimelineEventController.Callback ************************************************************

    override fun onUrlClicked(url: String, title: String): Boolean {
        permalinkHandler
                .launch(requireActivity(), url, object : NavigationInterceptor {
                    override fun navToRoom(roomId: String?, eventId: String?, deepLink: Uri?): Boolean {
                        // Same room?
                        if (roomId == roomDetailArgs.roomId) {
                            // Navigation to same room
                            if (eventId == null) {
                                showSnackWithMessage(getString(R.string.navigate_to_room_when_already_in_the_room))
                            } else {
                                // Highlight and scroll to this event
                                roomDetailViewModel.handle(RoomDetailAction.NavigateToEvent(eventId, true))
                            }
                            return true
                        }
                        // Not handled
                        return false
                    }

                    override fun navToMemberProfile(userId: String, deepLink: Uri): Boolean {
                        openRoomMemberProfile(userId)
                        return true
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { managed ->
                    if (!managed) {
                        if (title.isValidUrl() && url.isValidUrl() && URL(title).host != URL(url).host) {
                            AlertDialog.Builder(requireActivity())
                                    .setTitle(R.string.external_link_confirmation_title)
                                    .setMessage(
                                            getString(R.string.external_link_confirmation_message, title, url)
                                                    .toSpannable()
                                                    .colorizeMatchingText(url, colorProvider.getColorFromAttribute(R.attr.riotx_text_primary_body_contrast))
                                                    .colorizeMatchingText(title, colorProvider.getColorFromAttribute(R.attr.riotx_text_primary_body_contrast))
                                    )
                                    .setPositiveButton(R.string._continue) { _, _ ->
                                        openUrlInExternalBrowser(requireContext(), url)
                                    }
                                    .setNegativeButton(R.string.cancel, null)
                                    .show()
                                    .withColoredButton(DialogInterface.BUTTON_NEGATIVE)
                        } else {
                            // Open in external browser, in a new Tab
                            openUrlInExternalBrowser(requireContext(), url)
                        }
                    }
                }
                .disposeOnDestroyView()
        // In fact it is always managed
        return true
    }

    override fun onUrlLongClicked(url: String): Boolean {
        if (url != getString(R.string.edited_suffix) && url.isValidUrl()) {
            // Copy the url to the clipboard
            copyToClipboard(requireContext(), url, true, R.string.link_copied_to_clipboard)
        }
        return true
    }

    override fun onEventVisible(event: TimelineEvent) {
        roomDetailViewModel.handle(RoomDetailAction.TimelineEventTurnsVisible(event))
    }

    override fun onEventInvisible(event: TimelineEvent) {
        roomDetailViewModel.handle(RoomDetailAction.TimelineEventTurnsInvisible(event))
    }

    override fun onEncryptedMessageClicked(informationData: MessageInformationData, view: View) {
        vectorBaseActivity.notImplemented("encrypted message click")
    }

    override fun onImageMessageClicked(messageImageContent: MessageImageInfoContent, mediaData: ImageContentRenderer.Data, view: View) {
        navigator.openMediaViewer(
                activity = requireActivity(),
                roomId = roomDetailArgs.roomId,
                mediaData = mediaData,
                view = view
        ) { pairs ->
            pairs.add(Pair(views.roomToolbar, ViewCompat.getTransitionName(views.roomToolbar) ?: ""))
            pairs.add(Pair(views.composerLayout, ViewCompat.getTransitionName(views.composerLayout) ?: ""))
        }
    }

    override fun onVideoMessageClicked(messageVideoContent: MessageVideoContent, mediaData: VideoContentRenderer.Data, view: View) {
        navigator.openMediaViewer(
                activity = requireActivity(),
                roomId = roomDetailArgs.roomId,
                mediaData = mediaData,
                view = view
        ) { pairs ->
            pairs.add(Pair(views.roomToolbar, ViewCompat.getTransitionName(views.roomToolbar) ?: ""))
            pairs.add(Pair(views.composerLayout, ViewCompat.getTransitionName(views.composerLayout) ?: ""))
        }
    }

//    override fun onFileMessageClicked(eventId: String, messageFileContent: MessageFileContent) {
//        val isEncrypted = messageFileContent.encryptedFileInfo != null
//        val action = RoomDetailAction.DownloadOrOpen(eventId, messageFileContent, isEncrypted)
//        // We need WRITE_EXTERNAL permission
// //        if (!isEncrypted || checkPermissions(PERMISSIONS_FOR_WRITING_FILES, this, PERMISSION_REQUEST_CODE_DOWNLOAD_FILE)) {
//            showSnackWithMessage(getString(R.string.downloading_file, messageFileContent.getFileName()))
//            roomDetailViewModel.handle(action)
// //        } else {
// //            roomDetailViewModel.pendingAction = action
// //        }
//    }

    private fun cleanUpAfterPermissionNotGranted() {
        // Reset all pending data
        roomDetailViewModel.pendingAction = null
        attachmentsHelper.pendingType = null
    }

//    override fun onAudioMessageClicked(messageAudioContent: MessageAudioContent) {
//        vectorBaseActivity.notImplemented("open audio file")
//    }

    override fun onLoadMore(direction: Timeline.Direction) {
        roomDetailViewModel.handle(RoomDetailAction.LoadMoreTimelineEvents(direction))
    }

    override fun onEventCellClicked(informationData: MessageInformationData, messageContent: Any?, view: View) {
        when (messageContent) {
            is MessageVerificationRequestContent -> {
                roomDetailViewModel.handle(RoomDetailAction.ResumeVerification(informationData.eventId, null))
            }
            is MessageWithAttachmentContent      -> {
                val action = RoomDetailAction.DownloadOrOpen(informationData.eventId, informationData.senderId, messageContent)
                roomDetailViewModel.handle(action)
            }
            is EncryptedEventContent             -> {
                roomDetailViewModel.handle(RoomDetailAction.TapOnFailedToDecrypt(informationData.eventId))
            }
        }
    }

    override fun onEventLongClicked(informationData: MessageInformationData, messageContent: Any?, view: View): Boolean {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val roomId = roomDetailArgs.roomId

        this.view?.hideKeyboard()

        MessageActionsBottomSheet
                .newInstance(roomId, informationData)
                .show(requireActivity().supportFragmentManager, "MESSAGE_CONTEXTUAL_ACTIONS")

        return true
    }

    private fun handleCancelSend(action: EventSharedAction.Cancel) {
        if (action.force) {
            roomDetailViewModel.handle(RoomDetailAction.CancelSend(action.eventId, true))
        } else {
            AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialog_title_confirmation)
                    .setMessage(getString(R.string.event_status_cancel_sending_dialog_message))
                    .setNegativeButton(R.string.no, null)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        roomDetailViewModel.handle(RoomDetailAction.CancelSend(action.eventId, false))
                    }
                    .show()
        }
    }

    override fun onAvatarClicked(informationData: MessageInformationData) {
        // roomDetailViewModel.handle(RoomDetailAction.RequestVerification(informationData.userId))
        openRoomMemberProfile(informationData.senderId)
    }

    private fun openRoomMemberProfile(userId: String) {
        navigator.openRoomMemberProfile(userId = userId, roomId = roomDetailArgs.roomId, context = requireActivity())
    }

    override fun onMemberNameClicked(informationData: MessageInformationData) {
        insertUserDisplayNameInTextEditor(informationData.senderId)
    }

    override fun onClickOnReactionPill(informationData: MessageInformationData, reaction: String, on: Boolean) {
        if (on) {
            // we should test the current real state of reaction on this event
            roomDetailViewModel.handle(RoomDetailAction.SendReaction(informationData.eventId, reaction))
        } else {
            // I need to redact a reaction
            roomDetailViewModel.handle(RoomDetailAction.UndoReaction(informationData.eventId, reaction))
        }
    }

    override fun onLongClickOnReactionPill(informationData: MessageInformationData, reaction: String) {
        ViewReactionsBottomSheet.newInstance(roomDetailArgs.roomId, informationData)
                .show(requireActivity().supportFragmentManager, "DISPLAY_REACTIONS")
    }

    override fun onEditedDecorationClicked(informationData: MessageInformationData) {
        ViewEditHistoryBottomSheet.newInstance(roomDetailArgs.roomId, informationData)
                .show(requireActivity().supportFragmentManager, "DISPLAY_EDITS")
    }

    override fun onTimelineItemAction(itemAction: RoomDetailAction) {
        roomDetailViewModel.handle(itemAction)
    }

    override fun getPreviewUrlRetriever(): PreviewUrlRetriever {
        return roomDetailViewModel.previewUrlRetriever
    }

    override fun onRoomCreateLinkClicked(url: String) {
        permalinkHandler
                .launch(requireContext(), url, object : NavigationInterceptor {
                    override fun navToRoom(roomId: String?, eventId: String?, deepLink: Uri?): Boolean {
                        requireActivity().finish()
                        return false
                    }
                })
                .subscribe()
                .disposeOnDestroyView()
    }

    override fun onReadReceiptsClicked(readReceipts: List<ReadReceiptData>) {
        DisplayReadReceiptsBottomSheet.newInstance(readReceipts)
                .show(requireActivity().supportFragmentManager, "DISPLAY_READ_RECEIPTS")
    }

    override fun onReadMarkerVisible() {
        updateJumpToReadMarkerViewVisibility()
        roomDetailViewModel.handle(RoomDetailAction.EnterTrackingUnreadMessagesState)
    }

    override fun onPreviewUrlClicked(url: String) {
        onUrlClicked(url, url)
    }

    override fun onPreviewUrlCloseClicked(eventId: String, url: String) {
        roomDetailViewModel.handle(RoomDetailAction.DoNotShowPreviewUrlFor(eventId, url))
    }

    override fun onPreviewUrlImageClicked(sharedView: View?, mxcUrl: String?, title: String?) {
        navigator.openBigImageViewer(requireActivity(), sharedView, mxcUrl, title)
    }

    private fun onShareActionClicked(action: EventSharedAction.Share) {
        if (action.messageContent is MessageTextContent) {
            shareText(requireContext(), action.messageContent.body)
        } else if (action.messageContent is MessageWithAttachmentContent) {
            lifecycleScope.launch {
                val result = runCatching { session.fileService().downloadFile(messageContent = action.messageContent) }
                if (!isAdded) return@launch
                result.fold(
                        { shareMedia(requireContext(), it, getMimeTypeFromUri(requireContext(), it.toUri())) },
                        { showErrorInSnackbar(it) }
                )
            }
        }
    }

    private val saveActionActivityResultLauncher = registerForPermissionsResult { allGranted ->
        if (allGranted) {
            sharedActionViewModel.pendingAction?.let {
                handleActions(it)
                sharedActionViewModel.pendingAction = null
            }
        } else {
            cleanUpAfterPermissionNotGranted()
        }
    }

    private fun onSaveActionClicked(action: EventSharedAction.Save) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && !checkPermissions(PERMISSIONS_FOR_WRITING_FILES, requireActivity(), saveActionActivityResultLauncher)) {
            sharedActionViewModel.pendingAction = action
            return
        }
        session.coroutineScope.launch {
            val result = runCatching { session.fileService().downloadFile(messageContent = action.messageContent) }
            if (!isAdded) return@launch
            result.mapCatching {
                saveMedia(
                        context = requireContext(),
                        file = it,
                        title = action.messageContent.body,
                        mediaMimeType = action.messageContent.mimeType ?: getMimeTypeFromUri(requireContext(), it.toUri()),
                        notificationUtils = notificationUtils
                )
            }
                    .onFailure {
                        if (!isAdded) return@onFailure
                        showErrorInSnackbar(it)
                    }
        }
    }

    private fun handleActions(action: EventSharedAction) {
        when (action) {
            is EventSharedAction.OpenUserProfile            -> {
                openRoomMemberProfile(action.userId)
            }
            is EventSharedAction.AddReaction                -> {
                emojiActivityResultLauncher.launch(EmojiReactionPickerActivity.intent(requireContext(), action.eventId))
            }
            is EventSharedAction.ViewReactions              -> {
                ViewReactionsBottomSheet.newInstance(roomDetailArgs.roomId, action.messageInformationData)
                        .show(requireActivity().supportFragmentManager, "DISPLAY_REACTIONS")
            }
            is EventSharedAction.Copy                       -> {
                // I need info about the current selected message :/
                copyToClipboard(requireContext(), action.content, false)
                showSnackWithMessage(getString(R.string.copied_to_clipboard))
            }
            is EventSharedAction.Redact                     -> {
                promptConfirmationToRedactEvent(action)
            }
            is EventSharedAction.Share                      -> {
                onShareActionClicked(action)
            }
            is EventSharedAction.Save                       -> {
                onSaveActionClicked(action)
            }
            is EventSharedAction.ViewEditHistory            -> {
                onEditedDecorationClicked(action.messageInformationData)
            }
            is EventSharedAction.ViewSource                 -> {
                JSonViewerDialog.newInstance(
                        action.content,
                        -1,
                        createJSonViewerStyleProvider(colorProvider)
                ).show(childFragmentManager, "JSON_VIEWER")
            }
            is EventSharedAction.ViewDecryptedSource        -> {
                JSonViewerDialog.newInstance(
                        action.content,
                        -1,
                        createJSonViewerStyleProvider(colorProvider)
                ).show(childFragmentManager, "JSON_VIEWER")
            }
            is EventSharedAction.QuickReact                 -> {
                // eventId,ClickedOn,Add
                roomDetailViewModel.handle(RoomDetailAction.UpdateQuickReactAction(action.eventId, action.clickedOn, action.add))
            }
            is EventSharedAction.Edit                       -> {
                roomDetailViewModel.handle(RoomDetailAction.EnterEditMode(action.eventId, views.composerLayout.text.toString()))
            }
            is EventSharedAction.Quote                      -> {
                roomDetailViewModel.handle(RoomDetailAction.EnterQuoteMode(action.eventId, views.composerLayout.text.toString()))
            }
            is EventSharedAction.Reply                      -> {
                roomDetailViewModel.handle(RoomDetailAction.EnterReplyMode(action.eventId, views.composerLayout.text.toString()))
            }
            is EventSharedAction.CopyPermalink              -> {
                val permalink = session.permalinkService().createPermalink(roomDetailArgs.roomId, action.eventId)
                copyToClipboard(requireContext(), permalink, false)
                showSnackWithMessage(getString(R.string.copied_to_clipboard))
            }
            is EventSharedAction.Resend                     -> {
                roomDetailViewModel.handle(RoomDetailAction.ResendMessage(action.eventId))
            }
            is EventSharedAction.Remove                     -> {
                roomDetailViewModel.handle(RoomDetailAction.RemoveFailedEcho(action.eventId))
            }
            is EventSharedAction.Cancel                     -> {
                handleCancelSend(action)
            }
            is EventSharedAction.ReportContentSpam          -> {
                roomDetailViewModel.handle(RoomDetailAction.ReportContent(
                        action.eventId, action.senderId, "This message is spam", spam = true))
            }
            is EventSharedAction.ReportContentInappropriate -> {
                roomDetailViewModel.handle(RoomDetailAction.ReportContent(
                        action.eventId, action.senderId, "This message is inappropriate", inappropriate = true))
            }
            is EventSharedAction.ReportContentCustom        -> {
                promptReasonToReportContent(action)
            }
            is EventSharedAction.IgnoreUser                 -> {
                action.senderId?.let { askConfirmationToIgnoreUser(it) }
            }
            is EventSharedAction.OnUrlClicked               -> {
                onUrlClicked(action.url, action.title)
            }
            is EventSharedAction.OnUrlLongClicked           -> {
                onUrlLongClicked(action.url)
            }
            is EventSharedAction.ReRequestKey               -> {
                roomDetailViewModel.handle(RoomDetailAction.ReRequestKeys(action.eventId))
            }
            is EventSharedAction.UseKeyBackup               -> {
                context?.let {
                    startActivity(KeysBackupRestoreActivity.intent(it))
                }
            }
        }
    }

    private fun askConfirmationToIgnoreUser(senderId: String) {
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.room_participants_action_ignore_title)
                .setMessage(R.string.room_participants_action_ignore_prompt_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.room_participants_action_ignore) { _, _ ->
                    roomDetailViewModel.handle(RoomDetailAction.IgnoreUser(senderId))
                }
                .show()
                .withColoredButton(DialogInterface.BUTTON_POSITIVE)
    }

    /**
     * Insert a user displayName in the message editor.
     *
     * @param userId the userId.
     */
    @SuppressLint("SetTextI18n")
    private fun insertUserDisplayNameInTextEditor(userId: String) {
        val startToCompose = views.composerLayout.text.isNullOrBlank()

        if (startToCompose
                && userId == session.myUserId) {
            // Empty composer, current user: start an emote
            views.composerLayout.views.composerEditText.setText(Command.EMOTE.command + " ")
            views.composerLayout.views.composerEditText.setSelection(Command.EMOTE.length)
        } else {
            val roomMember = roomDetailViewModel.getMember(userId)
            // TODO move logic outside of fragment
            (roomMember?.displayName ?: userId)
                    .let { sanitizeDisplayName(it) }
                    .let { displayName ->
                        buildSpannedString {
                            append(displayName)
                            setSpan(
                                    PillImageSpan(
                                            glideRequests,
                                            avatarRenderer,
                                            requireContext(),
                                            MatrixItem.UserItem(userId, displayName, roomMember?.avatarUrl)
                                    )
                                            .also { it.bind(views.composerLayout.views.composerEditText) },
                                    0,
                                    displayName.length,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            append(if (startToCompose) ": " else " ")
                        }.let { pill ->
                            if (startToCompose) {
                                if (displayName.startsWith("/")) {
                                    // Ensure displayName will not be interpreted as a Slash command
                                    views.composerLayout.views.composerEditText.append("\\")
                                }
                                views.composerLayout.views.composerEditText.append(pill)
                            } else {
                                views.composerLayout.views.composerEditText.text?.insert(views.composerLayout.views.composerEditText.selectionStart, pill)
                            }
                        }
                    }
        }
        focusComposerAndShowKeyboard()
    }

    private fun focusComposerAndShowKeyboard() {
        if (views.composerLayout.isVisible) {
            views.composerLayout.views.composerEditText.showKeyboard(andRequestFocus = true)
        }
    }

    private fun showSnackWithMessage(message: String) {
        view?.showOptimizedSnackbar(message)
    }

    private fun showDialogWithMessage(message: String) {
        AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok), null)
                .show()
    }

// VectorInviteView.Callback

    override fun onAcceptInvite() {
        notificationDrawerManager.clearMemberShipNotificationForRoom(roomDetailArgs.roomId)
        roomDetailViewModel.handle(RoomDetailAction.AcceptInvite)
    }

    override fun onRejectInvite() {
        notificationDrawerManager.clearMemberShipNotificationForRoom(roomDetailArgs.roomId)
        roomDetailViewModel.handle(RoomDetailAction.RejectInvite)
    }

// JumpToReadMarkerView.Callback

    override fun onJumpToReadMarkerClicked() = withState(roomDetailViewModel) {
        views.jumpToReadMarkerView.isVisible = false
        if (it.unreadState is UnreadState.HasUnread) {
            roomDetailViewModel.handle(RoomDetailAction.NavigateToEvent(it.unreadState.firstUnreadEventId, false))
        }
        if (it.unreadState is UnreadState.ReadMarkerNotLoaded) {
            roomDetailViewModel.handle(RoomDetailAction.NavigateToEvent(it.unreadState.readMarkerId, false))
        }
    }

    override fun onClearReadMarkerClicked() {
        roomDetailViewModel.handle(RoomDetailAction.MarkAllAsRead)
    }

// AttachmentTypeSelectorView.Callback

    private val typeSelectedActivityResultLauncher = registerForPermissionsResult { allGranted ->
        if (allGranted) {
            val pendingType = attachmentsHelper.pendingType
            if (pendingType != null) {
                attachmentsHelper.pendingType = null
                launchAttachmentProcess(pendingType)
            }
        } else {
            cleanUpAfterPermissionNotGranted()
        }
    }

    override fun onTypeSelected(type: AttachmentTypeSelectorView.Type) {
        if (checkPermissions(type.permissionsBit, requireActivity(), typeSelectedActivityResultLauncher)) {
            launchAttachmentProcess(type)
        } else {
            attachmentsHelper.pendingType = type
        }
    }

    private fun launchAttachmentProcess(type: AttachmentTypeSelectorView.Type) {
        when (type) {
            AttachmentTypeSelectorView.Type.CAMERA  -> attachmentsHelper.openCamera(
                    activity = requireActivity(),
                    vectorPreferences = vectorPreferences,
                    cameraActivityResultLauncher = attachmentCameraActivityResultLauncher,
                    cameraVideoActivityResultLauncher = attachmentCameraVideoActivityResultLauncher
            )
            AttachmentTypeSelectorView.Type.FILE    -> attachmentsHelper.selectFile(attachmentFileActivityResultLauncher)
            AttachmentTypeSelectorView.Type.GALLERY -> attachmentsHelper.selectGallery(attachmentMediaActivityResultLauncher)
            AttachmentTypeSelectorView.Type.AUDIO   -> attachmentsHelper.selectAudio(attachmentAudioActivityResultLauncher)
            AttachmentTypeSelectorView.Type.CONTACT -> attachmentsHelper.selectContact(attachmentContactActivityResultLauncher)
            AttachmentTypeSelectorView.Type.STICKER -> roomDetailViewModel.handle(RoomDetailAction.SelectStickerAttachment)
        }.exhaustive
    }

// AttachmentsHelper.Callback

    override fun onContentAttachmentsReady(attachments: List<ContentAttachmentData>) {
        val grouped = attachments.toGroupedContentAttachmentData()
        if (grouped.notPreviewables.isNotEmpty()) {
            // Send the not previewable attachments right now (?)
            roomDetailViewModel.handle(RoomDetailAction.SendMedia(grouped.notPreviewables, false))
        }
        if (grouped.previewables.isNotEmpty()) {
            val intent = AttachmentsPreviewActivity.newIntent(requireContext(), AttachmentsPreviewArgs(grouped.previewables))
            contentAttachmentActivityResultLauncher.launch(intent)
        }
    }

    override fun onAttachmentsProcessFailed() {
        Toast.makeText(requireContext(), R.string.error_attachment, Toast.LENGTH_SHORT).show()
    }

    override fun onContactAttachmentReady(contactAttachment: ContactAttachment) {
        super.onContactAttachmentReady(contactAttachment)
        val formattedContact = contactAttachment.toHumanReadable()
        roomDetailViewModel.handle(RoomDetailAction.SendMessage(formattedContact, false))
    }

    private fun onViewWidgetsClicked() {
        RoomWidgetsBottomSheet.newInstance()
                .show(childFragmentManager, "ROOM_WIDGETS_BOTTOM_SHEET")
    }

    override fun onTapToReturnToCall() {
        callManager.getCurrentCall()?.let { call ->
            VectorCallActivity.newIntent(
                    context = requireContext(),
                    callId = call.callId,
                    signalingRoomId = call.signalingRoomId,
                    otherUserId = call.mxCall.opponentUserId,
                    isIncomingCall = !call.mxCall.isOutgoing,
                    isVideoCall = call.mxCall.isVideoCall,
                    mode = null
            ).let {
                startActivity(it)
            }
        }
    }
}

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

package im.vector.app.features.home.room.detail.timeline

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.VisibilityState
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.LoadingItem_
import im.vector.app.core.extensions.localDateTime
import im.vector.app.core.extensions.nextOrNull
import im.vector.app.core.extensions.prevOrNull
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.home.room.detail.RoomDetailAction
import im.vector.app.features.home.room.detail.RoomDetailViewState
import im.vector.app.features.home.room.detail.UnreadState
import im.vector.app.features.home.room.detail.timeline.factory.MergedHeaderItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.ReadReceiptsItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.TimelineControllerInterceptorHelper
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventDiffUtilCallback
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityStateChangedListener
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.helper.TimelineMediaSizeProvider
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.BasedMergedItem
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem
import im.vector.app.features.home.room.detail.timeline.item.SendStateDecoration
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

class TimelineEventController @Inject constructor(private val dateFormatter: VectorDateFormatter,
                                                  private val vectorPreferences: VectorPreferences,
                                                  private val contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder,
                                                  private val contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder,
                                                  private val timelineItemFactory: TimelineItemFactory,
                                                  private val timelineMediaSizeProvider: TimelineMediaSizeProvider,
                                                  private val mergedHeaderItemFactory: MergedHeaderItemFactory,
                                                  private val session: Session,
                                                  private val callManager: WebRtcCallManager,
                                                  @TimelineEventControllerHandler
                                                  private val backgroundHandler: Handler,
                                                  private val userPreferencesProvider: UserPreferencesProvider,
                                                  private val timelineEventVisibilityHelper: TimelineEventVisibilityHelper,
                                                  private val readReceiptsItemFactory: ReadReceiptsItemFactory
) : EpoxyController(backgroundHandler, backgroundHandler), Timeline.Listener, EpoxyController.Interceptor {

    interface Callback :
            BaseCallback,
            ReactionPillCallback,
            AvatarCallback,
            UrlClickCallback,
            ReadReceiptsCallback,
            PreviewUrlCallback {
        fun onLoadMore(direction: Timeline.Direction)
        fun onEventInvisible(event: TimelineEvent)
        fun onEventVisible(event: TimelineEvent)
        fun onRoomCreateLinkClicked(url: String)
        fun onEncryptedMessageClicked(informationData: MessageInformationData, view: View)
        fun onImageMessageClicked(messageImageContent: MessageImageInfoContent, mediaData: ImageContentRenderer.Data, view: View)
        fun onVideoMessageClicked(messageVideoContent: MessageVideoContent, mediaData: VideoContentRenderer.Data, view: View)

        //        fun onFileMessageClicked(eventId: String, messageFileContent: MessageFileContent)
//        fun onAudioMessageClicked(messageAudioContent: MessageAudioContent)
        fun onEditedDecorationClicked(informationData: MessageInformationData)

        // TODO move all callbacks to this?
        fun onTimelineItemAction(itemAction: RoomDetailAction)

        // Introduce ViewModel scoped component (or Hilt?)
        fun getPreviewUrlRetriever(): PreviewUrlRetriever
    }

    interface ReactionPillCallback {
        fun onClickOnReactionPill(informationData: MessageInformationData, reaction: String, on: Boolean)
        fun onLongClickOnReactionPill(informationData: MessageInformationData, reaction: String)
    }

    interface BaseCallback {
        fun onEventCellClicked(informationData: MessageInformationData, messageContent: Any?, view: View)
        fun onEventLongClicked(informationData: MessageInformationData, messageContent: Any?, view: View): Boolean
    }

    interface AvatarCallback {
        fun onAvatarClicked(informationData: MessageInformationData)
        fun onMemberNameClicked(informationData: MessageInformationData)
    }

    interface ReadReceiptsCallback {
        fun onReadReceiptsClicked(readReceipts: List<ReadReceiptData>)
        fun onReadMarkerVisible()
    }

    interface UrlClickCallback {
        fun onUrlClicked(url: String, title: String): Boolean
        fun onUrlLongClicked(url: String): Boolean
    }

    interface PreviewUrlCallback {
        fun onPreviewUrlClicked(url: String)
        fun onPreviewUrlCloseClicked(eventId: String, url: String)
        fun onPreviewUrlImageClicked(sharedView: View?, mxcUrl: String?, title: String?)
    }

    // Map eventId to adapter position
    private val adapterPositionMapping = HashMap<String, Int>()
    private val modelCache = arrayListOf<CacheItemData?>()
    private var currentSnapshot: List<TimelineEvent> = emptyList()
    private var inSubmitList: Boolean = false
    private var hasReachedInvite: Boolean = false
    private var hasUTD: Boolean = false
    private var unreadState: UnreadState = UnreadState.Unknown
    private var positionOfReadMarker: Int? = null
    private var eventIdToHighlight: String? = null

    var callback: Callback? = null
    var timeline: Timeline? = null

    private val listUpdateCallback = object : ListUpdateCallback {

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                (position until (position + count)).forEach {
                    // Invalidate cache
                    modelCache[it] = null
                }
                requestModelBuild()
            }
        }

        override fun onMoved(fromPosition: Int, toPosition: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                val model = modelCache.removeAt(fromPosition)
                modelCache.add(toPosition, model)
                requestModelBuild()
            }
        }

        override fun onInserted(position: Int, count: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                repeat(count) {
                    modelCache.add(position, null)
                }
                requestModelBuild()
            }
        }

        override fun onRemoved(position: Int, count: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                repeat(count) {
                    modelCache.removeAt(position)
                }
                requestModelBuild()
            }
        }
    }

    private val interceptorHelper = TimelineControllerInterceptorHelper(
            ::positionOfReadMarker,
            adapterPositionMapping,
            userPreferencesProvider,
            callManager
    )

    init {
        addInterceptor(this)
        requestModelBuild()
    }

    override fun intercept(models: MutableList<EpoxyModel<*>>) = synchronized(modelCache) {
        interceptorHelper.intercept(models, unreadState, timeline, callback)
    }

    fun update(viewState: RoomDetailViewState) {
        var requestModelBuild = false
        if (eventIdToHighlight != viewState.highlightedEventId) {
            // Clear cache to force a refresh
            synchronized(modelCache) {
                for (i in 0 until modelCache.size) {
                    if (modelCache[i]?.eventId == viewState.highlightedEventId
                            || modelCache[i]?.eventId == eventIdToHighlight) {
                        modelCache[i] = null
                    }
                }
            }
            eventIdToHighlight = viewState.highlightedEventId
            requestModelBuild = true
        }
        if (this.unreadState != viewState.unreadState) {
            this.unreadState = viewState.unreadState
            requestModelBuild = true
        }
        if (requestModelBuild) {
            requestModelBuild()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        timeline?.addListener(this)
        timelineMediaSizeProvider.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        timelineMediaSizeProvider.recyclerView = null
        contentUploadStateTrackerBinder.clear()
        contentDownloadStateTrackerBinder.clear()
        timeline?.removeListener(this)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun buildModels() {
        val timestamp = System.currentTimeMillis()

        val showingForwardLoader = LoadingItem_()
                .id("forward_loading_item_$timestamp")
                .setVisibilityStateChangedListener(Timeline.Direction.FORWARDS)
                .addWhenLoading(Timeline.Direction.FORWARDS)

        val timelineModels = getModels()
        add(timelineModels)
        if (hasReachedInvite && hasUTD) {
            return
        }
        // Avoid displaying two loaders if there is no elements between them
        val showBackwardsLoader = !showingForwardLoader || timelineModels.isNotEmpty()
        // We can hide the loader but still add the item to controller so it can trigger backwards pagination
        LoadingItem_()
                .id("backward_loading_item_$timestamp")
                .setVisibilityStateChangedListener(Timeline.Direction.BACKWARDS)
                .showLoader(showBackwardsLoader)
                .addWhenLoading(Timeline.Direction.BACKWARDS)
    }

// Timeline.LISTENER ***************************************************************************

    override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
        submitSnapshot(snapshot)
    }

    override fun onTimelineFailure(throwable: Throwable) {
        // no-op, already handled
    }

    override fun onNewTimelineEvents(eventIds: List<String>) {
        // no-op, already handled
    }

    private fun submitSnapshot(newSnapshot: List<TimelineEvent>) {
        backgroundHandler.post {
            inSubmitList = true
            val diffCallback = TimelineEventDiffUtilCallback(currentSnapshot, newSnapshot)
            currentSnapshot = newSnapshot
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            diffResult.dispatchUpdatesTo(listUpdateCallback)
            requestModelBuild()
            inSubmitList = false
        }
    }

    private fun assertUpdateCallbacksAllowed() {
        require(inSubmitList || Looper.myLooper() == backgroundHandler.looper)
    }

    private fun getModels(): List<EpoxyModel<*>> {
        buildCacheItemsIfNeeded()
        return modelCache
                .map { cacheItemData ->
                    val eventModel = if (cacheItemData == null || mergedHeaderItemFactory.isCollapsed(cacheItemData.localId)) {
                        null
                    } else {
                        cacheItemData.eventModel
                    }
                    listOf(
                            cacheItemData?.readReceiptsItem?.takeUnless { mergedHeaderItemFactory.isCollapsed(cacheItemData.localId) },
                            eventModel,
                            cacheItemData?.mergedHeaderModel,
                            cacheItemData?.formattedDayModel?.takeIf { eventModel != null || cacheItemData.mergedHeaderModel != null }
                    )
                }
                .flatten()
                .filterNotNull()
    }

    private fun buildCacheItemsIfNeeded() = synchronized(modelCache) {
        hasUTD = false
        hasReachedInvite = false
        if (modelCache.isEmpty()) {
            return
        }
        val receiptsByEvents = getReadReceiptsByShownEvent()
        val lastSentEventWithoutReadReceipts = searchLastSentEventWithoutReadReceipts(receiptsByEvents)
        (0 until modelCache.size).forEach { position ->
            val event = currentSnapshot[position]
            val nextEvent = currentSnapshot.nextOrNull(position)
            val prevEvent = currentSnapshot.prevOrNull(position)
            val params = TimelineItemFactoryParams(
                    event = event,
                    prevEvent = prevEvent,
                    nextEvent = nextEvent,
                    highlightedEventId = eventIdToHighlight,
                    lastSentEventIdWithoutReadReceipts = lastSentEventWithoutReadReceipts,
                    callback = callback
            )
            // Should be build if not cached or if model should be refreshed
            if (modelCache[position] == null || modelCache[position]?.shouldTriggerBuild == true) {
                modelCache[position] = buildCacheItem(params)
            }
            val itemCachedData = modelCache[position] ?: return@forEach
            // Then update with additional models if needed
            modelCache[position] = itemCachedData.enrichWithModels(event, nextEvent, position, receiptsByEvents)
        }
    }

    private fun buildCacheItem(params: TimelineItemFactoryParams): CacheItemData {
        val event = params.event
        if (hasReachedInvite && hasUTD) {
            return CacheItemData(event.localId, event.root.eventId)
        }
        updateUTDStates(event, params.nextEvent)
        val eventModel = timelineItemFactory.create(params).also {
            it.id(event.localId)
            it.setOnVisibilityStateChanged(TimelineEventVisibilityStateChangedListener(callback, event))
        }
        val shouldTriggerBuild = eventModel is AbsMessageItem && eventModel.attributes.informationData.sendStateDecoration == SendStateDecoration.SENT
        return CacheItemData(
                localId = event.localId,
                eventId = event.root.eventId,
                eventModel = eventModel,
                shouldTriggerBuild = shouldTriggerBuild)
    }

    private fun CacheItemData.enrichWithModels(event: TimelineEvent,
                                               nextEvent: TimelineEvent?,
                                               position: Int,
                                               receiptsByEvents: Map<String, List<ReadReceipt>>): CacheItemData {
        val wantsDateSeparator = wantsDateSeparator(event, nextEvent)
        val mergedHeaderModel = mergedHeaderItemFactory.create(event,
                nextEvent = nextEvent,
                items = this@TimelineEventController.currentSnapshot,
                addDaySeparator = wantsDateSeparator,
                currentPosition = position,
                eventIdToHighlight = eventIdToHighlight,
                callback = callback
        ) {
            requestModelBuild()
        }
        val formattedDayModel = if (wantsDateSeparator) {
            buildDaySeparatorItem(event.root.originServerTs)
        } else {
            null
        }
        val readReceipts = receiptsByEvents[event.eventId].orEmpty()
        return copy(
                readReceiptsItem = readReceiptsItemFactory.create(event.eventId, readReceipts, callback),
                formattedDayModel = formattedDayModel,
                mergedHeaderModel = mergedHeaderModel
        )
    }

    private fun searchLastSentEventWithoutReadReceipts(receiptsByEvent: Map<String, List<ReadReceipt>>): String? {
        if (timeline?.isLive == false) {
            // If timeline is not live we don't want to show SentStatus
            return null
        }
        for (event in currentSnapshot) {
            // If there is any RR on the event, we stop searching for Sent event
            if (receiptsByEvent[event.eventId]?.isNotEmpty() == true) {
                return null
            }
            // If the event is not shown, we go to the next one
            if (!timelineEventVisibilityHelper.shouldShowEvent(event, eventIdToHighlight)) {
                continue
            }
            // If the event is sent by us, we update the holder with the eventId and stop the search
            if (event.root.senderId == session.myUserId && event.root.sendState.isSent()) {
                return event.eventId
            }
        }
        return null
    }

    private fun getReadReceiptsByShownEvent(): Map<String, List<ReadReceipt>> {
        val receiptsByEvent = HashMap<String, MutableList<ReadReceipt>>()
        if (!userPreferencesProvider.shouldShowReadReceipts()) {
            return receiptsByEvent
        }
        var lastShownEventId: String? = null
        val itr = currentSnapshot.listIterator(currentSnapshot.size)
        while (itr.hasPrevious()) {
            val event = itr.previous()
            val currentReadReceipts = ArrayList(event.readReceipts).filter {
                it.user.userId != session.myUserId
            }
            if (timelineEventVisibilityHelper.shouldShowEvent(event, eventIdToHighlight)) {
                lastShownEventId = event.eventId
            }
            if (lastShownEventId == null) {
                continue
            }
            val existingReceipts = receiptsByEvent.getOrPut(lastShownEventId) { ArrayList() }
            existingReceipts.addAll(currentReadReceipts)
        }
        return receiptsByEvent
    }

    private fun buildDaySeparatorItem(originServerTs: Long?): DaySeparatorItem {
        val formattedDay = dateFormatter.format(originServerTs, DateFormatKind.TIMELINE_DAY_DIVIDER)
        return DaySeparatorItem_().formattedDay(formattedDay).id(formattedDay)
    }

    private fun LoadingItem_.setVisibilityStateChangedListener(direction: Timeline.Direction): LoadingItem_ {
        val host = this@TimelineEventController
        return onVisibilityStateChanged { _, _, visibilityState ->
            if (visibilityState == VisibilityState.VISIBLE) {
                host.callback?.onLoadMore(direction)
            }
        }
    }

    private fun updateUTDStates(event: TimelineEvent, nextEvent: TimelineEvent?) {
        if (vectorPreferences.labShowCompleteHistoryInEncryptedRoom()) {
            return
        }
        if (event.root.type == EventType.STATE_ROOM_MEMBER
                && event.root.stateKey == session.myUserId) {
            val content = event.root.content.toModel<RoomMemberContent>()
            if (content?.membership == Membership.INVITE) {
                hasReachedInvite = true
            } else if (content?.membership == Membership.JOIN) {
                val prevContent = event.root.resolvedPrevContent().toModel<RoomMemberContent>()
                if (prevContent?.membership?.isActive() == false) {
                    hasReachedInvite = true
                }
            }
        }
        if (nextEvent?.root?.getClearType() == EventType.ENCRYPTED) {
            hasUTD = true
        }
    }

    private fun wantsDateSeparator(event: TimelineEvent, nextEvent: TimelineEvent?): Boolean {
        return if (hasReachedInvite && hasUTD) {
            true
        } else {
            val date = event.root.localDateTime()
            val nextDate = nextEvent?.root?.localDateTime()
            date.toLocalDate() != nextDate?.toLocalDate()
        }
    }

    /**
     * Return true if added
     */
    private fun LoadingItem_.addWhenLoading(direction: Timeline.Direction): Boolean {
        val host = this@TimelineEventController
        val shouldAdd = host.timeline?.hasMoreToLoad(direction) ?: false
        addIf(shouldAdd, host)
        return shouldAdd
    }

    fun searchPositionOfEvent(eventId: String?): Int? = synchronized(modelCache) {
        return adapterPositionMapping[eventId]
    }

    fun getPositionOfReadMarker(): Int? = synchronized(modelCache) {
        return positionOfReadMarker
    }

    private data class CacheItemData(
            val localId: Long,
            val eventId: String?,
            val readReceiptsItem: ReadReceiptsItem? = null,
            val eventModel: EpoxyModel<*>? = null,
            val mergedHeaderModel: BasedMergedItem<*>? = null,
            val formattedDayModel: DaySeparatorItem? = null,
            val shouldTriggerBuild: Boolean = false
    )
}

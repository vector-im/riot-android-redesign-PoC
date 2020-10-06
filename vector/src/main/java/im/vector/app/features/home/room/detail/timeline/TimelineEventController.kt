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
import im.vector.app.features.home.room.detail.RoomDetailAction
import im.vector.app.features.home.room.detail.RoomDetailViewState
import im.vector.app.features.home.room.detail.UnreadState
import im.vector.app.features.home.room.detail.timeline.factory.MergedHeaderItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ReadMarkerVisibilityStateChangedListener
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventDiffUtilCallback
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityStateChangedListener
import im.vector.app.features.home.room.detail.timeline.helper.TimelineMediaSizeProvider
import im.vector.app.features.home.room.detail.timeline.item.BaseEventItem
import im.vector.app.features.home.room.detail.timeline.item.BasedMergedItem
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.item.TimelineReadMarkerItem_
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

private const val DEFAULT_PREFETCH_THRESHOLD = 30

class TimelineEventController @Inject constructor(private val dateFormatter: VectorDateFormatter,
                                                  private val vectorPreferences: VectorPreferences,
                                                  private val contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder,
                                                  private val contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder,
                                                  private val timelineItemFactory: TimelineItemFactory,
                                                  private val timelineMediaSizeProvider: TimelineMediaSizeProvider,
                                                  private val mergedHeaderItemFactory: MergedHeaderItemFactory,
                                                  private val session: Session,
                                                  @TimelineEventControllerHandler
                                                  private val backgroundHandler: Handler,
                                                  @EpoxyControllerModelHandler
                                                  private val modelBuilderHandler: Handler
) : EpoxyController(modelBuilderHandler, backgroundHandler), Timeline.Listener, EpoxyController.Interceptor {

    interface Callback : BaseCallback, ReactionPillCallback, AvatarCallback, UrlClickCallback, ReadReceiptsCallback {
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
    private var previousModelsSize = 0

    var callback: Callback? = null
    var timeline: Timeline? = null

    private val listUpdateCallback = object : ListUpdateCallback {

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                (position until (position + count)).forEach {
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
                (0 until count).forEach {
                    modelCache.add(position, null)
                }
                requestModelBuild()
            }
        }

        override fun onRemoved(position: Int, count: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                (0 until count).forEach {
                    modelCache.removeAt(position)
                }
                requestModelBuild()
            }
        }
    }

    init {
        addInterceptor(this)
        requestModelBuild()
    }

    // Update position when we are building new items
    override fun intercept(models: MutableList<EpoxyModel<*>>) = synchronized(modelCache) {
        positionOfReadMarker = null
        adapterPositionMapping.clear()
        models.forEachIndexed { index, epoxyModel ->
            if (epoxyModel is BaseEventItem) {
                epoxyModel.getEventIds().forEach {
                    adapterPositionMapping[it] = index
                }
            }
        }
        val currentUnreadState = this.unreadState
        if (currentUnreadState is UnreadState.HasUnread) {
            val position = adapterPositionMapping[currentUnreadState.firstUnreadEventId]?.plus(1)
            positionOfReadMarker = position
            if (position != null) {
                val readMarker = TimelineReadMarkerItem_()
                        .also {
                            it.id("read_marker")
                            it.setOnVisibilityStateChanged(ReadMarkerVisibilityStateChangedListener(callback))
                        }
                models.add(position, readMarker)
            }
        }
        val shouldAddBackwardPrefetch = timeline?.hasMoreToLoad(Timeline.Direction.BACKWARDS) ?: false
        if (shouldAddBackwardPrefetch) {
            val indexOfPrefetchBackward = (previousModelsSize - 1)
                    .coerceAtMost(models.size - DEFAULT_PREFETCH_THRESHOLD)
                    .coerceAtLeast(0)

            val loadingItem = LoadingItem_()
                    .id("prefetch_backward_loading${System.currentTimeMillis()}")
                    .showLoader(false)
                    .setVisibilityStateChangedListener(Timeline.Direction.BACKWARDS)

            models.add(indexOfPrefetchBackward, loadingItem)
        }
        val shouldAddForwardPrefetch = timeline?.hasMoreToLoad(Timeline.Direction.FORWARDS) ?: false
        if (shouldAddForwardPrefetch) {
            val indexOfPrefetchForward = DEFAULT_PREFETCH_THRESHOLD.coerceAtMost(models.size - 1)
            val loadingItem = LoadingItem_()
                    .id("prefetch_forward_loading${System.currentTimeMillis()}")
                    .showLoader(false)
                    .setVisibilityStateChangedListener(Timeline.Direction.FORWARDS)
            models.add(indexOfPrefetchForward, loadingItem)
        }
        previousModelsSize = models.size
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
                    listOf(eventModel,
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
        (0 until modelCache.size).forEach { position ->
            // Should be build if not cached or if cached but contains additional models
            // We then are sure we always have items up to date.
            if (modelCache[position] == null || modelCache[position]?.shouldTriggerBuild() == true) {
                modelCache[position] = buildCacheItem(position, currentSnapshot)
            }
        }
    }

    private fun buildCacheItem(currentPosition: Int, items: List<TimelineEvent>): CacheItemData {
        val event = items[currentPosition]
        val nextEvent = items.nextOrNull(currentPosition)
        if (hasReachedInvite && hasUTD) {
            return CacheItemData(event.localId, event.root.eventId, null, null, null)
        }
        updateUTDStates(event, nextEvent)
        val eventModel = timelineItemFactory.create(event, nextEvent, eventIdToHighlight, callback).also {
            it.id(event.localId)
            it.setOnVisibilityStateChanged(TimelineEventVisibilityStateChangedListener(callback, event))
        }
        val addDaySeparator = if (hasReachedInvite && hasUTD) {
            true
        } else {
            val date = event.root.localDateTime()
            val nextDate = nextEvent?.root?.localDateTime()
            date.toLocalDate() != nextDate?.toLocalDate()
        }
        val mergedHeaderModel = mergedHeaderItemFactory.create(event,
                nextEvent = nextEvent,
                items = items,
                addDaySeparator = addDaySeparator,
                currentPosition = currentPosition,
                eventIdToHighlight = eventIdToHighlight,
                callback = callback
        ) {
            requestModelBuild()
        }
        val daySeparatorItem = buildDaySeparatorItem(addDaySeparator, event.root.originServerTs)
        return CacheItemData(event.localId, event.root.eventId, eventModel, mergedHeaderModel, daySeparatorItem)
    }

    private fun buildDaySeparatorItem(addDaySeparator: Boolean, originServerTs: Long?): DaySeparatorItem? {
        return if (addDaySeparator) {
            val formattedDay = dateFormatter.format(originServerTs, DateFormatKind.TIMELINE_DAY_DIVIDER)
            DaySeparatorItem_().formattedDay(formattedDay).id(formattedDay)
        } else {
            null
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

    /**
     * Return true if added
     */
    private fun LoadingItem_.addWhenLoading(direction: Timeline.Direction): Boolean {
        val shouldAdd = timeline?.hasMoreToLoad(direction) ?: false
        addIf(shouldAdd, this@TimelineEventController)
        return shouldAdd
    }

    private fun LoadingItem_.setVisibilityStateChangedListener(direction: Timeline.Direction): LoadingItem_ {
        return onVisibilityStateChanged { _, _, visibilityState ->
            if (visibilityState == VisibilityState.VISIBLE) {
                callback?.onLoadMore(direction)
            }
        }
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
            val eventModel: EpoxyModel<*>? = null,
            val mergedHeaderModel: BasedMergedItem<*>? = null,
            val formattedDayModel: DaySeparatorItem? = null
    ) {
        fun shouldTriggerBuild(): Boolean {
            return mergedHeaderModel != null || formattedDayModel != null
        }
    }
}

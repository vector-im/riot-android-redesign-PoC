/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.riotx.features.notifications

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import im.vector.matrix.android.api.session.content.ContentUrlResolver
import im.vector.riotx.BuildConfig
import im.vector.riotx.R
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.core.resources.StringProvider
import im.vector.riotx.features.settings.VectorPreferences
import me.gujun.android.span.span
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The NotificationDrawerManager receives notification events as they arrived (from event stream or fcm) and
 * organise them in order to display them in the notification drawer.
 * Events can be grouped into the same notification, old (already read) events can be removed to do some cleaning.
 */
@Singleton
class NotificationDrawerManager @Inject constructor(private val context: Context,
                                                    private val notificationUtils: NotificationUtils,
                                                    private val vectorPreferences: VectorPreferences,
                                                    private val stringProvider: StringProvider,
                                                    private val activeSessionHolder: ActiveSessionHolder,
                                                    private val iconLoader: IconLoader,
                                                    private val bitmapLoader: BitmapLoader) {

    private val handlerThread: HandlerThread = HandlerThread("NotificationDrawerManager", Thread.MIN_PRIORITY)
    private var backgroundHandler: Handler

    init {
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)
    }

    // The first time the notification drawer is refreshed, we force re-render of all notifications
    private var firstTime = true

    private val eventList = loadEventInfo()

    private val avatarSize = context.resources.getDimensionPixelSize(R.dimen.profile_avatar_size)

    private var currentRoomId: String? = null

    /**
    Should be called as soon as a new event is ready to be displayed.
    The notification corresponding to this event will not be displayed until
    #refreshNotificationDrawer() is called.
    Events might be grouped and there might not be one notification per event!
     */
    fun onNotifiableEventReceived(notifiableEvent: NotifiableEvent) {
        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            Timber.i("Notification are disabled for this device")
            return
        }
        // If we support multi session, event list should be per userId
        // Currently only manage single session
        if (BuildConfig.LOW_PRIVACY_LOG_ENABLE) {
            Timber.v("%%%%%%%% onNotifiableEventReceived $notifiableEvent")
        }
        synchronized(eventList) {
            val existing = eventList.firstOrNull { it.eventId == notifiableEvent.eventId }
            if (existing != null) {
                if (existing.isPushGatewayEvent) {
                    // Use the event coming from the event stream as it may contains more info than
                    // the fcm one (like type/content/clear text)
                    // In this case the message has already been notified, and might have done some noise
                    // So we want the notification to be updated even if it has already been displayed
                    // But it should make no noise (e.g when an encrypted message from FCM should be
                    // update with clear text after a sync)
                    notifiableEvent.hasBeenDisplayed = false
                    notifiableEvent.noisy = false
                    eventList.remove(existing)
                    eventList.add(notifiableEvent)
                } else {
                    // keep the existing one, do not replace
                }
            } else {
                // Check if this is an edit
                if (notifiableEvent.editedEventId != null) {
                    // This is an edition
                    val eventBeforeEdition = eventList.firstOrNull {
                        // Edition of an event
                        it.eventId == notifiableEvent.editedEventId
                                // or edition of an edition
                                || it.editedEventId == notifiableEvent.editedEventId
                    }

                    if (eventBeforeEdition != null) {
                        // Replace the existing notification with the new content
                        eventList.remove(eventBeforeEdition)

                        eventList.add(notifiableEvent)
                    } else {
                        // Ignore an edit of a not displayed event in the notification drawer
                    }
                } else {
                    // Not an edit
                    eventList.add(notifiableEvent)
                }
            }
        }
    }

    fun onEventRedacted(eventId: String) {
        synchronized(eventList) {
            eventList.find { it.eventId == eventId }?.apply {
                isRedacted = true
                hasBeenDisplayed = false
            }
        }
    }

    /**
     * Clear all known events and refresh the notification drawer
     */
    fun clearAllEvents() {
        synchronized(eventList) {
            eventList.clear()
        }
        refreshNotificationDrawer()
    }

    /** Clear all known message events for this room */
    fun clearMessageEventOfRoom(roomId: String?) {
        Timber.v("clearMessageEventOfRoom $roomId")

        if (roomId != null) {
            synchronized(eventList) {
                eventList.removeAll { e ->
                    e is NotifiableMessageEvent && e.roomId == roomId
                }
            }
            refreshNotificationDrawer()
        }
    }

    /**
     * Should be called when the application is currently opened and showing timeline for the given roomId.
     * Used to ignore events related to that room (no need to display notification) and clean any existing notification on this room.
     */
    fun setCurrentRoom(roomId: String?) {
        var hasChanged: Boolean
        synchronized(eventList) {
            hasChanged = roomId != currentRoomId
            currentRoomId = roomId
        }
        if (hasChanged) {
            clearMessageEventOfRoom(roomId)
        }
    }

    fun clearMemberShipNotificationForRoom(roomId: String) {
        synchronized(eventList) {
            eventList.removeAll { e ->
                e is InviteNotifiableEvent && e.roomId == roomId
            }
        }
        notificationUtils.cancelNotificationMessage(roomId, ROOM_INVITATION_NOTIFICATION_ID)
    }

    fun refreshNotificationDrawer() {
        // Implement last throttler
        Timber.v("refreshNotificationDrawer()")
        backgroundHandler.removeCallbacksAndMessages(null)
        backgroundHandler.postDelayed(
                {
                    try {
                        refreshNotificationDrawerBg()
                    } catch (throwable: Throwable) {
                        // It can happen if for instance session has been destroyed. It's a bit ugly to try catch like this, but it's safer
                        Timber.w(throwable, "refreshNotificationDrawerBg failure")
                    }
                }, 200)
    }

    @WorkerThread
    private fun refreshNotificationDrawerBg() {
        Timber.v("refreshNotificationDrawerBg()")

        val session = activeSessionHolder.getSafeActiveSession() ?: return

        val user = session.getUser(session.myUserId)
        // myUserDisplayName cannot be empty else NotificationCompat.MessagingStyle() will crash
        val myUserDisplayName = user?.getBestName() ?: session.myUserId
        val myUserAvatarUrl = session.contentUrlResolver().resolveThumbnail(user?.avatarUrl, avatarSize, avatarSize, ContentUrlResolver.ThumbnailMethod.SCALE)
        synchronized(eventList) {
            Timber.v("%%%%%%%% REFRESH NOTIFICATION DRAWER ")
            // TMP code
            var hasNewEvent = false
            var summaryIsNoisy = false
            val summaryInboxStyle = NotificationCompat.InboxStyle()

            // group events by room to create a single MessagingStyle notif
            val roomIdToEventMap: MutableMap<String, MutableList<NotifiableMessageEvent>> = LinkedHashMap()
            val simpleEvents: MutableList<SimpleNotifiableEvent> = ArrayList()
            val invitationEvents: MutableList<InviteNotifiableEvent> = ArrayList()

            val eventIterator = eventList.listIterator()
            while (eventIterator.hasNext()) {
                when (val event = eventIterator.next()) {
                    is NotifiableMessageEvent -> {
                        val roomId = event.roomId
                        val roomEvents = roomIdToEventMap.getOrPut(roomId) { ArrayList() }

                        if (shouldIgnoreMessageEventInRoom(roomId)) {
                            // forget this event
                            eventIterator.remove()
                        } else {
                            roomEvents.add(event)
                        }
                    }
                    is InviteNotifiableEvent  -> invitationEvents.add(event)
                    is SimpleNotifiableEvent  -> simpleEvents.add(event)
                    else                      -> Timber.w("Type not handled")
                }
            }

            Timber.v("%%%%%%%% REFRESH NOTIFICATION DRAWER ${roomIdToEventMap.size} room groups")

            var globalLastMessageTimestamp = 0L

            // events have been grouped by roomId
            for ((roomId, events) in roomIdToEventMap) {
                // Build the notification for the room
                if (events.isEmpty() || events.all { it.isRedacted }) {
                    // Just clear this notification
                    Timber.v("%%%%%%%% REFRESH NOTIFICATION DRAWER $roomId has no more events")
                    notificationUtils.cancelNotificationMessage(roomId, ROOM_MESSAGES_NOTIFICATION_ID)
                    continue
                }

                val roomName = events[0].roomName ?: events[0].senderName ?: ""

                val roomEventGroupInfo = RoomEventGroupInfo(
                        roomId = roomId,
                        isDirect = events[0].roomIsDirect,
                        roomDisplayName = roomName)

                val style = NotificationCompat.MessagingStyle(Person.Builder()
                        .setName(myUserDisplayName)
                        .setIcon(iconLoader.getUserIcon(myUserAvatarUrl))
                        .setKey(events[0].matrixID)
                        .build())

                style.isGroupConversation = !roomEventGroupInfo.isDirect

                if (!roomEventGroupInfo.isDirect) {
                    style.conversationTitle = roomEventGroupInfo.roomDisplayName
                }

                val largeBitmap = getRoomBitmap(events)

                var unreadEvents : MutableList<NotifiableMessageEvent> = events

                val readMarker = activeSessionHolder.getSafeActiveSession()?.getRoom(roomId)?.getReadMarker()?.getOrNull()
                if (readMarker != null) {
                    unreadEvents = ArrayList()
                    events.sortBy { it.timestamp }
                    var readFromHere: Boolean = false
                    for (event in events.reversed()) {
                        if (readMarker == event.eventId) {
                            readFromHere = true
                        }
                        if (readFromHere) {
                            eventList.remove(event)
                        } else {
                            unreadEvents.add(event)
                        }
                    }
                    unreadEvents.reverse()
                }

                for (event in unreadEvents) {
                    // if all events in this room have already been displayed there is no need to update it
                    if (!event.hasBeenDisplayed && !event.isRedacted) {
                        roomEventGroupInfo.shouldBing = roomEventGroupInfo.shouldBing || event.noisy
                        roomEventGroupInfo.customSound = event.soundName
                    }
                    roomEventGroupInfo.hasNewEvent = roomEventGroupInfo.hasNewEvent || !event.hasBeenDisplayed

                    val senderPerson = Person.Builder()
                            .setName(event.senderName)
                            .setIcon(iconLoader.getUserIcon(event.senderAvatarPath))
                            .setKey(event.senderId)
                            .build()

                    if (event.outGoingMessage && event.outGoingMessageFailed) {
                        style.addMessage(stringProvider.getString(R.string.notification_inline_reply_failed), event.timestamp, senderPerson)
                        roomEventGroupInfo.hasSmartReplyError = true
                    } else {
                        if (!event.isRedacted) {
                            style.addMessage(event.body, event.timestamp, senderPerson)
                        }
                    }
                    event.hasBeenDisplayed = true // we can consider it as displayed

                    // It is possible that this event was previously shown as an 'anonymous' simple notif.
                    // And now it will be merged in a single MessageStyle notif, so we can clean to be sure
                    notificationUtils.cancelNotificationMessage(event.eventId, ROOM_EVENT_NOTIFICATION_ID)
                }

                try {
                    if (events.size == 1) {
                        val event = events[0]
                        if (roomEventGroupInfo.isDirect) {
                            val line = span {
                                span {
                                    textStyle = "bold"
                                    +String.format("%s: ", event.senderName)
                                }
                                +(event.description ?: "")
                            }
                            summaryInboxStyle.addLine(line)
                        } else {
                            val line = span {
                                span {
                                    textStyle = "bold"
                                    +String.format("%s: %s ", roomName, event.senderName)
                                }
                                +(event.description ?: "")
                            }
                            summaryInboxStyle.addLine(line)
                        }
                    } else {
                        val summaryLine = stringProvider.getQuantityString(
                                R.plurals.notification_compat_summary_line_for_room, events.size, roomName, events.size)
                        summaryInboxStyle.addLine(summaryLine)
                    }
                } catch (e: Throwable) {
                    // String not found or bad format
                    Timber.v("%%%%%%%% REFRESH NOTIFICATION DRAWER failed to resolve string")
                    summaryInboxStyle.addLine(roomName)
                }

                if (firstTime || roomEventGroupInfo.hasNewEvent) {
                    // Should update displayed notification
                    Timber.v("%%%%%%%% REFRESH NOTIFICATION DRAWER $roomId need refresh")
                    val lastMessageTimestamp = events.last().timestamp

                    if (globalLastMessageTimestamp < lastMessageTimestamp) {
                        globalLastMessageTimestamp = lastMessageTimestamp
                    }

                    val tickerText = if (roomEventGroupInfo.isDirect) {
                        stringProvider.getString(R.string.notification_ticker_text_dm, events.last().senderName, events.last().description)
                    } else {
                        stringProvider.getString(R.string.notification_ticker_text_group, roomName, events.last().senderName, events.last().description)
                    }

                    val notification = notificationUtils.buildMessagesListNotification(
                            style,
                            roomEventGroupInfo,
                            largeBitmap,
                            lastMessageTimestamp,
                            myUserDisplayName,
                            tickerText)

                    // is there an id for this room?
                    notificationUtils.showNotificationMessage(roomId, ROOM_MESSAGES_NOTIFICATION_ID, notification)

                    hasNewEvent = true
                    summaryIsNoisy = summaryIsNoisy || roomEventGroupInfo.shouldBing
                } else {
                    Timber.v("%%%%%%%% REFRESH NOTIFICATION DRAWER $roomId is up to date")
                }
            }

            // Handle invitation events
            for (event in invitationEvents) {
                // We build a invitation notification
                if (firstTime || !event.hasBeenDisplayed) {
                    val notification = notificationUtils.buildRoomInvitationNotification(event, session.myUserId)
                    notificationUtils.showNotificationMessage(event.roomId, ROOM_INVITATION_NOTIFICATION_ID, notification)
                    event.hasBeenDisplayed = true // we can consider it as displayed
                    hasNewEvent = true
                    summaryIsNoisy = summaryIsNoisy || event.noisy
                    summaryInboxStyle.addLine(event.description)
                }
            }

            // Handle simple events
            for (event in simpleEvents) {
                // We build a simple notification
                if (firstTime || !event.hasBeenDisplayed) {
                    val notification = notificationUtils.buildSimpleEventNotification(event, session.myUserId)
                    notificationUtils.showNotificationMessage(event.eventId, ROOM_EVENT_NOTIFICATION_ID, notification)
                    event.hasBeenDisplayed = true // we can consider it as displayed
                    hasNewEvent = true
                    summaryIsNoisy = summaryIsNoisy || event.noisy
                    summaryInboxStyle.addLine(event.description)
                }
            }

            // ======== Build summary notification =========
            // On Android 7.0 (API level 24) and higher, the system automatically builds a summary for
            // your group using snippets of text from each notification. The user can expand this
            // notification to see each separate notification.
            // To support older versions, which cannot show a nested group of notifications,
            // you must create an extra notification that acts as the summary.
            // This appears as the only notification and the system hides all the others.
            // So this summary should include a snippet from all the other notifications,
            // which the user can tap to open your app.
            // The behavior of the group summary may vary on some device types such as wearables.
            // To ensure the best experience on all devices and versions, always include a group summary when you create a group
            // https://developer.android.com/training/notify-user/group

            if (eventList.isEmpty() || eventList.all { it.isRedacted }) {
                notificationUtils.cancelNotificationMessage(null, SUMMARY_NOTIFICATION_ID)
            } else {
                val nbEvents = roomIdToEventMap.size + simpleEvents.size
                val sumTitle = stringProvider.getQuantityString(R.plurals.notification_compat_summary_title, nbEvents, nbEvents)
                summaryInboxStyle.setBigContentTitle(sumTitle)
                        // TODO get latest event?
                        .setSummaryText(stringProvider.getQuantityString(R.plurals.notification_unread_notified_messages, nbEvents, nbEvents))

                val notification = notificationUtils.buildSummaryListNotification(
                        summaryInboxStyle,
                        sumTitle,
                        noisy = hasNewEvent && summaryIsNoisy,
                        lastMessageTimestamp = globalLastMessageTimestamp)

                notificationUtils.showNotificationMessage(null, SUMMARY_NOTIFICATION_ID, notification)

                if (hasNewEvent && summaryIsNoisy) {
                    try {
                        // turn the screen on for 3 seconds
                        /*
                        TODO
                        if (Matrix.getInstance(VectorApp.getInstance())!!.pushManager.isScreenTurnedOn) {
                            val pm = VectorApp.getInstance().getSystemService(Context.POWER_SERVICE) as PowerManager
                            val wl = pm.newWakeLock(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                    NotificationDrawerManager::class.java.name)
                            wl.acquire(3000)
                            wl.release()
                        }
                         */
                    } catch (e: Throwable) {
                        Timber.e(e, "## Failed to turn screen on")
                    }
                }
            }
            // notice that we can get bit out of sync with actual display but not a big issue
            firstTime = false
        }
    }

    private fun getRoomBitmap(events: List<NotifiableMessageEvent>): Bitmap? {
        if (events.isEmpty()) return null

        // Use the last event (most recent?)
        val roomAvatarPath = events.last().roomAvatarPath ?: events.last().senderAvatarPath

        return bitmapLoader.getRoomBitmap(roomAvatarPath)
    }

    private fun shouldIgnoreMessageEventInRoom(roomId: String?): Boolean {
        return currentRoomId != null && roomId == currentRoomId
    }

    fun persistInfo() {
        synchronized(eventList) {
            if (eventList.isEmpty()) {
                deleteCachedRoomNotifications()
                return
            }
            try {
                val file = File(context.applicationContext.cacheDir, ROOMS_NOTIFICATIONS_FILE_NAME)
                if (!file.exists()) file.createNewFile()
                FileOutputStream(file).use {
                    activeSessionHolder.getSafeActiveSession()?.securelyStoreObject(eventList, KEY_ALIAS_SECRET_STORAGE, it)
                }
            } catch (e: Throwable) {
                Timber.e(e, "## Failed to save cached notification info")
            }
        }
    }

    private fun loadEventInfo(): MutableList<NotifiableEvent> {
        try {
            val file = File(context.applicationContext.cacheDir, ROOMS_NOTIFICATIONS_FILE_NAME)
            if (file.exists()) {
                FileInputStream(file).use {
                    val events: ArrayList<NotifiableEvent>? = activeSessionHolder.getSafeActiveSession()?.loadSecureSecret(it, KEY_ALIAS_SECRET_STORAGE)
                    if (events != null) {
                        return events.toMutableList()
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "## Failed to load cached notification info")
        }
        return ArrayList()
    }

    private fun deleteCachedRoomNotifications() {
        val file = File(context.applicationContext.cacheDir, ROOMS_NOTIFICATIONS_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 0
        private const val ROOM_MESSAGES_NOTIFICATION_ID = 1
        private const val ROOM_EVENT_NOTIFICATION_ID = 2
        private const val ROOM_INVITATION_NOTIFICATION_ID = 3

        // TODO Mutliaccount
        private const val ROOMS_NOTIFICATIONS_FILE_NAME = "im.vector.notifications.cache"

        private const val KEY_ALIAS_SECRET_STORAGE = "notificationMgr"
    }
}

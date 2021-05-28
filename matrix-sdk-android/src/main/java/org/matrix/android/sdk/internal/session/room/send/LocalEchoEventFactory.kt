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

package org.matrix.android.sdk.internal.session.room.send

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.AudioInfo
import org.matrix.android.sdk.api.session.room.model.message.FileInfo
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageOptionsContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.OPTION_TYPE_POLL
import org.matrix.android.sdk.api.session.room.model.message.OptionItem
import org.matrix.android.sdk.api.session.room.model.message.ThumbnailInfo
import org.matrix.android.sdk.api.session.room.model.message.VideoInfo
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionInfo
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.room.timeline.isReply
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.permalinks.PermalinkFactory
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import javax.inject.Inject

/**
 * Creates local echo of events for room events.
 * A local echo is an event that is persisted even if not yet sent to the server,
 * in an optimistic way (as if the server as responded immediately). Local echo are using a local id,
 * (the transaction ID), this id is used when receiving an event from a sync to check if this event
 * is matching an existing local echo.
 *
 * The transactionId is used as loc
 */
internal class LocalEchoEventFactory @Inject constructor(
        private val context: Context,
        @UserId private val userId: String,
        private val markdownParser: MarkdownParser,
        private val textPillsUtils: TextPillsUtils,
        private val thumbnailExtractor: ThumbnailExtractor,
        private val localEchoRepository: LocalEchoRepository,
        private val permalinkFactory: PermalinkFactory
) {
    fun createTextEvent(roomId: String, msgType: String, text: CharSequence, autoMarkdown: Boolean): Event {
        if (msgType == MessageType.MSGTYPE_TEXT || msgType == MessageType.MSGTYPE_EMOTE) {
            return createFormattedTextEvent(roomId, createTextContent(text, autoMarkdown), msgType)
        }
        val content = MessageTextContent(msgType = msgType, body = text.toString())
        return createMessageEvent(roomId, content)
    }

    private fun createTextContent(text: CharSequence, autoMarkdown: Boolean): TextContent {
        if (autoMarkdown) {
            return markdownParser.parse(text)
        } else {
            // Try to detect pills
            textPillsUtils.processSpecialSpansToHtml(text)?.let {
                return TextContent(text.toString(), it)
            }
        }

        return TextContent(text.toString())
    }

    fun createFormattedTextEvent(roomId: String, textContent: TextContent, msgType: String): Event {
        return createMessageEvent(roomId, textContent.toMessageTextContent(msgType))
    }

    fun createReplaceTextEvent(roomId: String,
                               targetEventId: String,
                               newBodyText: CharSequence,
                               newBodyAutoMarkdown: Boolean,
                               msgType: String,
                               compatibilityText: String): Event {
        return createMessageEvent(roomId,
                MessageTextContent(
                        msgType = msgType,
                        body = compatibilityText,
                        relatesTo = RelationDefaultContent(RelationType.REPLACE, targetEventId),
                        newContent = createTextContent(newBodyText, newBodyAutoMarkdown)
                                .toMessageTextContent(msgType)
                                .toContent()
                ))
    }

    fun createOptionsReplyEvent(roomId: String,
                                pollEventId: String,
                                optionIndex: Int,
                                optionLabel: String): Event {
        return createMessageEvent(roomId,
                MessagePollResponseContent(
                        body = optionLabel,
                        relatesTo = RelationDefaultContent(
                                type = RelationType.RESPONSE,
                                option = optionIndex,
                                eventId = pollEventId)

                ))
    }

    fun createPollEvent(roomId: String,
                        question: String,
                        options: List<OptionItem>): Event {
        val compatLabel = buildString {
            append("[Poll] ")
            append(question)
            options.forEach {
                append("\n")
                append(it.value)
            }
        }
        return createMessageEvent(
                roomId,
                MessageOptionsContent(
                        body = compatLabel,
                        label = question,
                        optionType = OPTION_TYPE_POLL,
                        options = options.toList()
                )
        )
    }

    fun createReplaceTextOfReply(roomId: String,
                                 eventReplaced: TimelineEvent,
                                 originalEvent: TimelineEvent,
                                 newBodyText: String,
                                 newBodyAutoMarkdown: Boolean,
                                 msgType: String,
                                 compatibilityText: String): Event {
        val permalink = permalinkFactory.createPermalink(roomId, originalEvent.root.eventId ?: "")
        val userLink = originalEvent.root.senderId?.let { permalinkFactory.createPermalink(it) } ?: ""

        val body = bodyForReply(originalEvent.getLastMessageContent(), originalEvent.isReply())
        val replyFormatted = REPLY_PATTERN.format(
                permalink,
                userLink,
                originalEvent.senderInfo.disambiguatedDisplayName,
                // Remove inner mx_reply tags if any
                body.takeFormatted().replace(MX_REPLY_REGEX, ""),
                createTextContent(newBodyText, newBodyAutoMarkdown).takeFormatted()
        )
        //
        // > <@alice:example.org> This is the original body
        //
        val replyFallback = buildReplyFallback(body, originalEvent.root.senderId ?: "", newBodyText)

        return createMessageEvent(roomId,
                MessageTextContent(
                        msgType = msgType,
                        body = compatibilityText,
                        relatesTo = RelationDefaultContent(RelationType.REPLACE, eventReplaced.root.eventId),
                        newContent = MessageTextContent(
                                msgType = msgType,
                                format = MessageFormat.FORMAT_MATRIX_HTML,
                                body = replyFallback,
                                formattedBody = replyFormatted
                        )
                                .toContent()
                ))
    }

    fun createMediaEvent(roomId: String, attachment: ContentAttachmentData): Event {
        return when (attachment.type) {
            ContentAttachmentData.Type.IMAGE -> createImageEvent(roomId, attachment)
            ContentAttachmentData.Type.VIDEO -> createVideoEvent(roomId, attachment)
            ContentAttachmentData.Type.AUDIO -> createAudioEvent(roomId, attachment)
            ContentAttachmentData.Type.FILE  -> createFileEvent(roomId, attachment)
        }
    }

    fun createReactionEvent(roomId: String, targetEventId: String, reaction: String): Event {
        val content = ReactionContent(
                ReactionInfo(
                        RelationType.ANNOTATION,
                        targetEventId,
                        reaction
                )
        )
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.REACTION,
                content = content.toContent(),
                unsignedData = UnsignedData(age = null, transactionId = localId))
    }

    private fun createImageEvent(roomId: String, attachment: ContentAttachmentData): Event {
        var width = attachment.width
        var height = attachment.height

        when (attachment.exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                val tmp = width
                width = height
                height = tmp
            }
        }

        val content = MessageImageContent(
                msgType = MessageType.MSGTYPE_IMAGE,
                body = attachment.name ?: "image",
                info = ImageInfo(
                        mimeType = attachment.getSafeMimeType(),
                        width = width?.toInt() ?: 0,
                        height = height?.toInt() ?: 0,
                        size = attachment.size
                ),
                url = attachment.queryUri.toString()
        )
        return createMessageEvent(roomId, content)
    }

    private fun createVideoEvent(roomId: String, attachment: ContentAttachmentData): Event {
        val mediaDataRetriever = MediaMetadataRetriever()
        mediaDataRetriever.setDataSource(context, attachment.queryUri)

        // Use frame to calculate height and width as we are sure to get the right ones
        val firstFrame: Bitmap? = mediaDataRetriever.frameAtTime
        val height = firstFrame?.height ?: 0
        val width = firstFrame?.width ?: 0
        mediaDataRetriever.release()

        val thumbnailInfo = thumbnailExtractor.extractThumbnail(attachment)?.let {
            ThumbnailInfo(
                    width = it.width,
                    height = it.height,
                    size = it.size,
                    mimeType = it.mimeType
            )
        }
        val content = MessageVideoContent(
                msgType = MessageType.MSGTYPE_VIDEO,
                body = attachment.name ?: "video",
                videoInfo = VideoInfo(
                        mimeType = attachment.getSafeMimeType(),
                        width = width,
                        height = height,
                        size = attachment.size,
                        duration = attachment.duration?.toInt() ?: 0,
                        // Glide will be able to use the local path and extract a thumbnail.
                        thumbnailUrl = attachment.queryUri.toString(),
                        thumbnailInfo = thumbnailInfo
                ),
                url = attachment.queryUri.toString()
        )
        return createMessageEvent(roomId, content)
    }

    private fun createAudioEvent(roomId: String, attachment: ContentAttachmentData): Event {
        val content = MessageAudioContent(
                msgType = MessageType.MSGTYPE_AUDIO,
                body = attachment.name ?: "audio",
                audioInfo = AudioInfo(
                        mimeType = attachment.getSafeMimeType()?.takeIf { it.isNotBlank() },
                        size = attachment.size
                ),
                url = attachment.queryUri.toString()
        )
        return createMessageEvent(roomId, content)
    }

    private fun createFileEvent(roomId: String, attachment: ContentAttachmentData): Event {
        val content = MessageFileContent(
                msgType = MessageType.MSGTYPE_FILE,
                body = attachment.name ?: "file",
                info = FileInfo(
                        mimeType = attachment.getSafeMimeType()?.takeIf { it.isNotBlank() },
                        size = attachment.size
                ),
                url = attachment.queryUri.toString()
        )
        return createMessageEvent(roomId, content)
    }

    private fun createMessageEvent(roomId: String, content: MessageContent? = null): Event {
        return createEvent(roomId, EventType.MESSAGE, content.toContent())
    }

    fun createEvent(roomId: String, type: String, content: Content?): Event {
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = type,
                content = content,
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    private fun dummyOriginServerTs(): Long {
        return System.currentTimeMillis()
    }

    fun createReplyTextEvent(roomId: String,
                             eventReplied: TimelineEvent,
                             replyText: CharSequence,
                             autoMarkdown: Boolean): Event? {
        // Fallbacks and event representation
        // TODO Add error/warning logs when any of this is null
        val permalink = permalinkFactory.createPermalink(eventReplied.root) ?: return null
        val userId = eventReplied.root.senderId ?: return null
        val userLink = permalinkFactory.createPermalink(userId) ?: return null

        val body = bodyForReply(eventReplied.getLastMessageContent(), eventReplied.isReply())
        val replyFormatted = REPLY_PATTERN.format(
                permalink,
                userLink,
                userId,
                // Remove inner mx_reply tags if any
                body.takeFormatted().replace(MX_REPLY_REGEX, ""),
                createTextContent(replyText, autoMarkdown).takeFormatted()
        )
        //
        // > <@alice:example.org> This is the original body
        //
        val replyFallback = buildReplyFallback(body, userId, replyText.toString())

        val eventId = eventReplied.root.eventId ?: return null
        val content = MessageTextContent(
                msgType = MessageType.MSGTYPE_TEXT,
                format = MessageFormat.FORMAT_MATRIX_HTML,
                body = replyFallback,
                formattedBody = replyFormatted,
                relatesTo = RelationDefaultContent(null, null, ReplyToContent(eventId))
        )
        return createMessageEvent(roomId, content)
    }

    private fun buildReplyFallback(body: TextContent, originalSenderId: String?, newBodyText: String): String {
        return buildString {
            append("> <")
            append(originalSenderId)
            append(">")

            val lines = body.text.split("\n")
            lines.forEachIndexed { index, s ->
                if (index == 0) {
                    append(" $s")
                } else {
                    append("\n> $s")
                }
            }
            append("\n\n")
            append(newBodyText)
        }
    }

    /**
     * Returns a TextContent used for the fallback event representation in a reply message.
     * In case of an edit of a reply the last content is not
     * himself a reply, but it will contain the fallbacks, so we have to trim them.
     */
    private fun bodyForReply(content: MessageContent?, isReply: Boolean): TextContent {
        when (content?.msgType) {
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE -> {
                var formattedText: String? = null
                if (content is MessageContentWithFormattedBody) {
                    formattedText = content.matrixFormattedBody
                }
                return if (isReply) {
                    TextContent(content.body, formattedText).removeInReplyFallbacks()
                } else {
                    TextContent(content.body, formattedText)
                }
            }
            MessageType.MSGTYPE_FILE   -> return TextContent("sent a file.")
            MessageType.MSGTYPE_AUDIO  -> return TextContent("sent an audio file.")
            MessageType.MSGTYPE_IMAGE  -> return TextContent("sent an image.")
            MessageType.MSGTYPE_VIDEO  -> return TextContent("sent a video.")
            else                       -> return TextContent(content?.body ?: "")
        }
    }

    /*
     * {
        "content": {
            "reason": "Spamming"
            },
            "event_id": "$143273582443PhrSn:domain.com",
            "origin_server_ts": 1432735824653,
            "redacts": "$fukweghifu23:localhost",
            "room_id": "!jEsUZKDJdhlrceRyVU:domain.com",
            "sender": "@example:domain.com",
            "type": "m.room.redaction",
            "unsigned": {
            "age": 1234
        }
    }
     */
    fun createRedactEvent(roomId: String, eventId: String, reason: String?): Event {
        val localId = LocalEcho.createLocalEchoId()
        return Event(
                roomId = roomId,
                originServerTs = dummyOriginServerTs(),
                senderId = userId,
                eventId = localId,
                type = EventType.REDACTION,
                redacts = eventId,
                content = reason?.let { mapOf("reason" to it).toContent() },
                unsignedData = UnsignedData(age = null, transactionId = localId)
        )
    }

    fun createLocalEcho(event: Event) {
        checkNotNull(event.roomId) { "Your event should have a roomId" }
        localEchoRepository.createLocalEcho(event)
    }

    companion object {
        // <mx-reply>
        //     <blockquote>
        //         <a href="https://matrix.to/#/!somewhere:domain.com/$event:domain.com">In reply to</a>
        //         <a href="https://matrix.to/#/@alice:example.org">@alice:example.org</a>
        //         <br />
        //         <!-- This is where the related event's HTML would be. -->
        //     </blockquote>
        // </mx-reply>
        // No whitespace because currently breaks temporary formatted text to Span
        const val REPLY_PATTERN = """<mx-reply><blockquote><a href="%s">In reply to</a> <a href="%s">%s</a><br />%s</blockquote></mx-reply>%s"""

        // This is used to replace inner mx-reply tags
        val MX_REPLY_REGEX = "<mx-reply>.*</mx-reply>".toRegex()
    }
}

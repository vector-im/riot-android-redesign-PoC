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

package im.vector.app.features.home.room.detail.timeline.format

import im.vector.app.EmojiCompatWrapper
import im.vector.app.R
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageOptionsContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.OPTION_TYPE_BUTTONS
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.room.timeline.getTextEditableContent
import org.matrix.android.sdk.api.session.room.timeline.isReply
import javax.inject.Inject

class DisplayableEventFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val emojiCompatWrapper: EmojiCompatWrapper,
        private val noticeEventFormatter: NoticeEventFormatter
) {

    fun format(timelineEvent: TimelineEvent, appendAuthor: Boolean): CharSequence {
        if (timelineEvent.root.isRedacted()) {
            return noticeEventFormatter.formatRedactedEvent(timelineEvent.root)
        }

        if (timelineEvent.root.isEncrypted()
                && timelineEvent.root.mxDecryptionResult == null) {
            return stringProvider.getString(R.string.encrypted_message)
        }

        val senderName = timelineEvent.senderInfo.disambiguatedDisplayName

        when (timelineEvent.root.getClearType()) {
            EventType.STICKER               -> {
                return simpleFormat(senderName, stringProvider.getString(R.string.send_a_sticker), appendAuthor)
            }
            EventType.REACTION              -> {
                timelineEvent.root.getClearContent().toModel<ReactionContent>()?.relatesTo?.let {
                    val emojiSpanned = emojiCompatWrapper.safeEmojiSpanify(stringProvider.getString(R.string.sent_a_reaction, it.key))
                    return simpleFormat(senderName, emojiSpanned, appendAuthor)
                }
            }
            EventType.MESSAGE               -> {
                timelineEvent.getLastMessageContent()?.let { messageContent ->
                    when (messageContent.msgType) {
                        MessageType.MSGTYPE_VERIFICATION_REQUEST -> {
                            return simpleFormat(senderName, stringProvider.getString(R.string.verification_request), appendAuthor)
                        }
                        MessageType.MSGTYPE_IMAGE                -> {
                            return simpleFormat(senderName, stringProvider.getString(R.string.sent_an_image), appendAuthor)
                        }
                        MessageType.MSGTYPE_AUDIO                -> {
                            if ((messageContent as? MessageAudioContent)?.voiceMessageIndicator != null) {
                                return simpleFormat(senderName, stringProvider.getString(R.string.sent_a_voice_message), appendAuthor)
                            } else {
                                return simpleFormat(senderName, stringProvider.getString(R.string.sent_an_audio_file), appendAuthor)
                            }
                        }
                        MessageType.MSGTYPE_VIDEO                -> {
                            return simpleFormat(senderName, stringProvider.getString(R.string.sent_a_video), appendAuthor)
                        }
                        MessageType.MSGTYPE_FILE                 -> {
                            return simpleFormat(senderName, stringProvider.getString(R.string.sent_a_file), appendAuthor)
                        }
                        MessageType.MSGTYPE_TEXT                 -> {
                            return if (timelineEvent.isReply()) {
                                // Skip reply prefix, and show important
                                // TODO add a reply image span ?
                                simpleFormat(senderName, timelineEvent.getTextEditableContent()
                                        ?: messageContent.body, appendAuthor)
                            } else {
                                simpleFormat(senderName, messageContent.body, appendAuthor)
                            }
                        }
                        MessageType.MSGTYPE_RESPONSE             -> {
                            // do not show that?
                            return span { }
                        }
                        MessageType.MSGTYPE_OPTIONS              -> {
                            return when (messageContent) {
                                is MessageOptionsContent -> {
                                    val previewText = if (messageContent.optionType == OPTION_TYPE_BUTTONS) {
                                        stringProvider.getString(R.string.sent_a_bot_buttons)
                                    } else {
                                        stringProvider.getString(R.string.sent_a_poll)
                                    }
                                    simpleFormat(senderName, previewText, appendAuthor)
                                }
                                else                     -> {
                                    span { }
                                }
                            }
                        }
                        else                                     -> {
                            return simpleFormat(senderName, messageContent.body, appendAuthor)
                        }
                    }
                }
            }
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_DONE -> {
                // cancel and done can appear in timeline, so should have representation
                return simpleFormat(senderName, stringProvider.getString(R.string.sent_verification_conclusion), appendAuthor)
            }
            EventType.KEY_VERIFICATION_START,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_MAC,
            EventType.KEY_VERIFICATION_KEY,
            EventType.KEY_VERIFICATION_READY,
            EventType.CALL_CANDIDATES       -> {
                return span { }
            }
            else                            -> {
                return span {
                    text = noticeEventFormatter.format(timelineEvent) ?: ""
                    textStyle = "italic"
                }
            }
        }

        return span { }
    }

    private fun simpleFormat(senderName: String, body: CharSequence, appendAuthor: Boolean): CharSequence {
        return if (appendAuthor) {
            span {
                text = senderName
                textColor = colorProvider.getColorFromAttribute(R.attr.vctr_content_primary)
            }
                    .append(": ")
                    .append(body)
        } else {
            body
        }
    }
}

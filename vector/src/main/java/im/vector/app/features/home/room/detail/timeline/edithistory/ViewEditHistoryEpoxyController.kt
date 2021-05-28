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
package im.vector.app.features.home.room.detail.timeline.edithistory

import android.text.Spannable
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Incomplete
import com.airbnb.mvrx.Success
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.ui.list.genericItemHeader
import im.vector.app.core.ui.list.genericLoaderItem
import im.vector.app.features.html.EventHtmlRenderer
import me.gujun.android.span.span
import name.fraser.neil.plaintext.diff_match_patch
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.util.ContentUtils.extractUsefulTextFromReply
import org.matrix.android.sdk.internal.session.room.send.TextContent
import java.util.Calendar
import javax.inject.Inject

/**
 * Epoxy controller for edit history list
 */
class ViewEditHistoryEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val eventHtmlRenderer: EventHtmlRenderer,
        private val dateFormatter: VectorDateFormatter
) : TypedEpoxyController<ViewEditHistoryViewState>() {

    override fun buildModels(state: ViewEditHistoryViewState) {
        val host = this
        when (state.editList) {
            is Incomplete -> {
                genericLoaderItem {
                    id("Spinner")
                }
            }
            is Fail       -> {
                genericFooterItem {
                    id("failure")
                    text(host.stringProvider.getString(R.string.unknown_error))
                }
            }
            is Success    -> {
                state.editList()?.let { renderEvents(it, state.isOriginalAReply) }
            }
        }
    }

    private fun renderEvents(sourceEvents: List<Event>, isOriginalReply: Boolean) {
        val host = this
        if (sourceEvents.isEmpty()) {
            genericItem {
                id("footer")
                title(host.stringProvider.getString(R.string.no_message_edits_found))
            }
        } else {
            var lastDate: Calendar? = null
            sourceEvents.forEachIndexed { index, timelineEvent ->

                val evDate = Calendar.getInstance().apply {
                    timeInMillis = timelineEvent.originServerTs
                            ?: System.currentTimeMillis()
                }
                if (lastDate?.get(Calendar.DAY_OF_YEAR) != evDate.get(Calendar.DAY_OF_YEAR)) {
                    // need to display header with day
                    genericItemHeader {
                        id(evDate.hashCode())
                        text(host.dateFormatter.format(evDate.timeInMillis, DateFormatKind.EDIT_HISTORY_HEADER))
                    }
                }
                lastDate = evDate
                val cContent = getCorrectContent(timelineEvent, isOriginalReply)
                val body = cContent.formattedText?.let { eventHtmlRenderer.render(it) } ?: cContent.text

                val nextEvent = sourceEvents.getOrNull(index + 1)

                var spannedDiff: Spannable? = null
                if (nextEvent != null && cContent.formattedText == null /*No diff for html*/) {
                    // compares the body
                    val nContent = getCorrectContent(nextEvent, isOriginalReply)
                    val nextBody = nContent.formattedText?.let { eventHtmlRenderer.render(it) } ?: nContent.text
                    val dmp = diff_match_patch()
                    val diff = dmp.diff_main(nextBody.toString(), body.toString())
                    dmp.diff_cleanupSemantic(diff)
                    spannedDiff = span {
                        diff.map {
                            when (it.operation) {
                                diff_match_patch.Operation.DELETE -> {
                                    span {
                                        text = it.text.replace("\n", " ")
                                        textColor = colorProvider.getColor(R.color.vector_error_color)
                                        textDecorationLine = "line-through"
                                    }
                                }
                                diff_match_patch.Operation.INSERT -> {
                                    span {
                                        text = it.text
                                        textColor = colorProvider.getColor(R.color.vector_success_color)
                                    }
                                }
                                else                              -> {
                                    span {
                                        text = it.text
                                    }
                                }
                            }
                        }
                    }
                }
                genericItem {
                    id(timelineEvent.eventId)
                    title(host.dateFormatter.format(timelineEvent.originServerTs, DateFormatKind.EDIT_HISTORY_ROW))
                    description(spannedDiff ?: body)
                }
            }
        }
    }

    private fun getCorrectContent(event: Event, isOriginalReply: Boolean): TextContent {
        val clearContent = event.getClearContent().toModel<MessageTextContent>()
        val newContent = clearContent
                ?.newContent
                ?.toModel<MessageTextContent>()
        if (isOriginalReply) {
            return TextContent(extractUsefulTextFromReply(newContent?.body ?: clearContent?.body ?: ""))
        }
        return TextContent(newContent?.body ?: clearContent?.body ?: "", newContent?.formattedBody ?: clearContent?.formattedBody)
    }
}

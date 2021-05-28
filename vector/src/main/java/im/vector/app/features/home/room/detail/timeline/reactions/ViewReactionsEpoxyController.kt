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

package im.vector.app.features.home.room.detail.timeline.reactions

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Incomplete
import com.airbnb.mvrx.Success
import im.vector.app.EmojiCompatWrapper
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericLoaderItem
import javax.inject.Inject

/**
 * Epoxy controller for reaction event list
 */
class ViewReactionsEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val emojiCompatWrapper: EmojiCompatWrapper)
    : TypedEpoxyController<DisplayReactionsViewState>() {

    var listener: Listener? = null

    override fun buildModels(state: DisplayReactionsViewState) {
        val host = this
        when (state.mapReactionKeyToMemberList) {
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
                state.mapReactionKeyToMemberList()?.forEach {
                    reactionInfoSimpleItem {
                        id(it.eventId)
                        timeStamp(it.timestamp)
                        reactionKey(host.emojiCompatWrapper.safeEmojiSpanify(it.reactionKey))
                        authorDisplayName(it.authorName ?: it.authorId)
                        userClicked { host.listener?.didSelectUser(it.authorId) }
                    }
                }
            }
        }
    }

    interface Listener {
        fun didSelectUser(userId: String)
    }
}

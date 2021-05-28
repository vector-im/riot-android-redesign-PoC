/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.devtools

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.GenericItem
import im.vector.app.core.ui.list.genericItem
import me.gujun.android.span.span
import org.json.JSONObject
import javax.inject.Inject

class RoomStateListController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider
) : TypedEpoxyController<RoomDevToolViewState>() {

    var interactionListener: DevToolsInteractionListener? = null

    override fun buildModels(data: RoomDevToolViewState?) {
        val host = this
        when (data?.displayMode) {
            RoomDevToolViewState.Mode.StateEventList -> {
                val stateEventsGroups = data.stateEvents.invoke().orEmpty().groupBy { it.getClearType() }

                if (stateEventsGroups.isEmpty()) {
                    noResultItem {
                        id("no state events")
                        text(host.stringProvider.getString(R.string.no_result_placeholder))
                    }
                } else {
                    stateEventsGroups.forEach { entry ->
                        genericItem {
                            id(entry.key)
                            title(entry.key)
                            description(host.stringProvider.getQuantityString(R.plurals.entries, entry.value.size, entry.value.size))
                            itemClickAction(GenericItem.Action("view").apply {
                                perform = Runnable {
                                    host.interactionListener?.processAction(RoomDevToolAction.ShowStateEventType(entry.key))
                                }
                            })
                        }
                    }
                }
            }
            RoomDevToolViewState.Mode.StateEventListByType -> {
                val stateEvents = data.stateEvents.invoke().orEmpty().filter { it.type == data.currentStateType }
                if (stateEvents.isEmpty()) {
                    noResultItem {
                        id("no state events")
                        text(host.stringProvider.getString(R.string.no_result_placeholder))
                    }
                } else {
                    stateEvents.forEach { stateEvent ->
                        val contentJson = JSONObject(stateEvent.content.orEmpty()).toString().let {
                            if (it.length > 140) {
                                it.take(140) + Typography.ellipsis
                            } else {
                                it.take(140)
                            }
                        }
                        genericItem {
                            id(stateEvent.eventId)
                            title(span {
                                +"Type: "
                                span {
                                    textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                    text = "\"${stateEvent.type}\""
                                    textStyle = "normal"
                                }
                                +"\nState Key: "
                                span {
                                    textColor = host.colorProvider.getColorFromAttribute(R.attr.riotx_text_secondary)
                                    text = stateEvent.stateKey.let { "\"$it\"" }
                                    textStyle = "normal"
                                }
                            })
                            description(contentJson)
                            itemClickAction(GenericItem.Action("view").apply {
                                perform = Runnable {
                                    host.interactionListener?.processAction(RoomDevToolAction.ShowStateEvent(stateEvent))
                                }
                            })
                        }
                    }
                }
            }
            else                                           -> {
                // nop
            }
        }
    }
}

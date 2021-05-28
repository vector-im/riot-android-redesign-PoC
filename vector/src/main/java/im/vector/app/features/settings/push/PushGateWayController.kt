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

package im.vector.app.features.settings.push

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import javax.inject.Inject

class PushGateWayController @Inject constructor(
        private val stringProvider: StringProvider
) : TypedEpoxyController<PushGatewayViewState>() {

    override fun buildModels(data: PushGatewayViewState?) {
        val host = this
        data?.pushGateways?.invoke()?.let { pushers ->
            if (pushers.isEmpty()) {
                genericFooterItem {
                    id("footer")
                    text(host.stringProvider.getString(R.string.settings_push_gateway_no_pushers))
                }
            } else {
                pushers.forEach {
                    pushGatewayItem {
                        id("${it.pushKey}_${it.appId}")
                        pusher(it)
                    }
                }
            }
        } ?: run {
            genericFooterItem {
                id("loading")
                text(host.stringProvider.getString(R.string.loading))
            }
        }
    }
}

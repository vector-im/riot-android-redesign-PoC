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

package im.vector.app.features.settings.ignored

import com.airbnb.epoxy.EpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class IgnoredUsersController @Inject constructor(private val stringProvider: StringProvider,
                                                 private val avatarRenderer: AvatarRenderer) : EpoxyController() {

    var callback: Callback? = null
    private var viewState: IgnoredUsersViewState? = null

    init {
        requestModelBuild()
    }

    fun update(viewState: IgnoredUsersViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    override fun buildModels() {
        val nonNullViewState = viewState ?: return
        buildIgnoredUserModels(nonNullViewState.ignoredUsers)
    }

    private fun buildIgnoredUserModels(users: List<User>) {
        val host = this
        if (users.isEmpty()) {
            noResultItem {
                id("empty")
                text(host.stringProvider.getString(R.string.no_ignored_users))
            }
        } else {
            users.forEach { user ->
                userItem {
                    id(user.userId)
                    avatarRenderer(host.avatarRenderer)
                    matrixItem(user.toMatrixItem())
                    itemClickAction { host.callback?.onUserIdClicked(user.userId) }
                }
            }
        }
    }

    interface Callback {
        fun onUserIdClicked(userId: String)
    }
}

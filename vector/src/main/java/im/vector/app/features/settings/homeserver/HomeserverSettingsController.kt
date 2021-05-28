/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.settings.homeserver

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.discovery.settingsCenteredImageItem
import im.vector.app.features.discovery.settingsInfoItem
import im.vector.app.features.discovery.settingsSectionTitleItem
import org.matrix.android.sdk.api.federation.FederationVersion
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import javax.inject.Inject

class HomeserverSettingsController @Inject constructor(
        private val stringProvider: StringProvider,
        private val errorFormatter: ErrorFormatter
) : TypedEpoxyController<HomeServerSettingsViewState>() {

    var callback: Callback? = null

    interface Callback {
        fun retry()
    }

    override fun buildModels(data: HomeServerSettingsViewState?) {
        data ?: return
        val host = this

        buildHeader(data)
        buildCapabilities(data)
        when (val federationVersion = data.federationVersion) {
            is Loading,
            is Uninitialized ->
                loadingItem {
                    id("loading")
                }
            is Fail          ->
                errorWithRetryItem {
                    id("error")
                    text(host.errorFormatter.toHumanReadable(federationVersion.error))
                    listener { host.callback?.retry() }
                }
            is Success       ->
                buildFederationVersion(federationVersion())
        }
    }

    private fun buildHeader(state: HomeServerSettingsViewState) {
        settingsCenteredImageItem {
            id("icon")
            drawableRes(R.drawable.ic_layers)
        }
        settingsSectionTitleItem {
            id("urlTitle")
            titleResId(R.string.hs_url)
        }
        settingsInfoItem {
            id("urlValue")
            helperText(state.baseUrl)
        }
    }

    private fun buildFederationVersion(federationVersion: FederationVersion) {
        settingsSectionTitleItem {
            id("nameTitle")
            titleResId(R.string.settings_server_name)
        }
        settingsInfoItem {
            id("nameValue")
            helperText(federationVersion.name)
        }
        settingsSectionTitleItem {
            id("versionTitle")
            titleResId(R.string.settings_server_version)
        }
        settingsInfoItem {
            id("versionValue")
            helperText(federationVersion.version)
        }
    }

    private fun buildCapabilities(data: HomeServerSettingsViewState) {
        val host = this
        settingsSectionTitleItem {
            id("uploadTitle")
            titleResId(R.string.settings_server_upload_size_title)
        }

        val limit = data.homeServerCapabilities.maxUploadFileSize

        settingsInfoItem {
            id("uploadValue")
            if (limit == HomeServerCapabilities.MAX_UPLOAD_FILE_SIZE_UNKNOWN) {
                helperTextResId(R.string.settings_server_upload_size_unknown)
            } else {
                helperText(host.stringProvider.getString(R.string.settings_server_upload_size_content, "${limit / 1048576L} MB"))
            }
        }
    }
}

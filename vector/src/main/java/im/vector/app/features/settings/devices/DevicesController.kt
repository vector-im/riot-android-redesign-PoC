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

package im.vector.app.features.settings.devices

import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericItemHeader
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceInfo
import javax.inject.Inject

class DevicesController @Inject constructor(private val errorFormatter: ErrorFormatter,
                                            private val stringProvider: StringProvider,
                                            private val colorProvider: ColorProvider,
                                            private val dateFormatter: VectorDateFormatter,
                                            private val dimensionConverter: DimensionConverter,
                                            private val vectorPreferences: VectorPreferences) : EpoxyController() {

    var callback: Callback? = null
    private var viewState: DevicesViewState? = null

    init {
        requestModelBuild()
    }

    fun update(viewState: DevicesViewState) {
        this.viewState = viewState
        requestModelBuild()
    }

    override fun buildModels() {
        val nonNullViewState = viewState ?: return
        buildDevicesModels(nonNullViewState)
    }

    private fun buildDevicesModels(state: DevicesViewState) {
        val host = this
        when (val devices = state.devices) {
            is Loading,
            is Uninitialized ->
                loadingItem {
                    id("loading")
                }
            is Fail          ->
                errorWithRetryItem {
                    id("error")
                    text(host.errorFormatter.toHumanReadable(devices.error))
                    listener { host.callback?.retry() }
                }
            is Success       ->
                buildDevicesList(devices(), state.myDeviceId, !state.hasAccountCrossSigning, state.accountCrossSigningIsTrusted)
        }
    }

    private fun buildDevicesList(devices: List<DeviceFullInfo>,
                                 myDeviceId: String,
                                 legacyMode: Boolean,
                                 currentSessionCrossTrusted: Boolean) {
        val host = this
        devices
                .firstOrNull {
                    it.deviceInfo.deviceId == myDeviceId
                }?.let { fullInfo ->
                    val deviceInfo = fullInfo.deviceInfo
                    // Current device
                    genericItemHeader {
                        id("current")
                        text(host.stringProvider.getString(R.string.devices_current_device))
                    }

                    deviceItem {
                        id("myDevice${deviceInfo.deviceId}")
                        legacyMode(legacyMode)
                        trustedSession(currentSessionCrossTrusted)
                        dimensionConverter(host.dimensionConverter)
                        colorProvider(host.colorProvider)
                        detailedMode(host.vectorPreferences.developerMode())
                        deviceInfo(deviceInfo)
                        currentDevice(true)
                        e2eCapable(true)
                        lastSeenFormatted(host.dateFormatter.format(deviceInfo.lastSeenTs, DateFormatKind.DEFAULT_DATE_AND_TIME))
                        itemClickAction { host.callback?.onDeviceClicked(deviceInfo) }
                        trusted(DeviceTrustLevel(currentSessionCrossTrusted, true))
                    }

//                    // If cross signing enabled and this session not trusted, add short cut to complete security
                    // NEED DESIGN
//                    if (!legacyMode && !currentSessionCrossTrusted) {
//                        genericButtonItem {
//                            id("complete_security")
//                            iconRes(R.drawable.ic_shield_warning)
//                            text(stringProvider.getString(R.string.complete_security))
//                            buttonClickAction(DebouncedClickListener(View.OnClickListener { _ ->
//                                callback?.completeSecurity()
//                            }))
//                        }
//                    }
                }

        // Other devices
        if (devices.size > 1) {
            genericItemHeader {
                id("others")
                text(host.stringProvider.getString(R.string.devices_other_devices))
            }

            devices
                    .filter {
                        it.deviceInfo.deviceId != myDeviceId
                    }
                    .forEachIndexed { idx, deviceInfoPair ->
                        val deviceInfo = deviceInfoPair.deviceInfo
                        val cryptoInfo = deviceInfoPair.cryptoDeviceInfo
                        deviceItem {
                            id("device$idx")
                            legacyMode(legacyMode)
                            trustedSession(currentSessionCrossTrusted)
                            dimensionConverter(host.dimensionConverter)
                            colorProvider(host.colorProvider)
                            detailedMode(host.vectorPreferences.developerMode())
                            deviceInfo(deviceInfo)
                            currentDevice(false)
                            itemClickAction { host.callback?.onDeviceClicked(deviceInfo) }
                            e2eCapable(cryptoInfo != null)
                            trusted(cryptoInfo?.trustLevel)
                        }
                    }
        }
    }

    interface Callback {
        fun retry()
        fun onDeviceClicked(deviceInfo: DeviceInfo)
    }
}

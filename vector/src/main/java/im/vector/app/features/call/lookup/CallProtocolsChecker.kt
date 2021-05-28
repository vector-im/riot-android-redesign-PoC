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

package im.vector.app.features.call.lookup

import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.thirdparty.ThirdPartyProtocol
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

const val PROTOCOL_PSTN_PREFIXED = "im.vector.protocol.pstn"
const val PROTOCOL_PSTN = "m.protocol.pstn"
const val PROTOCOL_SIP_NATIVE = "im.vector.protocol.sip_native"
const val PROTOCOL_SIP_VIRTUAL = "im.vector.protocol.sip_virtual"

class CallProtocolsChecker(private val session: Session) {

    interface Listener {
        fun onPSTNSupportUpdated() = Unit
        fun onVirtualRoomSupportUpdated() = Unit
    }

    private val alreadyChecked = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    var supportedPSTNProtocol: String? = null
        private set

    var supportVirtualRooms: Boolean = false
        private set

    fun checkProtocols() {
        session.coroutineScope.launch {
            checkThirdPartyProtocols()
        }
    }

    suspend fun awaitCheckProtocols() {
        checkThirdPartyProtocols()
    }

    private suspend fun checkThirdPartyProtocols() {
        if (alreadyChecked.get()) return
        if (!checking.compareAndSet(false, true)) return
        try {
            val protocols = getThirdPartyProtocols(3)
            alreadyChecked.set(true)
            checking.set(false)
            supportedPSTNProtocol = protocols.extractPSTN()
            if (supportedPSTNProtocol != null) {
                listeners.forEach {
                    tryOrNull { it.onPSTNSupportUpdated() }
                }
            }
            supportVirtualRooms = protocols.supportsVirtualRooms()
            if (supportVirtualRooms) {
                listeners.forEach {
                    tryOrNull { it.onVirtualRoomSupportUpdated() }
                }
            }
        } catch (failure: Throwable) {
            Timber.v("Fail to get third party protocols, will check again next time.")
        }
    }

    private fun Map<String, ThirdPartyProtocol>.extractPSTN(): String? {
        return when {
            containsKey(PROTOCOL_PSTN_PREFIXED) -> PROTOCOL_PSTN_PREFIXED
            containsKey(PROTOCOL_PSTN)          -> PROTOCOL_PSTN
            else                                -> null
        }
    }

    private fun Map<String, ThirdPartyProtocol>.supportsVirtualRooms(): Boolean {
        return containsKey(PROTOCOL_SIP_VIRTUAL) && containsKey(PROTOCOL_SIP_NATIVE)
    }

    private suspend fun getThirdPartyProtocols(maxTries: Int): Map<String, ThirdPartyProtocol> {
        return try {
            session.thirdPartyService().getThirdPartyProtocols()
        } catch (failure: Throwable) {
            if (maxTries == 1) {
                throw failure
            } else {
                // Wait for 10s before trying again
                delay(10_000L)
                return getThirdPartyProtocols(maxTries - 1)
            }
        }
    }
}

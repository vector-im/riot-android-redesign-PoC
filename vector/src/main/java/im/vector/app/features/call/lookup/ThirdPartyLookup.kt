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

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.thirdparty.model.ThirdPartyUser

suspend fun Session.pstnLookup(phoneNumber: String, protocol: String?): List<ThirdPartyUser> {
    if (protocol == null) return emptyList()
    return tryOrNull {
        thirdPartyService().getThirdPartyUser(
                protocol = protocol,
                fields = mapOf("m.id.phone" to phoneNumber)
        )
    }.orEmpty()
}

suspend fun Session.sipVirtualLookup(nativeMxid: String): List<ThirdPartyUser> {
    return tryOrNull {
        thirdPartyService().getThirdPartyUser(
                protocol = PROTOCOL_SIP_VIRTUAL,
                fields = mapOf("native_mxid" to nativeMxid)
        )
    }.orEmpty()
}

suspend fun Session.sipNativeLookup(virtualMxid: String): List<ThirdPartyUser> {
    return tryOrNull {
        thirdPartyService().getThirdPartyUser(
                protocol = PROTOCOL_SIP_NATIVE,
                fields = mapOf("virtual_mxid" to virtualMxid)
        )
    }.orEmpty()
}

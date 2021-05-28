/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.call.conference

import im.vector.app.core.platform.VectorViewEvents
import org.jitsi.meet.sdk.JitsiMeetUserInfo

sealed class JitsiCallViewEvents : VectorViewEvents {
    data class JoinConference(
            val enableVideo: Boolean,
            val jitsiUrl: String,
            val subject: String,
            val confId: String,
            val userInfo: JitsiMeetUserInfo,
            val token: String?
    ) : JitsiCallViewEvents()

    data class ConfirmSwitchingConference(
            val args: VectorJitsiActivity.Args
    ) : JitsiCallViewEvents()

    object LeaveConference : JitsiCallViewEvents()
    object FailJoiningConference: JitsiCallViewEvents()
    object Finish : JitsiCallViewEvents()
}

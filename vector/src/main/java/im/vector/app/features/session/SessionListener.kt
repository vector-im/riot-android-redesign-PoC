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

package im.vector.app.features.session

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import im.vector.app.core.extensions.postLiveEvent
import im.vector.app.core.utils.LiveEvent
import kotlinx.coroutines.cancelChildren
import im.vector.app.features.call.vectorCallService
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.failure.GlobalError
import org.matrix.android.sdk.api.session.Session
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionListener @Inject constructor() : Session.Listener {

    private val _globalErrorLiveData = MutableLiveData<LiveEvent<GlobalError>>()
    val globalErrorLiveData: LiveData<LiveEvent<GlobalError>>
        get() = _globalErrorLiveData

    override fun onGlobalError(session: Session, globalError: GlobalError) {
        _globalErrorLiveData.postLiveEvent(globalError)
    }

    override fun onNewInvitedRoom(session: Session, roomId: String) {
        session.coroutineScope.launch {
            session.vectorCallService.userMapper.onNewInvitedRoom(roomId)
        }
    }

    override fun onSessionStopped(session: Session) {
        session.coroutineScope.coroutineContext.cancelChildren()
    }

    override fun onClearCache(session: Session) {
        session.coroutineScope.coroutineContext.cancelChildren()
    }
}

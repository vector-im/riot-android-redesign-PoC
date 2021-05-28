/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.di.SessionId
import javax.inject.Inject

@SessionScope
internal class SessionListeners @Inject constructor(
        @SessionId private val sessionId: String,
        private val sessionManager: SessionManager) {

    private val listeners = mutableSetOf<Session.Listener>()

    fun addListener(listener: Session.Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Session.Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun dispatch(block: (Session, Session.Listener) -> Unit) {
        synchronized(listeners) {
            val session = getSession()
            listeners.forEach {
                tryOrNull { block(session, it) }
            }
        }
    }

    private fun getSession(): Session {
        return sessionManager.getSessionComponent(sessionId)?.session()
                ?: throw IllegalStateException("No session found with this id.")
    }
}

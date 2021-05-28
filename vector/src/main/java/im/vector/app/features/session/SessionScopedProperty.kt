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

package im.vector.app.features.session

import org.matrix.android.sdk.api.session.Session
import kotlin.reflect.KProperty

/**
 * This is a simple hack for having some Session scope dependencies.
 * Probably a temporary solution waiting for refactoring the Dagger management of Session.
 * You should use it with an extension property :
    val Session.myProperty: MyProperty by SessionScopedProperty {
        init code
    }
 *
 */
class SessionScopedProperty<T : Any>(val initializer: (Session) -> T) {

    private val propertyBySessionId = HashMap<String, T>()

    private val sessionListener = object : Session.Listener {

        override fun onSessionStopped(session: Session) {
            synchronized(propertyBySessionId) {
                session.removeListener(this)
                propertyBySessionId.remove(session.sessionId)
            }
        }
    }

    operator fun getValue(thisRef: Session, property: KProperty<*>): T = synchronized(propertyBySessionId) {
        propertyBySessionId.getOrPut(thisRef.sessionId) {
            thisRef.addListener(sessionListener)
            initializer(thisRef)
        }
    }
}

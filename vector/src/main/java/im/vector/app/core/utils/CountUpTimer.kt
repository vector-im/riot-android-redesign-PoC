/*
 * Copyright 2020 New Vector Ltd
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

package im.vector.app.core.utils

import io.reactivex.Observable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CountUpTimer(private val intervalInMs: Long = 1_000) {

    private val elapsedTime: AtomicLong = AtomicLong()
    private val resumed: AtomicBoolean = AtomicBoolean(false)

    private val disposable = Observable.interval(intervalInMs / 10, TimeUnit.MILLISECONDS)
            .filter { resumed.get() }
            .map { elapsedTime.addAndGet(intervalInMs / 10) }
            .filter { it % intervalInMs == 0L }
            .subscribe {
                tickListener?.onTick(it)
            }

    var tickListener: TickListener? = null

    fun elapsedTime(): Long {
        return elapsedTime.get()
    }

    fun pause() {
        resumed.set(false)
    }

    fun resume() {
        resumed.set(true)
    }

    fun stop() {
        disposable.dispose()
    }

    interface TickListener {
        fun onTick(milliseconds: Long)
    }
}

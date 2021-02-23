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

package org.matrix.android.sdk.internal.session.room.send.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.failure.isTokenError
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.exhaustive
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import kotlin.concurrent.schedule

/**
 * A simple ever running thread unique for that session responsible of sending events in order.
 * Each send is retried 3 times, if there is no network (e.g if cannot ping home server) it will wait and
 * periodically test reachability before resume (does not count as a retry)
 *
 * If the app is killed before all event were sent, on next wakeup the scheduled events will be re posted
 */
@SessionScope
internal class EventSenderProcessor @Inject constructor(
        private val cryptoService: CryptoService,
        private val sessionParams: SessionParams,
        private val queuedTaskFactory: QueuedTaskFactory,
        private val taskExecutor: TaskExecutor,
        private val memento: QueueMemento
) : Thread("SENDER_THREAD_SID_${sessionParams.credentials.sessionId()}") {

    private fun markAsManaged(task: QueuedTask) {
        memento.track(task)
    }

    private fun markAsFinished(task: QueuedTask) {
        memento.unTrack(task)
    }

    // API
    fun postEvent(event: Event): Cancelable {
        return postEvent(event, event.roomId?.let { cryptoService.isRoomEncrypted(it) } ?: false)
    }

    override fun start() {
        super.start()
        // We should check for sending events not handled because app was killed
        // But we should be careful of only took those that was submitted to us, because if it's
        // for example it's a media event it is handled by some worker and he will handle it
        // This is a bit fragile :/
        // also some events cannot be retried manually by users, e.g reactions
        // they were previously relying on workers to do the work :/ and was expected to always finally succeed
        // Also some echos are not to be resent like redaction echos (fake event created for aggregation)

        tryOrNull {
            taskExecutor.executorScope.launch {
                Timber.d("## Send relaunched pending events on restart")
                memento.restoreTasks(this@EventSenderProcessor)
            }
        }
    }

    fun postEvent(event: Event, encrypt: Boolean): Cancelable {
        val task = queuedTaskFactory.createSendTask(event, encrypt)
        return postTask(task)
    }

    fun postRedaction(redactionLocalEcho: Event, reason: String?): Cancelable {
        return postRedaction(redactionLocalEcho.eventId!!, redactionLocalEcho.redacts!!, redactionLocalEcho.roomId!!, reason)
    }

    fun postRedaction(redactionLocalEchoId: String, eventToRedactId: String, roomId: String, reason: String?): Cancelable {
        val task = queuedTaskFactory.createRedactTask(redactionLocalEchoId, eventToRedactId, roomId, reason)
        return postTask(task)
    }

    fun postTask(task: QueuedTask): Cancelable {
        // non blocking add to queue
        sendingQueue.add(task)
        markAsManaged(task)
        return task
    }

    fun cancel(eventId: String, roomId: String) {
        (currentTask as? SendEventQueuedTask)
                ?.takeIf { it -> it.event.eventId == eventId && it.event.roomId == roomId }
                ?.cancel()
    }

    companion object {
        private const val RETRY_DELAY_TIME_MS = 500L
        private const val RETRY_PERIOD_TIME_MS = 10_000L
    }

    private var currentTask: QueuedTask? = null

    private var sendingQueue = LinkedBlockingQueue<QueuedTask>()

    private var networkAvailableLock = Object()
    private var canReachServer = true
    private lateinit var retryNoNetworkTask: TimerTask

    override fun run() {
        Timber.v("## SendThread started ts:${System.currentTimeMillis()}")

        initTimer()

        try {
            while (!isInterrupted) {
                Timber.v("## SendThread wait for task to process")
                val task = sendingQueue.take()
                        .also { currentTask = it }
                Timber.v("## SendThread Found task to process $task")

                if (task.isCancelled()) {
                    Timber.v("## SendThread send cancelled for $task")
                    // we do not execute this one
                    continue
                }
                // we check for network connectivity
                while (!canReachServer) {
                    Timber.v("## SendThread cannot reach server, wait ts:${System.currentTimeMillis()}")
                    // schedule to retry
                    waitForNetwork()
                    // if thread as been killed meanwhile
//                    if (state == State.KILLING) break
                }
                Timber.v("## Server is Reachable")
                // so network is available

                runBlocking {
                    retryLoop@ while (task.retryCount < 3) {
                        try {
                            // SendPerformanceProfiler.startStage(task.event.eventId!!, SendPerformanceProfiler.Stages.SEND_WORKER)
                            Timber.v("## SendThread retryLoop for $task retryCount ${task.retryCount}")
                            task.execute()
                            // sendEventTask.execute(SendEventTask.Params(task.event, task.encrypt, cryptoService))
                            // SendPerformanceProfiler.stopStage(task.event.eventId, SendPerformanceProfiler.Stages.SEND_WORKER)
                            break@retryLoop
                        } catch (exception: Throwable) {
                            when {
                                exception is IOException || exception is Failure.NetworkConnection                         -> {
                                    canReachServer = false
                                    task.retryCount++
                                    if (task.retryCount >= 3) task.onTaskFailed()
                                    while (!canReachServer) {
                                        Timber.v("## SendThread retryLoop cannot reach server, wait ts:${System.currentTimeMillis()}")
                                        // schedule to retry
                                        waitForNetwork()
                                    }
                                }
                                (exception is Failure.ServerError && exception.error.code == MatrixError.M_LIMIT_EXCEEDED) -> {
                                    task.retryCount++
                                    if (task.retryCount >= 3) task.onTaskFailed()
                                    Timber.v("## SendThread retryLoop retryable error for $task reason: ${exception.localizedMessage}")
                                    // wait a bit
                                    // Todo if its a quota exception can we get timout?
                                    sleep(3_000)
                                    continue@retryLoop
                                }
                                exception.isTokenError()                                                                   -> {
                                    Timber.v("## SendThread retryLoop retryable TOKEN error, interrupt")
                                    // we can exit the loop
                                    task.onTaskFailed()
                                    throw InterruptedException()
                                }
                                exception is CancellationException                                                         -> {
                                    Timber.v("## SendThread task has been cancelled")
                                    break@retryLoop
                                }
                                else                                                                                       -> {
                                    Timber.v("## SendThread retryLoop Un-Retryable error, try next task")
                                    // this task is in error, check next one?
                                    task.onTaskFailed()
                                    break@retryLoop
                                }
                            }
                        }
                    }
                }
                markAsFinished(task)
            }
        } catch (interruptionException: InterruptedException) {
            // will be thrown is thread is interrupted while seeping
            interrupt()
            Timber.v("## InterruptedException!! ${interruptionException.localizedMessage}")
        }
//        state = State.KILLED
        // is this needed?
        retryNoNetworkTask.cancel()
        Timber.w("## SendThread finished ${System.currentTimeMillis()}")
    }

    private fun initTimer() {
        retryNoNetworkTask = Timer(SyncState.NoNetwork.toString(), false).schedule(RETRY_DELAY_TIME_MS, RETRY_PERIOD_TIME_MS) {
            synchronized(networkAvailableLock) {
                canReachServer = checkHostAvailable().also {
                    Timber.v("## SendThread checkHostAvailable $it")
                }
                networkAvailableLock.notify()
            }
        }
    }

    private fun waitForNetwork() {
        retryNoNetworkTask.exhaustive // ensure retryNoNetworkTask is initialized
        synchronized(networkAvailableLock) { networkAvailableLock.wait() }
    }

    /**
     * Check if homeserver is reachable.
     */
    private fun checkHostAvailable(): Boolean {
        val host = sessionParams.homeServerConnectionConfig.homeServerUri.host ?: return false
        val port = sessionParams.homeServerConnectionConfig.homeServerUri.port.takeIf { it != -1 } ?: 80
        val timeout = 30_000
        try {
            Socket().use { socket ->
                val inetAddress: InetAddress = InetAddress.getByName(host)
                val inetSocketAddress = InetSocketAddress(inetAddress, port)
                socket.connect(inetSocketAddress, timeout)
                return true
            }
        } catch (e: IOException) {
            Timber.v("## EventSender isHostAvailable failure ${e.localizedMessage}")
            return false
        }
    }
}

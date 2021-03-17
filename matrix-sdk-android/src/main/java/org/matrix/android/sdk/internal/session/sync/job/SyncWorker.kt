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
package org.matrix.android.sdk.internal.session.sync.job

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.failure.isTokenError
import org.matrix.android.sdk.internal.di.WorkManagerProvider
import org.matrix.android.sdk.internal.network.NetworkConnectivityChecker
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.sync.SyncPresence
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val DEFAULT_LONG_POOL_TIMEOUT = 6L
private const val DEFAULT_DELAY_TIMEOUT = 30_000L

/**
 * Possible previous worker: None
 * Possible next worker    : None
 */
internal class SyncWorker(context: Context,
                          workerParameters: WorkerParameters
) : SessionSafeCoroutineWorker<SyncWorker.Params>(context, workerParameters, Params::class.java) {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            override val sessionId: String,
            val timeout: Long = DEFAULT_LONG_POOL_TIMEOUT,
            val delay: Long = DEFAULT_DELAY_TIMEOUT,
            val periodic: Boolean = false,
            override val lastFailureMessage: String? = null
    ) : SessionWorkerParams

    @Inject lateinit var syncTask: SyncTask
    @Inject lateinit var taskExecutor: TaskExecutor
    @Inject lateinit var networkConnectivityChecker: NetworkConnectivityChecker
    @Inject lateinit var workManagerProvider: WorkManagerProvider

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: Params): Result {
        Timber.i("Sync work starting")

        return runCatching {
            doSync(params.timeout)
        }.fold(
                {
                    Result.success().also {
                        if (params.periodic) {
                            // we want to schedule another one after delay
                            automaticallyRapidBackgroundSync(workManagerProvider, params.sessionId, params.timeout, params.delay)
                        }
                    }
                },
                { failure ->
                    if (failure.isTokenError()) {
                        Result.failure()
                    } else {
                        // If the worker was stopped (when going back in foreground), a JobCancellation exception is sent
                        // but in this case the result is ignored, as the work is considered stopped,
                        // so don't worry of the retry here for this case
                        Result.retry()
                    }
                }
        )
    }

    override fun buildErrorParams(params: Params, message: String): Params {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    private suspend fun doSync(timeout: Long) {
        val taskParams = SyncTask.Params(timeout * 1000, SyncPresence.Offline)
        syncTask.execute(taskParams)
    }

    companion object {
        private const val BG_SYNC_WORK_NAME = "BG_SYNCP"
        private const val BG_RAPID_SYNC_WORK_NAME = "BG_RAPID_SYNCP"
        private const val BG_PERIODIC_SYNC_WORK_NAME = "BG_PERIODIC_SYNCP"

        fun requireBackgroundSync(workManagerProvider: WorkManagerProvider, sessionId: String, serverTimeout: Long = 0) {
            val data = WorkerParamsFactory.toData(Params(sessionId, serverTimeout, 0L, false))
            val workRequest = workManagerProvider.matrixOneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(WorkManagerProvider.workConstraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build()
            // If we've already scheduled a sync that's not yet run, defer to the existing one
            workManagerProvider.workManager
                    .enqueueUniqueWork(BG_SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, workRequest)
        }

        fun automaticallyRapidBackgroundSync(workManagerProvider: WorkManagerProvider, sessionId: String, serverTimeout: Long = 0, delayInSeconds: Long = 30) {
            val data = WorkerParamsFactory.toData(Params(sessionId, serverTimeout, delayInSeconds, true))
            val workRequest = workManagerProvider.matrixOneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(WorkManagerProvider.workConstraints)
                    .setInputData(data)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .setInitialDelay(delayInSeconds, TimeUnit.SECONDS)
                    .build()
            // Avoid risking multiple chains of syncs by replacing the existing chain
            workManagerProvider.workManager
                    .enqueueUniqueWork(BG_RAPID_SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)
        }

        fun automaticallyPeriodicBackgroundSync(workManagerProvider: WorkManagerProvider, sessionId: String, serverTimeout: Long = 0, restartRapidSync: Boolean = true, delayInSeconds: Long = 30) {
            val data = WorkerParamsFactory.toData(Params(sessionId, serverTimeout, delayInSeconds, restartRapidSync))
            val workRequest = workManagerProvider.matrixPeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                    .setConstraints(WorkManagerProvider.workConstraints)
                    .setInputData(data)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build()

            workManagerProvider.workManager
                    .enqueueUniquePeriodicWork(BG_PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, workRequest)
        }

        fun stopAnyBackgroundSync(workManagerProvider: WorkManagerProvider) {
            workManagerProvider.workManager
                    .cancelUniqueWork(BG_SYNC_WORK_NAME)
            workManagerProvider.workManager
                    .cancelUniqueWork(BG_RAPID_SYNC_WORK_NAME)
            workManagerProvider.workManager
                    .cancelUniqueWork(BG_PERIODIC_SYNC_WORK_NAME)
        }
    }
}

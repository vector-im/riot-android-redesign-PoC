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

package org.matrix.android.sdk.internal.di

import android.content.Context
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class WorkManagerProvider @Inject constructor(
        context: Context,
        @SessionId private val sessionId: String
) {
    private val tag = MATRIX_SDK_TAG_PREFIX + sessionId

    val workManager = WorkManager.getInstance(context)

    /**
     * Create a OneTimeWorkRequestBuilder, with the Matrix SDK tag
     */
    inline fun <reified W : ListenableWorker> matrixOneTimeWorkRequestBuilder() =
            OneTimeWorkRequestBuilder<W>()
                    .addTag(tag)

    /**
     * Create a PeriodicWorkRequestBuilder, with the Matrix SDK tag
     */
    inline fun <reified W : ListenableWorker> matrixPeriodicWorkRequestBuilder(repeatInterval: Long,
                                                                               repeatIntervalTimeUnit: TimeUnit) =
            PeriodicWorkRequestBuilder<W>(repeatInterval, repeatIntervalTimeUnit)
                    .addTag(tag)

    /**
     * Create a PeriodicWorkRequestBuilder, with the Matrix SDK tag
     */
    inline fun <reified W : ListenableWorker> matrixPeriodicWorkRequestBuilder(repeatInterval: Long,
                                                                               repeatIntervalTimeUnit: TimeUnit,
                                                                               flexTimeInterval: Long,
                                                                               flexTimeIntervalUnit: TimeUnit) =
            PeriodicWorkRequestBuilder<W>(repeatInterval, repeatIntervalTimeUnit, flexTimeInterval, flexTimeIntervalUnit)
                    .addTag(tag)

    /**
     * Cancel all works instantiated by the Matrix SDK for the current session, and not those from the SDK client, or for other sessions
     */
    fun cancelAllWorks() {
        workManager.let {
            it.cancelAllWorkByTag(tag)
            it.pruneWork()
        }
    }

    companion object {
        private const val MATRIX_SDK_TAG_PREFIX = "MatrixSDK-"

        /**
         * Default constraints: connected network
         */
        val workConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        const val BACKOFF_DELAY = 10_000L
    }
}

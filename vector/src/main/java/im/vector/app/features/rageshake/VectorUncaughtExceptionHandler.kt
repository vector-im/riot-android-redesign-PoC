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

package im.vector.app.features.rageshake

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import im.vector.app.core.di.DefaultSharedPreferences
import im.vector.app.core.resources.VersionCodeProvider
import im.vector.app.core.utils.AnalyticsEngine
import im.vector.app.features.version.VersionProvider
import org.matrix.android.sdk.api.Matrix
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorUncaughtExceptionHandler @Inject constructor(private val bugReporter: BugReporter,
                                                         private val versionProvider: VersionProvider,
                                                         private val versionCodeProvider: VersionCodeProvider,
                                                         private val analyticsEngine: AnalyticsEngine) : Thread.UncaughtExceptionHandler {

    // key to save the crash status
    companion object {
        private const val PREFS_CRASH_KEY = "PREFS_CRASH_KEY"
    }

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private lateinit var context: Context

    /**
     * Activate this handler
     */
    fun activate(context: Context) {
        this.context = context
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * An uncaught exception has been triggered
     *
     * @param thread    the thread
     * @param throwable the throwable
     * @return the exception description
     */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Timber.v("Uncaught exception: $throwable")
        DefaultSharedPreferences.getInstance(context).edit {
            putBoolean(PREFS_CRASH_KEY, true)
        }
        val b = StringBuilder()
        val appName = "Element" // TODO Matrix.getApplicationName()

        b.append(appName + " Build : " + versionCodeProvider.getVersionCode() + "\n")
        b.append("$appName Version : ${versionProvider.getVersion(longFormat = true, useBuildNumber = true)}\n")
        b.append("SDK Version : ${Matrix.getSdkVersion()}\n")
        b.append("Phone : " + Build.MODEL.trim() + " (" + Build.VERSION.INCREMENTAL + " " + Build.VERSION.RELEASE + " " + Build.VERSION.CODENAME + ")\n")

        b.append("Memory statuses \n")

        var freeSize = 0L
        var totalSize = 0L
        var usedSize = -1L
        try {
            val info = Runtime.getRuntime()
            freeSize = info.freeMemory()
            totalSize = info.totalMemory()
            usedSize = totalSize - freeSize
        } catch (e: Exception) {
            e.printStackTrace()
        }

        b.append("usedSize   " + usedSize / 1048576L + " MB\n")
        b.append("freeSize   " + freeSize / 1048576L + " MB\n")
        b.append("totalSize   " + totalSize / 1048576L + " MB\n")

        b.append("Thread: ")
        b.append(thread.name)

        b.append(", Exception: ")

        val sw = StringWriter()
        val pw = PrintWriter(sw, true)
        throwable.printStackTrace(pw)
        b.append(sw.buffer.toString())

        val bugDescription = b.toString()
        Timber.e("FATAL EXCEPTION $bugDescription")

        bugReporter.saveCrashReport(context, bugDescription)

        // Show the classical system popup
        previousHandler?.uncaughtException(thread, throwable)

        analyticsEngine.report(AnalyticsEngine.AnalyticEvent.UnHandledCrash(throwable))
    }

    /**
     * Tells if the application crashed
     *
     * @return true if the application crashed
     */
    fun didAppCrash(context: Context): Boolean {
        return DefaultSharedPreferences.getInstance(context)
                .getBoolean(PREFS_CRASH_KEY, false)
    }

    /**
     * Clear the crash status
     */
    fun clearAppCrashStatus(context: Context) {
        DefaultSharedPreferences.getInstance(context).edit {
            remove(PREFS_CRASH_KEY)
        }
    }
}

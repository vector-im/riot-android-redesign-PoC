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
import android.util.Log
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorFileLogger @Inject constructor(
        context: Context,
        private val vectorPreferences: VectorPreferences
) : Timber.Tree() {

    companion object {
        private const val SIZE_20MB = 20 * 1024 * 1024
        private const val SIZE_50MB = 50 * 1024 * 1024
    }

    private val maxLogSizeByte = if (vectorPreferences.labAllowedExtendedLogging()) SIZE_50MB else SIZE_20MB
    private val logRotationCount = if (vectorPreferences.labAllowedExtendedLogging()) 15 else 7

    private val logger = Logger.getLogger(context.packageName).apply {
        tryOrNull {
            useParentHandlers = false
            level = Level.ALL
        }
    }

    private val fileHandler: FileHandler?
    private val cacheDirectory = File(context.cacheDir, "logs")
    private var fileNamePrefix = "logs"

    private val prioPrefixes = mapOf(
            Log.VERBOSE to "V/ ",
            Log.DEBUG to "D/ ",
            Log.INFO to "I/ ",
            Log.WARN to "W/ ",
            Log.ERROR to "E/ ",
            Log.ASSERT to "WTF/ "
    )

    init {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }

        for (i in 0..15) {
            val file = File(cacheDirectory, "elementLogs.$i.txt")
            tryOrNull { file.delete() }
        }

        fileHandler = tryOrNull("Failed to initialize FileLogger") {
            FileHandler(
                    cacheDirectory.absolutePath + "/" + fileNamePrefix + ".%g.txt",
                    maxLogSizeByte,
                    logRotationCount
            )
                    .also { it.formatter = LogFormatter() }
                    .also { logger.addHandler(it) }
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        fileHandler ?: return
        GlobalScope.launch(Dispatchers.IO) {
            if (skipLog(priority)) return@launch
            if (t != null) {
                logToFile(t)
            }
            logToFile(prioPrefixes[priority] ?: "$priority ", tag ?: "Tag", message)
        }
    }

    private fun skipLog(priority: Int): Boolean {
        return if (vectorPreferences.labAllowedExtendedLogging()) {
            false
        } else {
            // Exclude verbose logs
            priority < Log.DEBUG
        }
    }

    /**
     * Adds our own log files to the provided list of files.
     *
     * @return The list of files with logs.
     */
    fun getLogFiles(): List<File> {
        return tryOrNull("## getLogFiles() failed") {
            fileHandler
                    ?.flush()
                    ?.let { 0 until logRotationCount }
                    ?.mapNotNull { index ->
                        File(cacheDirectory, "$fileNamePrefix.$index.txt")
                                .takeIf { it.exists() }
                    }
        }
                .orEmpty()
    }

    /**
     * Log an Throwable
     *
     * @param throwable the throwable to log
     */
    private fun logToFile(throwable: Throwable?) {
        throwable ?: return

        val errors = StringWriter()
        throwable.printStackTrace(PrintWriter(errors))

        logger.info(errors.toString())
    }

    private fun logToFile(level: String, tag: String, content: String) {
        val b = StringBuilder()
        b.append(Thread.currentThread().id)
        b.append(" ")
        b.append(level)
        b.append("/")
        b.append(tag)
        b.append(": ")
        b.append(content)
        logger.info(b.toString())
    }
}

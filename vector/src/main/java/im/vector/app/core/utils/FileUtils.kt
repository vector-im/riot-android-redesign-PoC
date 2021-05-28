/*
 * Copyright 2018 New Vector Ltd
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

package im.vector.app.core.utils

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.Locale

// Implementation should return true in case of success
typealias ActionOnFile = (file: File) -> Boolean

/* ==========================================================================================
 * Delete
 * ========================================================================================== */

fun deleteAllFiles(root: File) {
    Timber.v("Delete ${root.absolutePath}")
    recursiveActionOnFile(root, ::deleteAction)
}

private fun deleteAction(file: File): Boolean {
    if (file.exists()) {
        Timber.v("deleteFile: $file")
        return file.delete()
    }

    return true
}

/* ==========================================================================================
 * Log
 * ========================================================================================== */

fun lsFiles(context: Context) {
    Timber.v("Content of cache dir:")
    recursiveActionOnFile(context.cacheDir, ::logAction)

    Timber.v("Content of files dir:")
    recursiveActionOnFile(context.filesDir, ::logAction)
}

private fun logAction(file: File): Boolean {
    if (file.isDirectory) {
        Timber.v(file.toString())
    } else {
        Timber.v("$file ${file.length()} bytes")
    }
    return true
}

/* ==========================================================================================
 * Private
 * ========================================================================================== */

/**
 * Return true in case of success
 */
private fun recursiveActionOnFile(file: File, action: ActionOnFile): Boolean {
    if (file.isDirectory) {
        file.list()?.forEach {
            val result = recursiveActionOnFile(File(file, it), action)

            if (!result) {
                // Break the loop
                return false
            }
        }
    }

    return action.invoke(file)
}

/**
 * Get the file extension of a fileUri or a filename
 *
 * @param fileUri the fileUri (can be a simple filename)
 * @return the file extension, in lower case, or null is extension is not available or empty
 */
fun getFileExtension(fileUri: String): String? {
    var reducedStr = fileUri

    if (reducedStr.isNotEmpty()) {
        // Remove fragment
        reducedStr = reducedStr.substringBeforeLast('#')

        // Remove query
        reducedStr = reducedStr.substringBeforeLast('?')

        // Remove path
        val filename = reducedStr.substringAfterLast('/')

        // Contrary to method MimeTypeMap.getFileExtensionFromUrl, we do not check the pattern
        // See https://stackoverflow.com/questions/14320527/android-should-i-use-mimetypemap-getfileextensionfromurl-bugs
        if (filename.isNotEmpty()) {
            val dotPos = filename.lastIndexOf('.')
            if (0 <= dotPos) {
                val ext = filename.substring(dotPos + 1)

                if (ext.isNotBlank()) {
                    return ext.lowercase(Locale.ROOT)
                }
            }
        }
    }

    return null
}

/* ==========================================================================================
 * Size
 * ========================================================================================== */

fun getSizeOfFiles(root: File): Int {
    return root.walkTopDown()
            .onEnter {
                Timber.v("Get size of ${it.absolutePath}")
                true
            }
            .sumOf { it.length().toInt() }
}

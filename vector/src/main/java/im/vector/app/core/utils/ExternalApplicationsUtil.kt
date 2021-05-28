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

package im.vector.app.core.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Browser
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import im.vector.app.BuildConfig
import im.vector.app.R
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.themes.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeAudio
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeImage
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeVideo
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Open a url in the internet browser of the system
 */
fun openUrlInExternalBrowser(context: Context, url: String?) {
    url?.let {
        openUrlInExternalBrowser(context, Uri.parse(it))
    }
}

/**
 * Open a uri in the internet browser of the system
 */
fun openUrlInExternalBrowser(context: Context, uri: Uri?) {
    uri?.let {
        val browserIntent = Intent(Intent.ACTION_VIEW, it).apply {
            putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
            putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
        }

        try {
            context.startActivity(browserIntent)
        } catch (activityNotFoundException: ActivityNotFoundException) {
            context.toast(R.string.error_no_external_application_found)
        }
    }
}

/**
 * Open url in custom tab or, if not available, in the default browser
 * If several compatible browsers are installed, the user will be proposed to choose one.
 * Ref: https://developer.chrome.com/multidevice/android/customtabs
 */
fun openUrlInChromeCustomTab(context: Context,
                             session: CustomTabsSession?,
                             url: String) {
    try {
        CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams.Builder()
                                .setToolbarColor(ThemeUtils.getColor(context, R.attr.riotx_background))
                                .setNavigationBarColor(ThemeUtils.getColor(context, R.attr.riotx_background))
                                .build()
                )
                .setColorScheme(
                        when {
                            ThemeUtils.isSystemTheme(context) -> CustomTabsIntent.COLOR_SCHEME_SYSTEM
                            ThemeUtils.isLightTheme(context)  -> CustomTabsIntent.COLOR_SCHEME_LIGHT
                            else                              -> CustomTabsIntent.COLOR_SCHEME_DARK
                        }
                )
                // Note: setting close button icon does not work
                .setCloseButtonIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_back_24dp))
                .setStartAnimations(context, R.anim.enter_fade_in, R.anim.exit_fade_out)
                .setExitAnimations(context, R.anim.enter_fade_in, R.anim.exit_fade_out)
                .apply { session?.let { setSession(it) } }
                .build()
                .launchUrl(context, Uri.parse(url))
    } catch (activityNotFoundException: ActivityNotFoundException) {
        context.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Open sound recorder external application
 */
fun openSoundRecorder(activity: Activity, requestCode: Int) {
    val recordSoundIntent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)

    // Create chooser
    val chooserIntent = Intent.createChooser(recordSoundIntent, activity.getString(R.string.go_on_with))

    try {
        activity.startActivityForResult(chooserIntent, requestCode)
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Open file selection activity
 */
fun openFileSelection(activity: Activity,
                      activityResultLauncher: ActivityResultLauncher<Intent>?,
                      allowMultipleSelection: Boolean,
                      requestCode: Int) {
    val fileIntent = Intent(Intent.ACTION_GET_CONTENT)
    fileIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultipleSelection)

    fileIntent.addCategory(Intent.CATEGORY_OPENABLE)
    fileIntent.type = MimeTypes.Any

    try {
        activityResultLauncher
                ?.launch(fileIntent)
                ?: run {
                    activity.startActivityForResult(fileIntent, requestCode)
                }
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Open external video recorder
 */
fun openVideoRecorder(activity: Activity, requestCode: Int) {
    val captureIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)

    // lowest quality
    captureIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)

    try {
        activity.startActivityForResult(captureIntent, requestCode)
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Open external camera
 * @return the latest taken picture camera uri
 */
fun openCamera(activity: Activity, titlePrefix: String, requestCode: Int): String? {
    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

    // the following is a fix for buggy 2.x devices
    val date = Date()
    val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    val values = ContentValues()
    values.put(MediaStore.Images.Media.TITLE, titlePrefix + formatter.format(date))
    // The Galaxy S not only requires the name of the file to output the image to, but will also not
    // set the mime type of the picture it just took (!!!). We assume that the Galaxy S takes image/jpegs
    // so the attachment uploader doesn't freak out about there being no mimetype in the content database.
    values.put(MediaStore.Images.Media.MIME_TYPE, MimeTypes.Jpeg)
    var dummyUri: Uri? = null
    try {
        dummyUri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        if (null == dummyUri) {
            Timber.e("Cannot use the external storage media to save image")
        }
    } catch (uoe: UnsupportedOperationException) {
        Timber.e(uoe, "Unable to insert camera URI into MediaStore.Images.Media.EXTERNAL_CONTENT_URI.")
        Timber.e("no SD card? Attempting to insert into device storage.")
    } catch (e: Exception) {
        Timber.e(e, "Unable to insert camera URI into MediaStore.Images.Media.EXTERNAL_CONTENT_URI.")
    }

    if (null == dummyUri) {
        try {
            dummyUri = activity.contentResolver.insert(MediaStore.Images.Media.INTERNAL_CONTENT_URI, values)
            if (null == dummyUri) {
                Timber.e("Cannot use the internal storage to save media to save image")
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to insert camera URI into internal storage. Giving up.")
        }
    }

    if (dummyUri != null) {
        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, dummyUri)
        Timber.v("trying to take a photo on $dummyUri")
    } else {
        Timber.v("trying to take a photo with no predefined uri")
    }

    // Store the dummy URI which will be set to a placeholder location. When all is lost on Samsung devices,
    // this will point to the data we're looking for.
    // Because Activities tend to use a single MediaProvider for all their intents, this field will only be the
    // *latest* TAKE_PICTURE Uri. This is deemed acceptable as the normal flow is to create the intent then immediately
    // fire it, meaning onActivityResult/getUri will be the next thing called, not another createIntentFor.
    val result = if (dummyUri == null) null else dummyUri.toString()

    try {
        activity.startActivityForResult(captureIntent, requestCode)

        return result
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }

    return null
}

/**
 * Send an email to address with optional subject and message
 */
fun sendMailTo(address: String, subject: String? = null, message: String? = null, activity: Activity) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts(
            "mailto", address, null))
    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    intent.putExtra(Intent.EXTRA_TEXT, message)

    try {
        activity.startActivity(intent)
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Open an arbitrary uri
 */
fun openUri(activity: Activity, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    try {
        activity.startActivity(intent)
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Send media to a third party application.
 *
 * @param activity       the activity
 * @param savedMediaPath the media path
 * @param mimeType       the media mime type.
 */
fun openMedia(activity: Activity, savedMediaPath: String, mimeType: String) {
    val file = File(savedMediaPath)
    val uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".fileProvider", file)

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        activity.startActivity(intent)
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

fun shareMedia(context: Context, file: File, mediaMimeType: String?) {
    var mediaUri: Uri? = null
    try {
        mediaUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileProvider", file)
    } catch (e: Exception) {
        Timber.e(e, "onMediaAction Selected File cannot be shared")
    }

    if (null != mediaUri) {
        val sendIntent = Intent()
        sendIntent.action = Intent.ACTION_SEND
        // Grant temporary read permission to the content URI
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sendIntent.type = mediaMimeType
        sendIntent.putExtra(Intent.EXTRA_STREAM, mediaUri)

        sendShareIntent(context, sendIntent)
    }
}

fun shareText(context: Context, text: String) {
    val sendIntent = Intent()
    sendIntent.action = Intent.ACTION_SEND
    sendIntent.type = "text/plain"
    sendIntent.putExtra(Intent.EXTRA_TEXT, text)

    sendShareIntent(context, sendIntent)
}

private fun sendShareIntent(context: Context, intent: Intent) {
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    } catch (activityNotFoundException: ActivityNotFoundException) {
        context.toast(R.string.error_no_external_application_found)
    }
}

private fun appendTimeToFilename(name: String): String {
    val dateExtension = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
    if (!name.contains(".")) return name + "_" + dateExtension

    val filename = name.substringBeforeLast(".")
    val fileExtension = name.substringAfterLast(".")

    return """${filename}_$dateExtension.$fileExtension"""
}

suspend fun saveMedia(context: Context, file: File, title: String, mediaMimeType: String?, notificationUtils: NotificationUtils) {
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val filename = appendTimeToFilename(title)

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.TITLE, filename)
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mediaMimeType)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            val externalContentUri = when {
                mediaMimeType?.isMimeTypeImage() == true -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                mediaMimeType?.isMimeTypeVideo() == true -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                mediaMimeType?.isMimeTypeAudio() == true -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else                                     -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }

            val uri = context.contentResolver.insert(externalContentUri, values)
            if (uri == null) {
                Toast.makeText(context, R.string.error_saving_media_file, Toast.LENGTH_LONG).show()
                throw IllegalStateException(context.getString(R.string.error_saving_media_file))
            } else {
                val source = file.inputStream().source().buffer()
                context.contentResolver.openOutputStream(uri)?.sink()?.buffer()?.let { sink ->
                    source.use { input ->
                        sink.use { output ->
                            output.writeAll(input)
                        }
                    }
                }
                notificationUtils.buildDownloadFileNotification(
                        uri,
                        filename,
                        mediaMimeType ?: MimeTypes.OctetStream
                ).let { notification ->
                    notificationUtils.showNotificationMessage("DL", uri.hashCode(), notification)
                }
            }
        } else {
            saveMediaLegacy(context, mediaMimeType, title, file)
        }
    }
}

@Suppress("DEPRECATION")
private fun saveMediaLegacy(context: Context,
                            mediaMimeType: String?,
                            title: String,
                            file: File) {
    val state = Environment.getExternalStorageState()
    if (Environment.MEDIA_MOUNTED != state) {
        context.toast(context.getString(R.string.error_saving_media_file))
        throw IllegalStateException(context.getString(R.string.error_saving_media_file))
    }

    val dest = when {
        mediaMimeType?.isMimeTypeImage() == true -> Environment.DIRECTORY_PICTURES
        mediaMimeType?.isMimeTypeVideo() == true -> Environment.DIRECTORY_MOVIES
        mediaMimeType?.isMimeTypeAudio() == true -> Environment.DIRECTORY_MUSIC
        else                                     -> Environment.DIRECTORY_DOWNLOADS
    }
    val downloadDir = Environment.getExternalStoragePublicDirectory(dest)
    try {
        val outputFilename = if (title.substringAfterLast('.', "").isEmpty()) {
            val extension = mediaMimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            "$title.$extension"
        } else {
            title
        }
        val savedFile = saveFileIntoLegacy(file, downloadDir, outputFilename)
        if (savedFile != null) {
            val downloadManager = context.getSystemService<DownloadManager>()
            downloadManager?.addCompletedDownload(
                    savedFile.name,
                    title,
                    true,
                    mediaMimeType ?: MimeTypes.OctetStream,
                    savedFile.absolutePath,
                    savedFile.length(),
                    true)
            addToGallery(savedFile, mediaMimeType, context)
        }
    } catch (error: Throwable) {
        context.toast(context.getString(R.string.error_saving_media_file))
        throw error
    }
}

private fun addToGallery(savedFile: File, mediaMimeType: String?, context: Context) {
    // MediaScannerConnection provides a way for applications to pass a newly created or downloaded media file to the media scanner service.
    var mediaConnection: MediaScannerConnection? = null
    val mediaScannerConnectionClient: MediaScannerConnection.MediaScannerConnectionClient = object : MediaScannerConnection.MediaScannerConnectionClient {
        override fun onMediaScannerConnected() {
            mediaConnection?.scanFile(savedFile.path, mediaMimeType)
        }

        override fun onScanCompleted(path: String, uri: Uri?) {
            if (path == savedFile.path) mediaConnection?.disconnect()
        }
    }
    mediaConnection = MediaScannerConnection(context, mediaScannerConnectionClient).apply { connect() }
}

/**
 * Open the play store to the provided application Id, default to this app
 */
fun openPlayStore(activity: Activity, appId: String = BuildConfig.APPLICATION_ID) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appId")))
    } catch (activityNotFoundException: ActivityNotFoundException) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appId")))
        } catch (activityNotFoundException: ActivityNotFoundException) {
            activity.toast(R.string.error_no_external_application_found)
        }
    }
}

fun openAppSettingsPage(activity: Activity) {
    try {
        activity.startActivity(
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.fromParts("package", activity.packageName, null)
                })
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

/**
 * Ask the user to select a location and a file name to write in
 */
fun selectTxtFileToWrite(
        activity: Activity,
        activityResultLauncher: ActivityResultLauncher<Intent>,
        defaultFileName: String,
        chooserHint: String
) {
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TITLE, defaultFileName)

    try {
        val chooserIntent = Intent.createChooser(intent, chooserHint)
        activityResultLauncher.launch(chooserIntent)
    } catch (activityNotFoundException: ActivityNotFoundException) {
        activity.toast(R.string.error_no_external_application_found)
    }
}

// ==============================================================================================================
// Media utils
// ==============================================================================================================
/**
 * Copy a file into a dstPath directory.
 * The output filename can be provided.
 * The output file is not overridden if it is already exist.
 *
 * ~~ This is copied from the old matrix sdk ~~
 *
 * @param sourceFile     the file source path
 * @param dstDirPath     the dst path
 * @param outputFilename optional the output filename
 * @return               the created file
 */
@Suppress("DEPRECATION")
fun saveFileIntoLegacy(sourceFile: File, dstDirPath: File, outputFilename: String?): File? {
    // defines another name for the external media
    var dstFileName: String

    // build a filename is not provided
    if (null == outputFilename) {
        // extract the file extension from the uri
        val dotPos = sourceFile.name.lastIndexOf(".")
        var fileExt = ""
        if (dotPos > 0) {
            fileExt = sourceFile.name.substring(dotPos)
        }
        dstFileName = "vector_" + System.currentTimeMillis() + fileExt
    } else {
        dstFileName = outputFilename
    }

    // remove dangerous characters from the filename
    dstFileName = dstFileName.replace(Regex("""[/\\]"""), "_")

    var dstFile = File(dstDirPath, dstFileName)

    // if the file already exists, append a marker
    if (dstFile.exists()) {
        var baseFileName = dstFileName
        var fileExt = ""
        val lastDotPos = dstFileName.lastIndexOf(".")
        if (lastDotPos > 0) {
            baseFileName = dstFileName.substring(0, lastDotPos)
            fileExt = dstFileName.substring(lastDotPos)
        }
        var counter = 1
        while (dstFile.exists()) {
            dstFile = File(dstDirPath, "$baseFileName($counter)$fileExt")
            counter++
        }
    }

    // Copy source file to destination
    var inputStream: FileInputStream? = null
    var outputStream: FileOutputStream? = null
    try {
        dstFile.createNewFile()
        inputStream = sourceFile.inputStream()
        outputStream = dstFile.outputStream()
        val buffer = ByteArray(1024 * 10)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            outputStream.write(buffer, 0, len)
        }
        return dstFile
    } catch (failure: Throwable) {
        return null
    } finally {
        // Close resources
        tryOrNull { inputStream?.close() }
        tryOrNull { outputStream?.close() }
    }
}

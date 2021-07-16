/*
 * Copyright 2019 New Vector Ltd
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

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity

// Permissions sets
val PERMISSIONS_FOR_AUDIO_IP_CALL = listOf(Manifest.permission.RECORD_AUDIO)
val PERMISSIONS_FOR_VIDEO_IP_CALL = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
val PERMISSIONS_FOR_VOICE_MESSAGE = listOf(Manifest.permission.RECORD_AUDIO)
val PERMISSIONS_FOR_TAKING_PHOTO = listOf(Manifest.permission.CAMERA)
val PERMISSIONS_FOR_MEMBERS_SEARCH = listOf(Manifest.permission.READ_CONTACTS)
val PERMISSIONS_FOR_ROOM_AVATAR = listOf(Manifest.permission.CAMERA)
val PERMISSIONS_FOR_WRITING_FILES = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
val PERMISSIONS_FOR_PICKING_CONTACT = listOf(Manifest.permission.READ_CONTACTS)

val PERMISSIONS_EMPTY = emptyList<String>()

// This is not ideal to store the value like that, but it works
private var permissionDialogDisplayed = false

/**
 * First boolean is true if all permissions have been granted
 * Second boolean is true if the permission is denied forever AND the permission request has not been displayed.
 * So when the user does not grant the permission and check the box do not ask again, this boolean will be false.
 * Only useful if the first boolean is false
 */
fun ComponentActivity.registerForPermissionsResult(lambda: (allGranted: Boolean, deniedPermanently: Boolean) -> Unit)
        : ActivityResultLauncher<Array<String>> {
    return registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onPermissionResult(result, lambda)
    }
}

fun Fragment.registerForPermissionsResult(lambda: (allGranted: Boolean, deniedPermanently: Boolean) -> Unit): ActivityResultLauncher<Array<String>> {
    return registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onPermissionResult(result, lambda)
    }
}

private fun onPermissionResult(result: Map<String, Boolean>, lambda: (allGranted: Boolean, deniedPermanently: Boolean) -> Unit) {
    if (result.keys.all { result[it] == true }) {
        lambda(true, /* not used */ false)
    } else {
        if (permissionDialogDisplayed) {
            // A permission dialog has been displayed, so even if the user has checked the do not ask again button, we do
            // not tell the user to open the app settings
            lambda(false, false)
        } else {
            // No dialog has been displayed, so tell the user to go to the system setting
            lambda(false, true)
        }
    }
    // Reset
    permissionDialogDisplayed = false
}

/**
 * Check if the permissions provided in the list are granted.
 * This is an asynchronous method if permissions are requested, the final response
 * is provided in onRequestPermissionsResult(). In this case checkPermissions()
 * returns false.
 * <br></br>If checkPermissions() returns true, the permissions were already granted.
 * The permissions to be granted are given as bit map in permissionsToBeGrantedBitMap (ex: [.PERMISSIONS_FOR_TAKING_PHOTO]).
 * <br></br>permissionsToBeGrantedBitMap is passed as the request code in onRequestPermissionsResult().
 *
 *
 * If a permission was already denied by the user, a popup is displayed to
 * explain why vector needs the corresponding permission.
 *
 * @param permissionsToBeGranted the permissions to be granted
 * @param activity               the calling Activity that is requesting the permissions (or fragment parent)
 * @param activityResultLauncher from the calling fragment/Activity that is requesting the permissions
 * @param rationaleMessage       message to be displayed BEFORE requesting for the permission
 * @return true if the permissions are granted (synchronous flow), false otherwise (asynchronous flow)
 */
fun checkPermissions(permissionsToBeGranted: List<String>,
                     activity: Activity,
                     activityResultLauncher: ActivityResultLauncher<Array<String>>,
                     @StringRes rationaleMessage: Int = 0): Boolean {
    // retrieve the permissions to be granted according to the permission list
    val missingPermissions = permissionsToBeGranted.filter { permission ->
        ContextCompat.checkSelfPermission(activity.applicationContext, permission) == PackageManager.PERMISSION_DENIED
    }

    return if (missingPermissions.isNotEmpty()) {
        permissionDialogDisplayed = !permissionsDeniedPermanently(missingPermissions, activity)

        if (rationaleMessage != 0 && permissionDialogDisplayed) {
            // display the dialog with the info text. Do not do it if no system dialog will
            // be displayed
            MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.permissions_rationale_popup_title)
                    .setMessage(rationaleMessage)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        activityResultLauncher.launch(missingPermissions.toTypedArray())
                    }
                    .show()
        } else {
            // some permissions are not granted, ask permissions
            activityResultLauncher.launch(missingPermissions.toTypedArray())
        }
        false
    } else {
        // permissions were granted, start now.
        true
    }
}

/**
 * To be call after the permission request
 *
 * @param permissionsToBeGranted the permissions to be granted
 * @param activity               the calling Activity that is requesting the permissions (or fragment parent)
 *
 * @return true if one of the permission has been denied and the user check the do not ask again checkbox
 */
private fun permissionsDeniedPermanently(permissionsToBeGranted: List<String>,
                                         activity: Activity): Boolean {
    return permissionsToBeGranted
            .filter { permission ->
                ContextCompat.checkSelfPermission(activity.applicationContext, permission) == PackageManager.PERMISSION_DENIED
            }
            .any { permission ->
                // If shouldShowRequestPermissionRationale() returns true, it means that the user as denied the permission, but not permanently.
                // If it return false, it mean that the user as denied permanently the permission
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission).not()
            }
}

fun VectorBaseActivity<*>.onPermissionDeniedSnackbar(@StringRes rationaleMessage: Int) {
    showSnackbar(getString(rationaleMessage), R.string.settings) {
        openAppSettingsPage(this)
    }
}

fun FragmentActivity.onPermissionDeniedDialog(@StringRes rationaleMessage: Int) {
    MaterialAlertDialogBuilder(this)
            .setTitle(R.string.missing_permissions_title)
            .setMessage(rationaleMessage)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openAppSettingsPage(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
}

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

package im.vector.app.core.extensions

import android.app.Activity
import android.content.Intent
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction

fun ComponentActivity.registerStartForActivityResult(onResult: (ActivityResult) -> Unit): ActivityResultLauncher<Intent> {
    return registerForActivityResult(ActivityResultContracts.StartActivityForResult(), onResult)
}

fun AppCompatActivity.addFragment(
        frameId: Int,
        fragment: Fragment,
        allowStateLoss: Boolean = false
) {
    supportFragmentManager.commitTransaction(allowStateLoss) { add(frameId, fragment) }
}

fun <T : Fragment> AppCompatActivity.addFragment(
        frameId: Int,
        fragmentClass: Class<T>,
        params: Parcelable? = null,
        tag: String? = null,
        allowStateLoss: Boolean = false
) {
    supportFragmentManager.commitTransaction(allowStateLoss) {
        add(frameId, fragmentClass, params.toMvRxBundle(), tag)
    }
}

fun AppCompatActivity.replaceFragment(
        frameId: Int,
        fragment: Fragment,
        tag: String? = null,
        allowStateLoss: Boolean = false
) {
    supportFragmentManager.commitTransaction(allowStateLoss) { replace(frameId, fragment, tag) }
}

fun <T : Fragment> AppCompatActivity.replaceFragment(
        frameId: Int,
        fragmentClass: Class<T>,
        params: Parcelable? = null,
        tag: String? = null,
        allowStateLoss: Boolean = false
) {
    supportFragmentManager.commitTransaction(allowStateLoss) {
        replace(frameId, fragmentClass, params.toMvRxBundle(), tag)
    }
}

fun AppCompatActivity.addFragmentToBackstack(
        frameId: Int,
        fragment: Fragment,
        tag: String? = null,
        allowStateLoss: Boolean = false
) {
    supportFragmentManager.commitTransaction(allowStateLoss) { replace(frameId, fragment).addToBackStack(tag) }
}

fun <T : Fragment> AppCompatActivity.addFragmentToBackstack(
        frameId: Int,
        fragmentClass: Class<T>,
        params: Parcelable? = null,
        tag: String? = null,
        allowStateLoss: Boolean = false,
        option: ((FragmentTransaction) -> Unit)? = null) {
    supportFragmentManager.commitTransaction(allowStateLoss) {
        option?.invoke(this)
        replace(frameId, fragmentClass, params.toMvRxBundle(), tag).addToBackStack(tag)
    }
}

fun AppCompatActivity.popBackstack() {
    supportFragmentManager.popBackStack()
}

fun AppCompatActivity.resetBackstack() {
    repeat(supportFragmentManager.backStackEntryCount) {
        supportFragmentManager.popBackStack()
    }
}

fun AppCompatActivity.hideKeyboard() {
    currentFocus?.hideKeyboard()
}

fun Activity.restart() {
    startActivity(intent)
    finish()
}

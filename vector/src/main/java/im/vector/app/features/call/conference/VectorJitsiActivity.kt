/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.app.features.call.conference

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.viewModel
import com.facebook.react.modules.core.PermissionListener
import im.vector.app.R
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityJitsiBinding
import kotlinx.parcelize.Parcelize
import org.jitsi.meet.sdk.BroadcastEvent
import org.jitsi.meet.sdk.JitsiMeetActivityDelegate
import org.jitsi.meet.sdk.JitsiMeetActivityInterface
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions
import org.jitsi.meet.sdk.JitsiMeetView
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.net.URL
import javax.inject.Inject

class VectorJitsiActivity : VectorBaseActivity<ActivityJitsiBinding>(), JitsiMeetActivityInterface {

    @Parcelize
    data class Args(
            val roomId: String,
            val widgetId: String,
            val enableVideo: Boolean
    ) : Parcelable

    override fun getBinding() = ActivityJitsiBinding.inflate(layoutInflater)

    @Inject lateinit var viewModelFactory: JitsiCallViewModel.Factory

    private var jitsiMeetView: JitsiMeetView? = null

    private val jitsiViewModel: JitsiCallViewModel by viewModel()

    override fun injectWith(injector: ScreenComponent) {
        injector.inject(this)
    }

    // See https://jitsi.github.io/handbook/docs/dev-guide/dev-guide-android-sdk#listening-for-broadcasted-events
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { onBroadcastReceived(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        jitsiViewModel.subscribe(this) {
            renderState(it)
        }

        jitsiViewModel.observeViewEvents {
            when (it) {
                is JitsiCallViewEvents.JoinConference             -> configureJitsiView(it)
                is JitsiCallViewEvents.ConfirmSwitchingConference -> handleConfirmSwitching(it)
                JitsiCallViewEvents.FailJoiningConference         -> handleFailJoining()
                JitsiCallViewEvents.Finish                        -> finish()
                JitsiCallViewEvents.LeaveConference               -> handleLeaveConference()
            }.exhaustive
        }

        registerForBroadcastMessages()
    }

    private fun handleLeaveConference() {
        jitsiMeetView?.leave()
    }

    private fun handleConfirmSwitching(action: JitsiCallViewEvents.ConfirmSwitchingConference) {
        AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_warning)
                .setMessage(R.string.jitsi_leave_conf_to_join_another_one_content)
                .setPositiveButton(R.string.action_switch) { _, _ ->
                    jitsiViewModel.handle(JitsiCallViewActions.SwitchTo(action.args, false))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,
                                               newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Timber.w("onPictureInPictureModeChanged($isInPictureInPictureMode)")
    }

    override fun initUiAndData() {
        super.initUiAndData()
        jitsiMeetView = JitsiMeetView(this)
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        views.jitsiLayout.addView(jitsiMeetView, params)
    }

    private fun renderState(viewState: JitsiCallViewState) {
        when (viewState.widget) {
            is Fail    -> finish()
            is Success -> {
                views.jitsiProgressLayout.isVisible = false
                jitsiMeetView?.isVisible = true
            }
            else       -> {
                jitsiMeetView?.isVisible = false
                views.jitsiProgressLayout.isVisible = true
            }
        }
    }

    private fun handleFailJoining() {
        Toast.makeText(this, getString(R.string.error_jitsi_join_conf), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun configureJitsiView(joinConference: JitsiCallViewEvents.JoinConference) {
        val jitsiMeetConferenceOptions = JitsiMeetConferenceOptions.Builder()
                .setVideoMuted(!joinConference.enableVideo)
                .setUserInfo(joinConference.userInfo)
                .setToken(joinConference.token)
                .apply {
                    tryOrNull { URL(joinConference.jitsiUrl) }?.let {
                        setServerURL(it)
                    }
                }
                // https://github.com/jitsi/jitsi-meet/blob/master/react/features/base/flags/constants.js
                .setFeatureFlag("chat.enabled", false)
                .setFeatureFlag("invite.enabled", false)
                .setFeatureFlag("add-people.enabled", false)
                .setFeatureFlag("video-share.enabled", false)
                .setFeatureFlag("call-integration.enabled", false)
                .setRoom(joinConference.confId)
                .setSubject(joinConference.subject)
                .build()
        jitsiMeetView?.join(jitsiMeetConferenceOptions)
    }

    override fun onStop() {
        JitsiMeetActivityDelegate.onHostPause(this)
        super.onStop()
    }

    override fun onResume() {
        JitsiMeetActivityDelegate.onHostResume(this)
        super.onResume()
    }

    override fun onBackPressed() {
        JitsiMeetActivityDelegate.onBackPressed()
        super.onBackPressed()
    }

    override fun onDestroy() {
        JitsiMeetActivityDelegate.onHostDestroy(this)
        unregisterForBroadcastMessages()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            jitsiMeetView?.enterPictureInPicture()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        JitsiMeetActivityDelegate.onNewIntent(intent)

        // Is it a switch to another conf?
        intent?.takeIf { it.hasExtra(MvRx.KEY_ARG) }
                ?.let { intent.getParcelableExtra<Args>(MvRx.KEY_ARG) }
                ?.let {
                    jitsiViewModel.handle(JitsiCallViewActions.SwitchTo(it, true))
                }

        super.onNewIntent(intent)
    }

    override fun requestPermissions(permissions: Array<out String>?, requestCode: Int, listener: PermissionListener?) {
        JitsiMeetActivityDelegate.requestPermissions(this, permissions, requestCode, listener)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        JitsiMeetActivityDelegate.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun registerForBroadcastMessages() {
        val intentFilter = IntentFilter()
        for (type in BroadcastEvent.Type.values()) {
            intentFilter.addAction(type.action)
        }
        tryOrNull("Unable to register receiver") {
            LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    private fun unregisterForBroadcastMessages() {
        tryOrNull("Unable to unregister receiver") {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        }
    }

    private fun onBroadcastReceived(intent: Intent) {
        val event = BroadcastEvent(intent)
        Timber.v("Broadcast received: ${event.type}")
        when (event.type) {
            BroadcastEvent.Type.CONFERENCE_TERMINATED -> onConferenceTerminated(event.data)
            else                                      -> Unit
        }
    }

    private fun onConferenceTerminated(data: Map<String, Any>) {
        Timber.v("JitsiMeetViewListener.onConferenceTerminated()")
        // Do not finish if there is an error
        if (data["error"] == null) {
            jitsiViewModel.handle(JitsiCallViewActions.OnConferenceLeft)
        }
    }

    companion object {
        fun newIntent(context: Context, roomId: String, widgetId: String, enableVideo: Boolean): Intent {
            return Intent(context, VectorJitsiActivity::class.java).apply {
                putExtra(MvRx.KEY_ARG, Args(roomId, widgetId, enableVideo))
            }
        }
    }
}

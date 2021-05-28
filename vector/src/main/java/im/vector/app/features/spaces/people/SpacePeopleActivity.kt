/*
 * Copyright (c) 2021 New Vector Ltd
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

package im.vector.app.features.spaces.people

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.airbnb.mvrx.MvRx
import im.vector.app.R
import im.vector.app.core.extensions.commitTransaction
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.platform.GenericIdArgs
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivitySimpleLoadingBinding
import im.vector.app.features.spaces.share.ShareSpaceBottomSheet

class SpacePeopleActivity : VectorBaseActivity<ActivitySimpleLoadingBinding>() {

    override fun getBinding() = ActivitySimpleLoadingBinding.inflate(layoutInflater)

    private lateinit var sharedActionViewModel: SpacePeopleSharedActionViewModel

    override fun initUiAndData() {
        super.initUiAndData()
        waitingView = views.waitingView.waitingView
    }

    override fun showWaitingView(text: String?) {
        hideKeyboard()
        views.waitingView.waitingStatusText.isGone = views.waitingView.waitingStatusText.text.isNullOrBlank()
        super.showWaitingView(text)
    }

    override fun hideWaitingView() {
        views.waitingView.waitingStatusText.text = null
        views.waitingView.waitingStatusText.isGone = true
        views.waitingView.waitingHorizontalProgress.progress = 0
        views.waitingView.waitingHorizontalProgress.isVisible = false
        super.hideWaitingView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = intent?.getParcelableExtra<GenericIdArgs>(MvRx.KEY_ARG)
        if (isFirstCreation()) {
            val simpleName = SpacePeopleFragment::class.java.simpleName
            if (supportFragmentManager.findFragmentByTag(simpleName) == null) {
                supportFragmentManager.commitTransaction {
                    replace(R.id.simpleFragmentContainer,
                            SpacePeopleFragment::class.java,
                            Bundle().apply { this.putParcelable(MvRx.KEY_ARG, args) },
                            simpleName
                    )
                }
            }
        }

        sharedActionViewModel = viewModelProvider.get(SpacePeopleSharedActionViewModel::class.java)
        sharedActionViewModel
                .observe()
                .subscribe { sharedAction ->
                    when (sharedAction) {
                        SpacePeopleSharedAction.Dismiss             -> finish()
                        is SpacePeopleSharedAction.NavigateToRoom   -> navigateToRooms(sharedAction)
                        SpacePeopleSharedAction.HideModalLoading    -> hideWaitingView()
                        SpacePeopleSharedAction.ShowModalLoading    -> {
                            showWaitingView()
                        }
                        is SpacePeopleSharedAction.NavigateToInvite -> {
                            ShareSpaceBottomSheet.show(supportFragmentManager, sharedAction.spaceId)
                        }
                    }
                }.disposeOnDestroy()
    }

    private fun navigateToRooms(action: SpacePeopleSharedAction.NavigateToRoom) {
        navigator.openRoom(this, action.roomId)
        finish()
    }

    companion object {
        fun newIntent(context: Context, spaceId: String): Intent {
            return Intent(context, SpacePeopleActivity::class.java).apply {
                putExtra(MvRx.KEY_ARG, GenericIdArgs(spaceId))
            }
        }
    }
}

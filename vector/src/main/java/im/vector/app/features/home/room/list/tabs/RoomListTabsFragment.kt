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

package im.vector.app.features.home.room.list.tabs

import android.os.Bundle
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.tabs.TabLayoutMediator
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.features.home.HomeActivitySharedAction
import im.vector.app.features.home.HomeSharedActionViewModel
import im.vector.app.features.settings.VectorPreferences
import kotlinx.android.synthetic.main.fragment_room_list_tabs.*
import timber.log.Timber
import javax.inject.Inject

class RoomListTabsFragment @Inject constructor(
        private val viewModelFactory: RoomListTabsViewModel.Factory,
        private val vectorPreferences: VectorPreferences
) : VectorBaseFragment(), RoomListTabsViewModel.Factory by viewModelFactory {

    private val viewModel: RoomListTabsViewModel by fragmentViewModel()
    private lateinit var pagerAdapter: RoomListTabsPagerAdapter
    private lateinit var sharedActionViewModel: HomeSharedActionViewModel

    override fun getLayoutResId() = R.layout.fragment_room_list_tabs

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedActionViewModel = activityViewModelProvider.get(HomeSharedActionViewModel::class.java)
        pagerAdapter = RoomListTabsPagerAdapter(this, requireContext(), vectorPreferences)
        viewPager.adapter = pagerAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val item = pagerAdapter.getTabs()[position]
            tab.text = getString(item.titleRes).toLowerCase().capitalize()
        }.attach()

        val onPageChangeListener: ViewPager2.OnPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val item = pagerAdapter.getTabs()[position]
                sharedActionViewModel.post(HomeActivitySharedAction.OnDisplayModeSelected(item))
            }
        }
        viewPager.registerOnPageChangeCallback(onPageChangeListener)
    }

    override fun onResume() {
        // Tmp if the labs preference did change
        pagerAdapter.notifyDataSetChanged()
        super.onResume()
    }
    override fun invalidate() = withState(viewModel) { state ->
        Timber.v("Invalidate state: $state")
    }
}

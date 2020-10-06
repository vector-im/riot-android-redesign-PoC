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

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.airbnb.mvrx.MvRx
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.home.room.list.RoomListFragment
import im.vector.app.features.home.room.list.RoomListParams
import im.vector.app.features.settings.VectorPreferences

class RoomListTabsPagerAdapter(private val fragment: Fragment,
                               private val context: Context,
                               private val vectorPreferences: VectorPreferences) : FragmentStateAdapter(fragment) {

//    companion object {
//        val TABS = listOf(
//                RoomListDisplayMode.ALL,
//                RoomListDisplayMode.FAVORITES,
//                RoomListDisplayMode.NOTIFICATIONS,
//                RoomListDisplayMode.ROOMS,
//                RoomListDisplayMode.PEOPLE,
//                RoomListDisplayMode.INVITES,
//                RoomListDisplayMode.LOW_PRIORITY
//        )
//    }

    fun getTabs() : List<RoomListDisplayMode> {
        return ArrayList<RoomListDisplayMode>().apply {
            add(RoomListDisplayMode.ALL)
            if (!vectorPreferences.labPinFavInTabNavigation()) {
                add(RoomListDisplayMode.FAVORITES)
            }
            add(RoomListDisplayMode.NOTIFICATIONS)
            add(RoomListDisplayMode.ROOMS)
            add(RoomListDisplayMode.PEOPLE)
            add(RoomListDisplayMode.INVITES)
            add(RoomListDisplayMode.LOW_PRIORITY)
        }
    }

    override fun getItemCount() = getTabs().count()

    override fun createFragment(position: Int): Fragment {
        val roomListFragment = fragment.childFragmentManager.fragmentFactory.instantiate(context.classLoader, RoomListFragment::class.java.name)
        val displayMode = getTabs()[position]
        val params = RoomListParams(displayMode)
        return roomListFragment.apply {
            arguments = Bundle().apply { putParcelable(MvRx.KEY_ARG, params) }
        }
    }
}

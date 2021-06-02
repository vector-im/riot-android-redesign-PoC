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

package im.vector.app.features.home.room.detail.timeline.helper

import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.VisibleForTesting
import im.vector.app.R
import im.vector.app.core.resources.ColorProvider
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MatrixItemColorProvider @Inject constructor(
        private val colorProvider: ColorProvider
) {
    private val cache = mutableMapOf<String, Int>()

    @ColorInt
    fun getColor(matrixItem: MatrixItem): Int {
        return cache.getOrPut(matrixItem.id) {
            colorProvider.getColor(
                    when (matrixItem) {
                        is MatrixItem.UserItem -> getColorFromUserId(matrixItem.id)
                        else                   -> getColorFromRoomId(matrixItem.id)
                    }
            )
        }
    }

    fun setOverrideColors(overrideColors: Map<String, String>?) {
        overrideColors?.forEach() {
            setOverrideColor(it.key, it.value)
        }
    }

    fun setOverrideColor(id: String, colorSpec: String?) : Boolean {
        val color = parseUserColorSpec(colorSpec)
        if (color == null) {
            cache.remove(id)
            return false
        } else {
            cache.put(id, color)
            return true
        }
    }

    @ColorInt
    private fun parseUserColorSpec(colorText: String?): Int? {
        if (colorText.isNullOrBlank()) {
            return null
        }
        try {
            if (colorText.first() == '#') {
                return (colorText.substring(1).toLong(radix = 16) or 0xff000000L).toInt()
            } else {
                return colorProvider.getColor(getUserColorByIndex(colorText.toInt()))
            }
        } catch (e: Throwable) {
            return null
        }
    }

    companion object {
        @ColorRes
        @VisibleForTesting
        fun getColorFromUserId(userId: String?): Int {
            var hash = 0

            userId?.toList()?.map { chr -> hash = (hash shl 5) - hash + chr.code }

            return getUserColorByIndex(abs(hash))
        }

        @ColorRes
        private fun getUserColorByIndex(index: Int): Int {
            return when (index % 8) {
                1    -> R.color.riotx_username_2
                2    -> R.color.riotx_username_3
                3    -> R.color.riotx_username_4
                4    -> R.color.riotx_username_5
                5    -> R.color.riotx_username_6
                6    -> R.color.riotx_username_7
                7    -> R.color.riotx_username_8
                else -> R.color.riotx_username_1
            }
        }

        @ColorRes
        private fun getColorFromRoomId(roomId: String?): Int {
            return when ((roomId?.toList()?.sumOf { it.code } ?: 0) % 3) {
                1    -> R.color.riotx_avatar_fill_2
                2    -> R.color.riotx_avatar_fill_3
                else -> R.color.riotx_avatar_fill_1
            }
        }
    }
}

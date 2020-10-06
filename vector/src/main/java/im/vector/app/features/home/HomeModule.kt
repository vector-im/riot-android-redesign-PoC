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

package im.vector.app.features.home

import android.os.Handler
import android.os.HandlerThread
import dagger.Module
import dagger.Provides
import im.vector.app.features.home.room.detail.timeline.EpoxyControllerDiffHandler
import im.vector.app.features.home.room.detail.timeline.EpoxyControllerModelHandler
import im.vector.app.features.home.room.detail.timeline.TimelineEventControllerHandler
import im.vector.app.features.home.room.detail.timeline.helper.EpoxyHandlerHelper
import im.vector.app.features.home.room.detail.timeline.helper.TimelineAsyncHelper

@Module
object HomeModule {

    @Provides
    @JvmStatic
    @TimelineEventControllerHandler
    fun providesTimelineBackgroundHandler(): Handler {
        return TimelineAsyncHelper.getBackgroundHandler()
    }

    @Provides
    @JvmStatic
    @EpoxyControllerModelHandler
    fun providesEpoxyControllerModelHandler(): Handler {
        return EpoxyHandlerHelper.modelBuildingHandler
    }

    @Provides
    @JvmStatic
    @EpoxyControllerDiffHandler
    fun providesEpoxyControllerDiffHandler(): Handler {
        return EpoxyHandlerHelper.diffingHandler
    }
}

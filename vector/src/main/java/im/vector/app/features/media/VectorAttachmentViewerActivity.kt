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
package im.vector.app.features.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.transition.addListener
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.transition.Transition
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DaggerScreenComponent
import im.vector.app.core.di.HasVectorInjector
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.di.VectorComponent
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.utils.shareMedia
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.attachmentviewer.AttachmentCommands
import im.vector.lib.attachmentviewer.AttachmentViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class VectorAttachmentViewerActivity : AttachmentViewerActivity(), BaseAttachmentProvider.InteractionListener {

    @Parcelize
    data class Args(
            val roomId: String?,
            val eventId: String,
            val sharedTransitionName: String?
    ) : Parcelable

    @Inject
    lateinit var sessionHolder: ActiveSessionHolder

    @Inject
    lateinit var dataSourceFactory: AttachmentProviderFactory

    @Inject
    lateinit var imageContentRenderer: ImageContentRenderer

    private lateinit var screenComponent: ScreenComponent

    private var initialIndex = 0
    private var isAnimatingOut = false

    private var currentSourceProvider: BaseAttachmentProvider<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate Activity ${javaClass.simpleName}")
        val vectorComponent = getVectorComponent()
        screenComponent = DaggerScreenComponent.factory().create(vectorComponent, this)
        val timeForInjection = measureTimeMillis {
            screenComponent.inject(this)
        }
        Timber.v("Injecting dependencies into ${javaClass.simpleName} took $timeForInjection ms")
        ThemeUtils.setActivityTheme(this, getOtherThemes())

        val args = args() ?: throw IllegalArgumentException("Missing arguments")

        if (savedInstanceState == null && addTransitionListener()) {
            args.sharedTransitionName?.let {
                ViewCompat.setTransitionName(imageTransitionView, it)
                transitionImageContainer.isVisible = true

                // Postpone transaction a bit until thumbnail is loaded
                val mediaData: Parcelable? = intent.getParcelableExtra(EXTRA_IMAGE_DATA)
                if (mediaData is ImageContentRenderer.Data) {
                    // will be shown at end of transition
                    pager2.isInvisible = true
                    supportPostponeEnterTransition()
                    imageContentRenderer.renderForSharedElementTransition(mediaData, imageTransitionView) {
                        // Proceed with transaction
                        scheduleStartPostponedTransition(imageTransitionView)
                    }
                } else if (mediaData is VideoContentRenderer.Data) {
                    // will be shown at end of transition
                    pager2.isInvisible = true
                    supportPostponeEnterTransition()
                    imageContentRenderer.renderForSharedElementTransition(mediaData.thumbnailMediaData, imageTransitionView) {
                        // Proceed with transaction
                        scheduleStartPostponedTransition(imageTransitionView)
                    }
                }
            }
        }

        val session = sessionHolder.getSafeActiveSession() ?: return Unit.also { finish() }

        val room = args.roomId?.let { session.getRoom(it) }

        val inMemoryData = intent.getParcelableArrayListExtra<AttachmentData>(EXTRA_IN_MEMORY_DATA)
        val sourceProvider = if (inMemoryData != null) {
            initialIndex = inMemoryData.indexOfFirst { it.eventId == args.eventId }.coerceAtLeast(0)
            dataSourceFactory.createProvider(inMemoryData, room, lifecycleScope)
        } else {
            val events = room?.getAttachmentMessages().orEmpty()
            initialIndex = events.indexOfFirst { it.eventId == args.eventId }.coerceAtLeast(0)
            dataSourceFactory.createProvider(events, lifecycleScope)
        }
        sourceProvider.interactionListener = this
        setSourceProvider(sourceProvider)
        currentSourceProvider = sourceProvider
        if (savedInstanceState == null) {
            pager2.setCurrentItem(initialIndex, false)
            // The page change listener is not notified of the change...
            pager2.post {
                onSelectedPositionChanged(initialIndex)
            }
        }

        window.statusBarColor = ContextCompat.getColor(this, R.color.black_alpha)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.black_alpha)
    }

    override fun onResume() {
        super.onResume()
        Timber.i("onResume Activity ${javaClass.simpleName}")
    }

    override fun onPause() {
        super.onPause()
        Timber.i("onPause Activity ${javaClass.simpleName}")
    }

    private fun getOtherThemes() = ActivityOtherThemes.VectorAttachmentsPreview

    override fun shouldAnimateDismiss(): Boolean {
        return currentPosition != initialIndex
    }

    override fun onBackPressed() {
        if (currentPosition == initialIndex) {
            // show back the transition view
            // TODO, we should track and update the mapping
            transitionImageContainer.isVisible = true
        }
        isAnimatingOut = true
        super.onBackPressed()
    }

    override fun animateClose() {
        if (currentPosition == initialIndex) {
            // show back the transition view
            // TODO, we should track and update the mapping
            transitionImageContainer.isVisible = true
        }
        isAnimatingOut = true
        ActivityCompat.finishAfterTransition(this)
    }

    // ==========================================================================================
    // PRIVATE METHODS
    // ==========================================================================================

    /**
     * Try and add a [Transition.TransitionListener] to the entering shared element
     * [Transition]. We do this so that we can load the full-size image after the transition
     * has completed.
     *
     * @return true if we were successful in adding a listener to the enter transition
     */
    private fun addTransitionListener(): Boolean {
        val transition = window.sharedElementEnterTransition

        if (transition != null) {
            // There is an entering shared element transition so add a listener to it
            transition.addListener(
                    onEnd = {
                        // The listener is also called when we are exiting
                        // so we use a boolean to avoid reshowing pager at end of dismiss transition
                        if (!isAnimatingOut) {
                            transitionImageContainer.isVisible = false
                            pager2.isInvisible = false
                        }
                    },
                    onCancel = {
                        if (!isAnimatingOut) {
                            transitionImageContainer.isVisible = false
                            pager2.isInvisible = false
                        }
                    }
            )
            return true
        }

        // If we reach here then we have not added a listener
        return false
    }

    private fun args() = intent.getParcelableExtra<Args>(EXTRA_ARGS)

    private fun getVectorComponent(): VectorComponent {
        return (application as HasVectorInjector).injector()
    }

    private fun scheduleStartPostponedTransition(sharedElement: View) {
        sharedElement.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        sharedElement.viewTreeObserver.removeOnPreDrawListener(this)
                        supportStartPostponedEnterTransition()
                        return true
                    }
                })
    }

    companion object {
        const val EXTRA_ARGS = "EXTRA_ARGS"
        const val EXTRA_IMAGE_DATA = "EXTRA_IMAGE_DATA"
        const val EXTRA_IN_MEMORY_DATA = "EXTRA_IN_MEMORY_DATA"

        fun newIntent(context: Context,
                      mediaData: AttachmentData,
                      roomId: String?,
                      eventId: String,
                      inMemoryData: List<AttachmentData>,
                      sharedTransitionName: String?) = Intent(context, VectorAttachmentViewerActivity::class.java).also {
            it.putExtra(EXTRA_ARGS, Args(roomId, eventId, sharedTransitionName))
            it.putExtra(EXTRA_IMAGE_DATA, mediaData)
            if (inMemoryData.isNotEmpty()) {
                it.putParcelableArrayListExtra(EXTRA_IN_MEMORY_DATA, ArrayList(inMemoryData))
            }
        }
    }

    override fun onDismissTapped() {
        animateClose()
    }

    override fun onPlayPause(play: Boolean) {
        handle(if (play) AttachmentCommands.StartVideo else AttachmentCommands.PauseVideo)
    }

    override fun videoSeekTo(percent: Int) {
        handle(AttachmentCommands.SeekTo(percent))
    }

    override fun onShareTapped() {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = currentSourceProvider?.getFileForSharing(currentPosition) ?: return@launch

            withContext(Dispatchers.Main) {
                shareMedia(
                        this@VectorAttachmentViewerActivity,
                        file,
                        getMimeTypeFromUri(this@VectorAttachmentViewerActivity, file.toUri())
                )
            }
        }
    }
}

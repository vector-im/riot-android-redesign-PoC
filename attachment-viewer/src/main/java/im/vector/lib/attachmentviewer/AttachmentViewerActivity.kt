/*
 * Copyright (c) 2020 New Vector Ltd
 * Copyright (C) 2018 stfalcon.com
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

package im.vector.lib.attachmentviewer

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import im.vector.lib.attachmentviewer.databinding.ActivityAttachmentViewerBinding

import java.lang.ref.WeakReference
import kotlin.math.abs

abstract class AttachmentViewerActivity : AppCompatActivity(), AttachmentEventListener {

    protected val pager2: ViewPager2
        get() = views.attachmentPager
    protected val imageTransitionView: ImageView
        get() = views.transitionImageView
    protected val transitionImageContainer: ViewGroup
        get() = views.transitionImageContainer

    private var topInset = 0
    private var bottomInset = 0
    private var systemUiVisibility = true

    private var overlayView: View? = null
        set(value) {
            if (value == overlayView) return
            overlayView?.let { views.rootContainer.removeView(it) }
            views.rootContainer.addView(value)
            value?.updatePadding(top = topInset, bottom = bottomInset)
            field = value
        }

    private lateinit var views: ActivityAttachmentViewerBinding

    private lateinit var swipeDismissHandler: SwipeToDismissHandler
    private lateinit var directionDetector: SwipeDirectionDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetectorCompat

    var currentPosition = 0
        private set

    private var swipeDirection: SwipeDirection? = null

    private fun isScaled() = attachmentsAdapter.isScaled(currentPosition)

    private val attachmentsAdapter = AttachmentsAdapter()

    private var wasScaled: Boolean = false
    private var isSwipeToDismissAllowed: Boolean = true
    private var isOverlayWasClicked = false

//    private val shouldDismissToBottom: Boolean
//        get() = e == null
//                || !externalTransitionImageView.isRectVisible
//                || !isAtStartPosition

    private var isImagePagerIdle = true

    fun setSourceProvider(sourceProvider: AttachmentSourceProvider) {
        attachmentsAdapter.attachmentSourceProvider = sourceProvider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setDecorViewFullScreen()

        views = ActivityAttachmentViewerBinding.inflate(layoutInflater)
        setContentView(views.root)
        views.attachmentPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        views.attachmentPager.adapter = attachmentsAdapter
        directionDetector = createSwipeDirectionDetector()
        gestureDetector = createGestureDetector()

        views.attachmentPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                isImagePagerIdle = state == ViewPager2.SCROLL_STATE_IDLE
            }

            override fun onPageSelected(position: Int) {
                onSelectedPositionChanged(position)
            }
        })

        swipeDismissHandler = createSwipeToDismissHandler()
        views.rootContainer.setOnTouchListener(swipeDismissHandler)
        views.rootContainer.viewTreeObserver.addOnGlobalLayoutListener { swipeDismissHandler.translationLimit = views.dismissContainer.height / 4 }

        scaleDetector = createScaleGestureDetector()

        ViewCompat.setOnApplyWindowInsetsListener(views.rootContainer) { _, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            overlayView?.updatePadding(top = systemBarsInsets.top, bottom = systemBarsInsets.bottom)
            topInset = systemBarsInsets.top
            bottomInset = systemBarsInsets.bottom
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun setDecorViewFullScreen() {
        // This is important for the dispatchTouchEvent, if not we must correct
        // the touch coordinates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New API instead of SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.setDecorFitsSystemWindows(false)
            // New API instead of SYSTEM_UI_FLAG_IMMERSIVE
            window.decorView.windowInsetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE
            // New API instead of FLAG_TRANSLUCENT_STATUS
            window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
            // new API instead of FLAG_TRANSLUCENT_NAVIGATION
            window.navigationBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE)
            window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
    }

    fun onSelectedPositionChanged(position: Int) {
        attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition)?.let {
            (it as? BaseViewHolder)?.onSelected(false)
        }
        attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(position)?.let {
            (it as? BaseViewHolder)?.onSelected(true)
            if (it is VideoViewHolder) {
                it.eventListener = WeakReference(this)
            }
        }
        currentPosition = position
        overlayView = attachmentsAdapter.attachmentSourceProvider?.overlayViewAtPosition(this@AttachmentViewerActivity, position)
    }

    override fun onPause() {
        attachmentsAdapter.onPause(currentPosition)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        attachmentsAdapter.onResume(currentPosition)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // The zoomable view is configured to disallow interception when image is zoomed

        // Check if the overlay is visible, and wants to handle the click
        if (overlayView?.isVisible == true && overlayView?.dispatchTouchEvent(ev) == true) {
            return true
        }

        // Log.v("ATTACHEMENTS", "================\ndispatchTouchEvent $ev")
        handleUpDownEvent(ev)

        // Log.v("ATTACHEMENTS", "scaleDetector is in progress ${scaleDetector.isInProgress}")
        // Log.v("ATTACHEMENTS", "pointerCount ${ev.pointerCount}")
        // Log.v("ATTACHEMENTS", "wasScaled $wasScaled")
        if (swipeDirection == null && (scaleDetector.isInProgress || ev.pointerCount > 1 || wasScaled)) {
            wasScaled = true
//            Log.v("ATTACHEMENTS", "dispatch to pager")
            return views.attachmentPager.dispatchTouchEvent(ev)
        }

        // Log.v("ATTACHEMENTS", "is current item scaled ${isScaled()}")
        return (if (isScaled()) super.dispatchTouchEvent(ev) else handleTouchIfNotScaled(ev)).also {
//            Log.v("ATTACHEMENTS", "\n================")
        }
    }

    private fun handleUpDownEvent(event: MotionEvent) {
        // Log.v("ATTACHEMENTS", "handleUpDownEvent $event")
        if (event.action == MotionEvent.ACTION_UP) {
            handleEventActionUp(event)
        }

        if (event.action == MotionEvent.ACTION_DOWN) {
            handleEventActionDown(event)
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
    }

    private fun handleEventActionDown(event: MotionEvent) {
        swipeDirection = null
        wasScaled = false
        views.attachmentPager.dispatchTouchEvent(event)

        swipeDismissHandler.onTouch(views.rootContainer, event)
        isOverlayWasClicked = dispatchOverlayTouch(event)
    }

    private fun handleEventActionUp(event: MotionEvent) {
//        wasDoubleTapped = false
        swipeDismissHandler.onTouch(views.rootContainer, event)
        views.attachmentPager.dispatchTouchEvent(event)
        isOverlayWasClicked = dispatchOverlayTouch(event)
    }

    private fun handleSingleTap(event: MotionEvent, isOverlayWasClicked: Boolean) {
        // TODO if there is no overlay, we should at least toggle system bars?
        if (overlayView != null && !isOverlayWasClicked) {
            toggleOverlayViewVisibility()
            super.dispatchTouchEvent(event)
        }
    }

    private fun toggleOverlayViewVisibility() {
        if (systemUiVisibility) {
            // we hide
            TransitionManager.beginDelayedTransition(views.rootContainer)
            hideSystemUI()
            overlayView?.isVisible = false
        } else {
            // we show
            TransitionManager.beginDelayedTransition(views.rootContainer)
            showSystemUI()
            overlayView?.isVisible = true
        }
    }

    private fun handleTouchIfNotScaled(event: MotionEvent): Boolean {
//        Log.v("ATTACHEMENTS", "handleTouchIfNotScaled $event")
        directionDetector.handleTouchEvent(event)

        return when (swipeDirection) {
            SwipeDirection.Up, SwipeDirection.Down    -> {
                if (isSwipeToDismissAllowed && !wasScaled && isImagePagerIdle) {
                    swipeDismissHandler.onTouch(views.rootContainer, event)
                } else true
            }
            SwipeDirection.Left, SwipeDirection.Right -> {
                views.attachmentPager.dispatchTouchEvent(event)
            }
            else                                      -> true
        }
    }

    private fun handleSwipeViewMove(translationY: Float, translationLimit: Int) {
        val alpha = calculateTranslationAlpha(translationY, translationLimit)
        views.backgroundView.alpha = alpha
        views.dismissContainer.alpha = alpha
        overlayView?.alpha = alpha
    }

    private fun dispatchOverlayTouch(event: MotionEvent): Boolean =
            overlayView
                    ?.let { it.isVisible && it.dispatchTouchEvent(event) }
                    ?: false

    private fun calculateTranslationAlpha(translationY: Float, translationLimit: Int): Float =
            1.0f - 1.0f / translationLimit.toFloat() / 4f * abs(translationY)

    private fun createSwipeToDismissHandler()
            : SwipeToDismissHandler = SwipeToDismissHandler(
            swipeView = views.dismissContainer,
            shouldAnimateDismiss = { shouldAnimateDismiss() },
            onDismiss = { animateClose() },
            onSwipeViewMove = ::handleSwipeViewMove)

    private fun createSwipeDirectionDetector() =
            SwipeDirectionDetector(this) { swipeDirection = it }

    private fun createScaleGestureDetector() =
            ScaleGestureDetector(this, ScaleGestureDetector.SimpleOnScaleGestureListener())

    private fun createGestureDetector() =
            GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isImagePagerIdle) {
                        handleSingleTap(e, isOverlayWasClicked)
                    }
                    return false
                }

                override fun onDoubleTap(e: MotionEvent?): Boolean {
                    return super.onDoubleTap(e)
                }
            })

    override fun onEvent(event: AttachmentEvents) {
        if (overlayView is AttachmentEventListener) {
            (overlayView as? AttachmentEventListener)?.onEvent(event)
        }
    }

    protected open fun shouldAnimateDismiss(): Boolean = true

    protected open fun animateClose() {
        window.statusBarColor = Color.TRANSPARENT
        finish()
    }

    fun handle(commands: AttachmentCommands) {
        (attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? BaseViewHolder)
                ?.handleCommand(commands)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        systemUiVisibility = false
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New API instead of SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.setDecorFitsSystemWindows(false)
            // new API instead of SYSTEM_UI_FLAG_HIDE_NAVIGATION
            window.decorView.windowInsetsController?.hide(WindowInsets.Type.navigationBars())
            // New API instead of SYSTEM_UI_FLAG_IMMERSIVE
            window.decorView.windowInsetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE
            // New API instead of FLAG_TRANSLUCENT_STATUS
            window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
            // New API instead of FLAG_TRANSLUCENT_NAVIGATION
            window.navigationBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    // Shows the system bars by removing all the flags
// except for the ones that make the content appear under the system bars.
    @Suppress("DEPRECATION")
    private fun showSystemUI() {
        systemUiVisibility = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New API instead of SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}

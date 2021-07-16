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

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.net.Uri
import android.text.Editable
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.text.toSpannable
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.transition.AutoTransition
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import im.vector.app.R
import im.vector.app.databinding.ComposerLayoutBinding
import org.matrix.android.sdk.api.extensions.orFalse

/**
 * Encapsulate the timeline composer UX.
 */
class TextComposerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0) : ConstraintLayout(context, attrs, defStyleAttr) {

    interface Callback : ComposerEditText.Callback {
        fun onCloseRelatedMessage()
        fun onSendMessage(text: CharSequence)
        fun onAddAttachment()
    }

    val views: ComposerLayoutBinding

    var callback: Callback? = null

    private var currentConstraintSetId: Int = -1

    private val animationDuration = 100L

    val text: Editable?
        get() = views.composerEditText.text

    var alwaysShowSendButton = false
        set(value) {
            field = value
            val shouldShowSendButton = currentConstraintSetId == R.layout.composer_layout_constraint_set_expanded || text?.isNotBlank().orFalse() || value
            views.sendButton.isInvisible = !shouldShowSendButton
        }

    init {
        inflate(context, R.layout.composer_layout, this)
        views = ComposerLayoutBinding.bind(this)

        collapse(false)

        views.composerEditText.callback = object : ComposerEditText.Callback {
            override fun onRichContentSelected(contentUri: Uri): Boolean {
                return callback?.onRichContentSelected(contentUri) ?: false
            }

            override fun onTextBlankStateChanged(isBlank: Boolean) {
                val shouldShowSendButton = currentConstraintSetId == R.layout.composer_layout_constraint_set_expanded || !isBlank || alwaysShowSendButton
                TransitionManager.endTransitions(this@TextComposerView)
                if (views.sendButton.isVisible != shouldShowSendButton) {
                    TransitionManager.beginDelayedTransition(
                            this@TextComposerView,
                            AutoTransition().also { it.duration = 150 }
                    )
                    views.sendButton.isInvisible = !shouldShowSendButton
                }
                callback?.onTextBlankStateChanged(isBlank)
            }
        }
        views.composerRelatedMessageCloseButton.setOnClickListener {
            collapse()
            callback?.onCloseRelatedMessage()
        }

        views.sendButton.setOnClickListener {
            val textMessage = text?.toSpannable() ?: ""
            callback?.onSendMessage(textMessage)
        }

        views.attachmentButton.setOnClickListener {
            callback?.onAddAttachment()
        }
    }

    fun collapse(animate: Boolean = true, transitionComplete: (() -> Unit)? = null) {
        if (currentConstraintSetId == R.layout.composer_layout_constraint_set_compact) {
            // ignore we good
            return
        }
        currentConstraintSetId = R.layout.composer_layout_constraint_set_compact
        applyNewConstraintSet(animate, transitionComplete)

        val shouldShowSendButton = !views.composerEditText.text.isNullOrEmpty() || alwaysShowSendButton
        views.sendButton.isInvisible = !shouldShowSendButton
    }

    fun expand(animate: Boolean = true, transitionComplete: (() -> Unit)? = null) {
        if (currentConstraintSetId == R.layout.composer_layout_constraint_set_expanded) {
            // ignore we good
            return
        }
        currentConstraintSetId = R.layout.composer_layout_constraint_set_expanded
        applyNewConstraintSet(animate, transitionComplete)
        views.sendButton.isInvisible = false
    }

    private fun applyNewConstraintSet(animate: Boolean, transitionComplete: (() -> Unit)?) {
        if (animate) {
            configureAndBeginTransition(transitionComplete)
        }
        ConstraintSet().also {
            it.clone(context, currentConstraintSetId)
            it.applyTo(this)
        }
    }

    private fun configureAndBeginTransition(transitionComplete: (() -> Unit)? = null) {
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_SEQUENTIAL
            addTransition(ChangeBounds())
            addTransition(Fade(Fade.IN))
            duration = animationDuration
            addListener(object : Transition.TransitionListener {
                override fun onTransitionEnd(transition: Transition) {
                    transitionComplete?.invoke()
                }

                override fun onTransitionResume(transition: Transition) {}

                override fun onTransitionPause(transition: Transition) {}

                override fun onTransitionCancel(transition: Transition) {}

                override fun onTransitionStart(transition: Transition) {}
            })
        }
        TransitionManager.beginDelayedTransition((parent as? ViewGroup ?: this), transition)
    }

    fun setRoomEncrypted(isEncrypted: Boolean) {
        if (isEncrypted) {
            views.composerEditText.setHint(R.string.room_message_placeholder)
        } else {
            views.composerEditText.setHint(R.string.room_message_placeholder)
        }
    }
}

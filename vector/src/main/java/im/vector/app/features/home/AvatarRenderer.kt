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

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.core.graphics.drawable.toBitmap
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import im.vector.app.core.contacts.MappedContact
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.GlideRequest
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import jp.wasabeef.glide.transformations.BlurTransformation
import jp.wasabeef.glide.transformations.ColorFilterTransformation
import org.matrix.android.sdk.api.auth.login.LoginProfileInfo
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

/**
 * This helper centralise ways to retrieve avatar into ImageView or even generic Target<Drawable>
 */

class AvatarRenderer @Inject constructor(private val activeSessionHolder: ActiveSessionHolder,
                                         private val matrixItemColorProvider: MatrixItemColorProvider,
                                         private val dimensionConverter: DimensionConverter) {

    companion object {
        private const val THUMBNAIL_SIZE = 250
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView) {
        render(GlideApp.with(imageView),
                matrixItem,
                DrawableImageViewTarget(imageView))
    }

    @UiThread
    fun renderSpace(matrixItem: MatrixItem, imageView: ImageView, glideRequests: GlideRequests) {
        val placeholder = getSpacePlaceholderDrawable(matrixItem)
        val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
        glideRequests
                .load(resolvedUrl)
                .transform(MultiTransformation(CenterCrop(), RoundedCorners(dimensionConverter.dpToPx(8))))
                .placeholder(placeholder)
                .into(DrawableImageViewTarget(imageView))
    }

    fun renderSpace(matrixItem: MatrixItem, imageView: ImageView) {
        renderSpace(
                matrixItem,
                imageView,
                GlideApp.with(imageView)
        )
    }

    fun clear(imageView: ImageView) {
        // It can be called after recycler view is destroyed, just silently catch
        tryOrNull { GlideApp.with(imageView).clear(imageView) }
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView, glideRequests: GlideRequests) {
        render(glideRequests,
                matrixItem,
                DrawableImageViewTarget(imageView))
    }

    @UiThread
    fun render(mappedContact: MappedContact, imageView: ImageView) {
        // Create a Fake MatrixItem, for the placeholder
        val matrixItem = MatrixItem.UserItem(
                // Need an id starting with @
                id = "@${mappedContact.displayName}",
                displayName = mappedContact.displayName
        )

        val placeholder = getPlaceholderDrawable(matrixItem)
        GlideApp.with(imageView)
                .load(mappedContact.photoURI)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(placeholder)
                .into(imageView)
    }

    @UiThread
    fun render(profileInfo: LoginProfileInfo, imageView: ImageView) {
        // Create a Fake MatrixItem, for the placeholder
        val matrixItem = MatrixItem.UserItem(
                // Need an id starting with @
                id = profileInfo.matrixId,
                displayName = profileInfo.displayName
        )

        val placeholder = getPlaceholderDrawable(matrixItem)
        GlideApp.with(imageView)
                .load(profileInfo.fullAvatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(placeholder)
                .into(imageView)
    }

    @UiThread
    fun render(glideRequests: GlideRequests,
               matrixItem: MatrixItem,
               target: Target<Drawable>) {
        val placeholder = getPlaceholderDrawable(matrixItem)
        buildGlideRequest(glideRequests, matrixItem.avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(placeholder)
                .into(target)
    }

    @AnyThread
    @Throws
    fun shortcutDrawable(glideRequests: GlideRequests, matrixItem: MatrixItem, iconSize: Int): Bitmap {
        return glideRequests
                .asBitmap()
                .apply {
                    val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
                    if (resolvedUrl != null) {
                        load(resolvedUrl)
                    } else {
                        val avatarColor = matrixItemColorProvider.getColor(matrixItem)
                        load(TextDrawable.builder()
                                .beginConfig()
                                .bold()
                                .endConfig()
                                .buildRect(matrixItem.firstLetterOfDisplayName(), avatarColor)
                                .toBitmap(width = iconSize, height = iconSize))
                    }
                }
                .submit(iconSize, iconSize)
                .get()
    }

    @UiThread
    fun renderBlur(matrixItem: MatrixItem, imageView: ImageView, sampling: Int, rounded: Boolean, @ColorInt colorFilter: Int? = null) {
        val transformations = mutableListOf<Transformation<Bitmap>>(
                BlurTransformation(20, sampling)
        )
        if (colorFilter != null) {
            transformations.add(ColorFilterTransformation(colorFilter))
        }
        if (rounded) {
            transformations.add(CircleCrop())
        }
        buildGlideRequest(GlideApp.with(imageView), matrixItem.avatarUrl)
                .apply(RequestOptions.bitmapTransform(MultiTransformation(transformations)))
                .into(imageView)
    }

    @AnyThread
    fun getCachedDrawable(glideRequests: GlideRequests, matrixItem: MatrixItem): Drawable {
        return buildGlideRequest(glideRequests, matrixItem.avatarUrl)
                .onlyRetrieveFromCache(true)
                .apply(RequestOptions.circleCropTransform())
                .submit()
                .get()
    }

    @AnyThread
    fun getPlaceholderDrawable(matrixItem: MatrixItem): Drawable {
        val avatarColor = matrixItemColorProvider.getColor(matrixItem)
        return TextDrawable.builder()
                .beginConfig()
                .bold()
                .endConfig()
                .buildRound(matrixItem.firstLetterOfDisplayName(), avatarColor)
    }

    @AnyThread
    fun getSpacePlaceholderDrawable(matrixItem: MatrixItem): Drawable {
        val avatarColor = matrixItemColorProvider.getColor(matrixItem)
        return TextDrawable.builder()
                .beginConfig()
                .bold()
                .endConfig()
                .buildRoundRect(matrixItem.firstLetterOfDisplayName(), avatarColor, dimensionConverter.dpToPx(8))
    }

    // PRIVATE API *********************************************************************************

    private fun buildGlideRequest(glideRequests: GlideRequests, avatarUrl: String?): GlideRequest<Drawable> {
        val resolvedUrl = resolvedUrl(avatarUrl)
        return glideRequests.load(resolvedUrl)
    }

    private fun resolvedUrl(avatarUrl: String?): String? {
        return activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
                ?.resolveThumbnail(avatarUrl, THUMBNAIL_SIZE, THUMBNAIL_SIZE, ContentUrlResolver.ThumbnailMethod.SCALE)
    }
}

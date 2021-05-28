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
package im.vector.app.features.reactions

import android.os.Build
import android.os.Trace
import android.text.Layout
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import im.vector.app.R
import im.vector.app.features.reactions.data.EmojiDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/**
 *
 * TODO: Configure Span using available width and emoji size
 * TODO: Performances
 * TODO: Scroll to section - Find a way to snap section to the top
 */
class EmojiRecyclerAdapter @Inject constructor(
        private val dataSource: EmojiDataSource
) :
        RecyclerView.Adapter<EmojiRecyclerAdapter.ViewHolder>() {

    var reactionClickListener: ReactionClickListener? = null
    var interactionListener: InteractionListener? = null
    private var mRecyclerView: RecyclerView? = null

    private var currentFirstVisibleSection = 0

    private enum class ScrollState {
        IDLE,
        DRAGGING,
        SETTLING,
        UNKNOWN
    }

    private var scrollState = ScrollState.UNKNOWN
    private var isFastScroll = false

    private val toUpdateWhenNotBusy = ArrayList<Pair<String, EmojiViewHolder>>()

    private val itemClickListener = View.OnClickListener { view ->
        mRecyclerView?.getChildLayoutPosition(view)?.let { itemPosition ->
            if (itemPosition != RecyclerView.NO_POSITION) {
                val sectionNumber = getSectionForAbsoluteIndex(itemPosition)
                if (!isSection(itemPosition)) {
                    val sectionMojis = dataSource.rawData.categories[sectionNumber].emojis
                    val sectionOffset = getSectionOffset(sectionNumber)
                    val emoji = sectionMojis[itemPosition - sectionOffset]
                    val item = dataSource.rawData.emojis.getValue(emoji).emoji
                    reactionClickListener?.onReactionSelected(item)
                }
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.mRecyclerView = recyclerView

        val gridLayoutManager = GridLayoutManager(recyclerView.context, 8)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (isSection(position)) gridLayoutManager.spanCount else 1
            }
        }.apply {
            isSpanIndexCacheEnabled = true
        }
        recyclerView.layoutManager = gridLayoutManager

        recyclerView.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
        }

        recyclerView.setHasFixedSize(true)
        // Default is 5 but we have lots of views for emojis
        recyclerView.recycledViewPool
                .setMaxRecycledViews(R.layout.grid_item_emoji, 300)

        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.mRecyclerView = null
        recyclerView.removeOnScrollListener(scrollListener)
        staticLayoutCache.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun scrollToSection(section: Int) {
        if (section < 0 || section >= dataSource.rawData.categories.size) {
            // ignore
            return
        }
        // mRecyclerView?.smoothScrollToPosition(getSectionOffset(section) - 1)
        // TODO Snap section header to top
        mRecyclerView?.scrollToPosition(getSectionOffset(section) - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        Trace.beginSection("MyAdapter.onCreateViewHolder")
        val inflater = LayoutInflater.from(parent.context)
        val itemView = inflater.inflate(viewType, parent, false)
        itemView.setOnClickListener(itemClickListener)
        val viewHolder = when (viewType) {
            R.layout.grid_section_header -> SectionViewHolder(itemView)
            else                         -> EmojiViewHolder(itemView)
        }
        Trace.endSection()
        return viewHolder
    }

    override fun getItemViewType(position: Int): Int {
        Trace.beginSection("MyAdapter.getItemViewType")
        if (isSection(position)) {
            return R.layout.grid_section_header
        }
        Trace.endSection()
        return R.layout.grid_item_emoji
    }

    private fun isSection(position: Int): Boolean {
        var sectionOffset = 1
        var lastItemInSection: Int
        dataSource.rawData.categories.forEach { category ->
            lastItemInSection = sectionOffset + category.emojis.size - 1
            if (position == sectionOffset - 1) return true
            sectionOffset = lastItemInSection + 2
        }
        return false
    }

    private fun getSectionForAbsoluteIndex(position: Int): Int {
        var sectionOffset = 1
        var lastItemInSection: Int
        var index = 0
        dataSource.rawData.categories.forEach { category ->
            lastItemInSection = sectionOffset + category.emojis.size - 1
            if (position <= lastItemInSection) return index
            sectionOffset = lastItemInSection + 2
            index++
        }
        return index
    }

    private fun getSectionOffset(section: Int): Int {
        // Todo cache this for fast access
        var sectionOffset = 1
        var lastItemInSection: Int
        dataSource.rawData.categories.forEachIndexed { index, category ->
            lastItemInSection = sectionOffset + category.emojis.size - 1
            if (section == index) return sectionOffset
            sectionOffset = lastItemInSection + 2
        }
        return sectionOffset
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Trace.beginSection("MyAdapter.onBindViewHolder")
        val sectionNumber = getSectionForAbsoluteIndex(position)
        if (isSection(position)) {
            holder.bind(dataSource.rawData.categories[sectionNumber].name)
        } else {
            val sectionMojis = dataSource.rawData.categories[sectionNumber].emojis
            val sectionOffset = getSectionOffset(sectionNumber)
            val emoji = sectionMojis[position - sectionOffset]
            val item = dataSource.rawData.emojis[emoji]!!.emoji
            (holder as EmojiViewHolder).data = item
            if (scrollState != ScrollState.SETTLING || !isFastScroll) {
//                    Log.i("PERF","Bind with draw at position:$position")
                holder.bind(item)
            } else {
//                    Log.i("PERF","Bind without draw at position:$position")
                toUpdateWhenNotBusy.add(item to holder)
                holder.bind(null)
            }
        }
        Trace.endSection()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder is EmojiViewHolder) {
            holder.data = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                toUpdateWhenNotBusy.removeIf { it.second == holder }
            } else {
                val index = toUpdateWhenNotBusy.indexOfFirst { it.second == holder }
                if (index != -1) {
                    toUpdateWhenNotBusy.removeAt(index)
                }
            }
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = dataSource.rawData.categories
            .sumOf { emojiCategory -> 1 /* Section */ + emojiCategory.emojis.size }

    abstract class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(s: String?)
    }

    private class EmojiViewHolder(itemView: View) : ViewHolder(itemView) {

        private var emojiView: EmojiDrawView = itemView.findViewById(R.id.grid_item_emoji_text)
        private val placeHolder: View = itemView.findViewById(R.id.grid_item_place_holder)

        var data: String? = null

        override fun bind(s: String?) {
            emojiView.emoji = s
            if (s != null) {
                emojiView.mLayout = getStaticLayoutForEmoji(s)
                emojiView.contentDescription = s
                placeHolder.visibility = View.GONE
//                emojiView.visibility = View.VISIBLE
            } else {
                emojiView.mLayout = null
                placeHolder.visibility = View.VISIBLE
//                emojiView.visibility = View.GONE
            }
        }
    }

    private class SectionViewHolder(itemView: View) : ViewHolder(itemView) {

        private var textView: TextView = itemView.findViewById(R.id.section_header_textview)

        override fun bind(s: String?) {
            textView.text = s
        }
    }

    companion object {
        private val staticLayoutCache = HashMap<String, StaticLayout>()

        private fun getStaticLayoutForEmoji(emoji: String): StaticLayout {
            return staticLayoutCache.getOrPut(emoji) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(emoji, 0, emoji.length, EmojiDrawView.tPaint, EmojiDrawView.emojiSize)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setLineSpacing(0f, 1f)
                            .setIncludePad(true)
                            .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(emoji, EmojiDrawView.tPaint, EmojiDrawView.emojiSize, Layout.Alignment.ALIGN_CENTER, 1f, 0f, true)
                }
            }
        }
    }

    interface InteractionListener {
        fun getCoroutineScope(): CoroutineScope
        fun firstVisibleSectionChange(section: Int)
    }

    // privates

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            scrollState = when (newState) {
                RecyclerView.SCROLL_STATE_IDLE     -> ScrollState.IDLE
                RecyclerView.SCROLL_STATE_SETTLING -> ScrollState.SETTLING
                RecyclerView.SCROLL_STATE_DRAGGING -> ScrollState.DRAGGING
                else                               -> ScrollState.UNKNOWN
            }

            // TODO better
            if (scrollState == ScrollState.IDLE) {
                //
                @Suppress("UNCHECKED_CAST")
                val toUpdate = toUpdateWhenNotBusy.clone() as ArrayList<Pair<String, EmojiViewHolder>>
                toUpdateWhenNotBusy.clear()
                toUpdate.chunked(8).forEach {
                    recyclerView.post {
                        val transition = AutoTransition().apply {
                            duration = 150
                        }
                        for (pair in it) {
                            val holder = pair.second
                            if (pair.first == holder.data) {
                                TransitionManager.beginDelayedTransition(holder.itemView as FrameLayout, transition)
                                val data = holder.data
                                holder.bind(data)
                            }
                        }
                        toUpdateWhenNotBusy.clear()
                    }
                }
            }
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            // Log.i("SCROLL SPEED","scroll speed $dy")
            isFastScroll = abs(dy) > 50
            val visible = (recyclerView.layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition()
            interactionListener?.getCoroutineScope()?.launch {
                val section = getSectionForAbsoluteIndex(visible)
                if (section != currentFirstVisibleSection) {
                    currentFirstVisibleSection = section
                    interactionListener?.getCoroutineScope()?.launch(Dispatchers.Main) {
                        interactionListener?.firstVisibleSectionChange(currentFirstVisibleSection)
                    }
                }
            }
        }
    }
}

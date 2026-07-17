package org.isoron.uhabits.activities.settings

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

class SettingsItemAnimator(
    private val motionEnabled: () -> Boolean
) : DefaultItemAnimator() {
    init {
        addDuration = 220L
        removeDuration = 180L
        moveDuration = 220L
        changeDuration = 0L
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        if (!motionEnabled()) {
            reset(holder)
            return false
        }
        return super.animateAdd(holder)
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        if (!motionEnabled()) {
            reset(holder)
            return false
        }
        return super.animateRemove(holder)
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        if (!motionEnabled()) {
            reset(holder)
            return false
        }
        return super.animateMove(holder, fromX, fromY, toX, toY)
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        super.endAnimation(item)
        reset(item)
    }

    override fun onAnimationFinished(viewHolder: RecyclerView.ViewHolder) {
        reset(viewHolder)
        super.onAnimationFinished(viewHolder)
    }

    private fun reset(holder: RecyclerView.ViewHolder) {
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
    }
}

package com.varol.dizici.ui.browse

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.varol.dizici.R
import com.varol.dizici.data.model.Series

class CardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 200
        private const val CARD_HEIGHT = 300
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val series = item as? Series ?: return
        val card = viewHolder.view as ImageCardView

        card.titleText = series.title
        card.contentText = series.providerName

        Glide.with(card.context)
            .load(series.posterUrl)
            .centerCrop()
            .error(android.R.drawable.ic_menu_gallery)
            .into(card.mainImageView ?: return)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.badgeImage = null
        card.mainImage = null
    }
}

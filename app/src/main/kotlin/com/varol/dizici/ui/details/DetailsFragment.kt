package com.varol.dizici.ui.details

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.app.DetailsSupportFragmentBackgroundController
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.varol.dizici.DiziciApp
import com.varol.dizici.R
import com.varol.dizici.data.model.Episode
import com.varol.dizici.data.model.Series
import com.varol.dizici.data.model.SeriesDetail
import com.varol.dizici.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class DetailsFragment : DetailsSupportFragment() {

    private val app get() = DiziciApp.instance
    private lateinit var bgController: DetailsSupportFragmentBackgroundController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bgController = DetailsSupportFragmentBackgroundController(this)
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Episode) {
                app.currentEpisode = item
                app.currentSources = emptyList()
                app.currentSourceIndex = 0
                startActivity(Intent(requireContext(), PlayerActivity::class.java))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadDetails()
    }

    private fun loadDetails() {
        val series = app.currentSeries ?: run { activity?.finish(); return }

        lifecycleScope.launch {
            try {
                val detail = app.repository.getSeriesDetail(series)
                app.currentSeriesDetail = detail
                buildUI(detail)
                loadBackground(detail.series.posterUrl)
            } catch (e: Exception) {
                buildFallbackUI(series)
            }
        }
    }

    private fun buildUI(detail: SeriesDetail) {
        val presenterSelector = ClassPresenterSelector()

        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(SeriesDetailsPresenter())
        detailsPresenter.backgroundColor = requireContext().getColor(R.color.primary_dark)
        detailsPresenter.onActionClickedListener = OnActionClickedListener { action ->
            if (action.id == ACTION_PLAY) {
                val firstEp = detail.seasons.firstOrNull()?.episodes?.firstOrNull()
                if (firstEp != null) {
                    app.currentEpisode = firstEp
                    app.currentSources = emptyList()
                    startActivity(Intent(requireContext(), PlayerActivity::class.java))
                }
            }
        }

        presenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)
        presenterSelector.addClassPresenter(ListRow::class.java, ListRowPresenter())

        val rowsAdapter = ArrayObjectAdapter(presenterSelector)

        // Dizi bilgi satırı
        val detailsRow = DetailsOverviewRow(detail.series).apply {
            val actionAdapter = ArrayObjectAdapter()
            actionAdapter.add(Action(ACTION_PLAY, getString(R.string.oynat)))
            actionsAdapter = actionAdapter
        }

        Glide.with(this)
            .load(detail.series.posterUrl)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(r: Drawable, t: Transition<in Drawable>?) {
                    detailsRow.imageDrawable = r
                }
                override fun onLoadCleared(p: Drawable?) {}
            })

        rowsAdapter.add(detailsRow)

        // Sezon / bölüm satırları
        val episodePresenter = EpisodeCardPresenter()
        detail.seasons.forEach { season ->
            val episodeAdapter = ArrayObjectAdapter(episodePresenter)
            season.episodes.forEach { episodeAdapter.add(it) }
            val header = HeaderItem("${getString(R.string.sezon)} ${season.number}")
            rowsAdapter.add(ListRow(header, episodeAdapter))
        }

        adapter = rowsAdapter
    }

    private fun buildFallbackUI(series: Series) {
        val presenterSelector = ClassPresenterSelector()
        val detailsPresenter = FullWidthDetailsOverviewRowPresenter(SeriesDetailsPresenter())
        detailsPresenter.backgroundColor = requireContext().getColor(R.color.primary_dark)
        detailsPresenter.onActionClickedListener = OnActionClickedListener {
            val ep = Episode(series.title, 1, 1, series.posterUrl, series.sourceUrl, series.providerName)
            app.currentEpisode = ep
            startActivity(Intent(requireContext(), PlayerActivity::class.java))
        }
        presenterSelector.addClassPresenter(DetailsOverviewRow::class.java, detailsPresenter)

        val rowsAdapter = ArrayObjectAdapter(presenterSelector)
        val detailsRow = DetailsOverviewRow(series).apply {
            val actionAdapter = ArrayObjectAdapter()
            actionAdapter.add(Action(ACTION_PLAY, getString(R.string.oynat)))
            actionsAdapter = actionAdapter
        }
        rowsAdapter.add(detailsRow)
        adapter = rowsAdapter
    }

    private fun loadBackground(url: String?) {
        if (url.isNullOrBlank()) return
        bgController.enableParallax()
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                override fun onResourceReady(
                    r: android.graphics.Bitmap,
                    t: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?
                ) {
                    bgController.coverBitmap = r
                }
                override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
            })
    }

    companion object {
        private const val ACTION_PLAY = 1L
    }
}

class SeriesDetailsPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(vh: ViewHolder, item: Any) {
        val series = item as Series
        vh.title.text = series.title
        vh.subtitle.text = buildString {
            series.year?.let { append(it) }
            series.rating?.let {
                if (isNotEmpty()) append(" • ")
                append("IMDb: $it")
            }
            if (isNotEmpty()) append(" • ")
            append(series.providerName)
        }
        vh.body.text = series.description ?: ""
    }
}

class EpisodeCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
        val card = androidx.leanback.widget.ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(240, 135)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val episode = item as? Episode ?: return
        val card = viewHolder.view as androidx.leanback.widget.ImageCardView
        card.titleText = "${episode.episodeNumber}. ${viewHolder.view.context.getString(R.string.bolum)}"
        card.contentText = episode.title
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {}
}

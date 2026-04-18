package com.varol.dizici.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.varol.dizici.DiziciApp
import com.varol.dizici.R
import com.varol.dizici.data.model.Series
import com.varol.dizici.ui.details.DetailsActivity
import com.varol.dizici.ui.search.SearchActivity
import kotlinx.coroutines.launch

class BrowseFragment : BrowseSupportFragment() {

    private val app get() = DiziciApp.instance
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        loadContent()
    }

    private fun setupUI() {
        title = "Dizici TV"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Series) {
                app.currentSeries = item
                startActivity(Intent(requireContext(), DetailsActivity::class.java))
            }
        }

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
    }

    private fun loadContent() {
        rowsAdapter.clear()

        // Yükleniyor satırı
        val loadingAdapter = ArrayObjectAdapter(cardPresenter)
        rowsAdapter.add(ListRow(HeaderItem("Yükleniyor…"), loadingAdapter))

        lifecycleScope.launch {
            try {
                val categories = app.repository.getHomePage(1)
                rowsAdapter.clear()

                categories.forEach { category ->
                    val listAdapter = ArrayObjectAdapter(cardPresenter)
                    category.series.forEach { listAdapter.add(it) }
                    val header = HeaderItem(category.name)
                    rowsAdapter.add(ListRow(header, listAdapter))
                }

                if (rowsAdapter.size() == 0) {
                    val emptyAdapter = ArrayObjectAdapter(cardPresenter)
                    rowsAdapter.add(ListRow(HeaderItem("İçerik yüklenemedi"), emptyAdapter))
                }
            } catch (e: Exception) {
                rowsAdapter.clear()
                val errAdapter = ArrayObjectAdapter(cardPresenter)
                rowsAdapter.add(ListRow(HeaderItem("Hata: ${e.message}"), errAdapter))
            }
        }
    }
}

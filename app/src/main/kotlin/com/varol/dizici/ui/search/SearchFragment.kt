package com.varol.dizici.ui.search

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.varol.dizici.DiziciApp
import com.varol.dizici.data.model.Series
import com.varol.dizici.ui.browse.CardPresenter
import com.varol.dizici.ui.details.DetailsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val app get() = DiziciApp.instance
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter()
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Series) {
                app.currentSeries = item
                startActivity(Intent(requireContext(), DetailsActivity::class.java))
            }
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        searchJob?.cancel()
        if (newQuery.length < 2) {
            rowsAdapter.clear()
            return true
        }
        searchJob = lifecycleScope.launch {
            delay(400) // Debounce
            performSearch(newQuery)
        }
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        searchJob?.cancel()
        lifecycleScope.launch { performSearch(query) }
        return true
    }

    private suspend fun performSearch(query: String) {
        rowsAdapter.clear()
        try {
            val results = app.repository.search(query)
            if (results.isEmpty()) {
                val emptyAdapter = ArrayObjectAdapter(cardPresenter)
                rowsAdapter.add(ListRow(HeaderItem("Sonuç bulunamadı"), emptyAdapter))
                return
            }

            // Sağlayıcıya göre grupla
            results.groupBy { it.providerName }.forEach { (providerName, seriesList) ->
                val listAdapter = ArrayObjectAdapter(cardPresenter)
                seriesList.forEach { listAdapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(providerName), listAdapter))
            }
        } catch (e: Exception) {
            val errAdapter = ArrayObjectAdapter(cardPresenter)
            rowsAdapter.add(ListRow(HeaderItem("Arama hatası"), errAdapter))
        }
    }
}

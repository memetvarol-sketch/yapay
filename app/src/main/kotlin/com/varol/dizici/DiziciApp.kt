package com.varol.dizici

import android.app.Application
import com.varol.dizici.data.model.Episode
import com.varol.dizici.data.model.Series
import com.varol.dizici.data.model.SeriesDetail
import com.varol.dizici.data.model.StreamSource
import com.varol.dizici.data.repository.ContentRepository

class DiziciApp : Application() {

    val repository = ContentRepository()

    // Aktiviteler arası veri taşımak için basit in-memory store
    var currentSeries: Series? = null
    var currentSeriesDetail: SeriesDetail? = null
    var currentEpisode: Episode? = null
    var currentSources: List<StreamSource> = emptyList()
    var currentSourceIndex: Int = 0

    companion object {
        lateinit var instance: DiziciApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}

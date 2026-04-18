package com.varol.dizici.data.provider

import com.varol.dizici.data.model.Episode
import com.varol.dizici.data.model.HomeCategory
import com.varol.dizici.data.model.Series
import com.varol.dizici.data.model.SeriesDetail
import com.varol.dizici.data.model.StreamSource

interface ContentProvider {
    val name: String

    suspend fun getHomePage(page: Int = 1): List<HomeCategory>
    suspend fun search(query: String): List<Series>
    suspend fun getSeriesDetail(series: Series): SeriesDetail
    suspend fun getStreamSources(episode: Episode): List<StreamSource>
}

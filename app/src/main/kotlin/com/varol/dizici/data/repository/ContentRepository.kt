package com.varol.dizici.data.repository

import com.varol.dizici.data.model.*
import com.varol.dizici.data.provider.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ContentRepository {

    val providers: List<ContentProvider> = listOf(
        // Mevcut provider'lar
        DiziKoreaProvider(),
        AsyaWatchProvider(),
        KoreanTurkProvider(),
        // Asya / Kore içerikli siteler
        AsyaFanatiklerimProvider(),
        AsyaMinikProvider(),
        AsyaAnimeleriProvider(),
        DiziAsyaProvider(),
        DiziAsiaProvider(),
        BirAsyaDiziProvider(),
        DiziGomProvider(),
        HintDiziProvider(),
        WebDramaTurkeyProvider(),
        // Genel Türk dizi siteleri
        SezonlukDiziProvider(),
        YabanciDiziProvider(),
        DiziBoxProvider(),
        DizillaProvider(),
        DiziPalProvider(),
        DiziPalOrijinalProvider(),
        TvDizilerProvider(),
        DiziWatchProvider(),
        DiziFilmOrgProvider(),
        DiziLifeProvider(),
        DiziMagProvider(),
        DiziMomProvider(),
        DiziYouProvider(),
        DdiziProvider(),
        TurkdizileriProvider()
    )

    suspend fun getHomePage(page: Int = 1): List<HomeCategory> = coroutineScope {
        providers.map { provider ->
            async {
                try {
                    provider.getHomePage(page)
                } catch (e: Exception) {
                    android.util.Log.e("DiziciRepo", "${provider.name} home failed", e)
                    listOf(
                        HomeCategory(
                            name = "${provider.name} hata: ${e.javaClass.simpleName} — ${e.message?.take(80)}",
                            series = emptyList()
                        )
                    )
                }
            }
        }.awaitAll().flatten()
    }

    suspend fun search(query: String): List<Series> = coroutineScope {
        providers.map { provider ->
            async {
                try { provider.search(query) } catch (e: Exception) { emptyList() }
            }
        }.awaitAll().flatten()
    }

    suspend fun getSeriesDetail(series: Series): SeriesDetail {
        return series.let { s ->
            val provider = providers.find { it.name == s.providerName }
                ?: providers.first()
            provider.getSeriesDetail(s)
        }
    }

    /**
     * Bir bölüm için tüm sağlayıcılardan kaynak topla.
     * Ana kaynak önce gelir, diğerleri arka planda aranır.
     */
    @Volatile var lastPrimaryError: Exception? = null

    suspend fun getAllSources(
        episode: Episode,
        seriesTitle: String
    ): List<StreamSource> = coroutineScope {
        lastPrimaryError = null
        val allSources = mutableListOf<StreamSource>()

        // Asıl sağlayıcı
        val primaryProvider = providers.find { it.name == episode.providerName }
        if (primaryProvider != null) {
            try {
                allSources.addAll(primaryProvider.getStreamSources(episode))
            } catch (e: Exception) {
                android.util.Log.e("DiziciRepo", "primary ${primaryProvider.name} stream failed", e)
                lastPrimaryError = e
            }
        }

        // Diğer sağlayıcılarda eşleşen bölümü ara
        val otherProviders = providers.filter { it.name != episode.providerName }
        val otherSources = otherProviders.map { provider ->
            async {
                try {
                    val matchingEpisode = findMatchingEpisode(provider, seriesTitle, episode)
                    if (matchingEpisode != null) {
                        provider.getStreamSources(matchingEpisode)
                    } else emptyList()
                } catch (e: Exception) { emptyList() }
            }
        }.awaitAll().flatten()

        allSources.addAll(otherSources)
        allSources
    }

    private suspend fun findMatchingEpisode(
        provider: ContentProvider,
        seriesTitle: String,
        episode: Episode
    ): Episode? {
        return try {
            val results = provider.search(seriesTitle)
            val match = results.firstOrNull { s ->
                s.title.lowercase().contains(seriesTitle.lowercase().take(10))
            } ?: return null

            val detail = provider.getSeriesDetail(match)
            detail.seasons
                .find { it.number == episode.seasonNumber }
                ?.episodes
                ?.find { it.episodeNumber == episode.episodeNumber }
        } catch (e: Exception) {
            null
        }
    }
}

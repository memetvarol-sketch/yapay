package com.varol.dizici.data.provider

import com.varol.dizici.data.extractor.VideoExtractor
import com.varol.dizici.data.model.*
import com.varol.dizici.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup

class KoreanTurkProvider : ContentProvider {

    override val name = "KoreanTurk"

    private val baseUrl = "https://www.koreanturk.net"
    private val client = HttpClient.client

    private val categories = listOf(
        "Son Eklenenler" to "$baseUrl/bolumler/page/",
        "Romantik" to "$baseUrl/konu/romantik/",
        "Dram" to "$baseUrl/konu/dram/",
        "Komedi" to "$baseUrl/konu/komedi/",
        "Aksiyon" to "$baseUrl/konu/aksiyon/"
    )

    override suspend fun getHomePage(page: Int): List<HomeCategory> = withContext(Dispatchers.IO) {
        categories.mapNotNull { (catName, catUrl) ->
            try {
                val url = if (catUrl.contains("/page/")) "$catUrl$page" else catUrl
                val html = get(url)
                val doc = Jsoup.parse(html)
                val series = mutableListOf<Series>()

                // Sayfalanmış liste
                doc.select("div.standartbox").forEach { el ->
                    val link = el.selectFirst("a") ?: return@forEach
                    var seriesUrl = link.attr("href")
                    // Bölüm URL'inden dizi URL'sine dönüştür
                    seriesUrl = seriesUrl.replace(Regex("-[0-9]+(-final)?-bolum-izle\\.html"), ".html")
                    val title = el.selectFirst("h2")?.ownText()?.trim() ?: return@forEach
                    val poster = el.selectFirst("img")?.attr("src")

                    if (!series.any { it.id == seriesUrl }) {
                        series.add(Series(
                            id = seriesUrl,
                            title = title,
                            posterUrl = poster,
                            description = null,
                            year = null,
                            rating = null,
                            sourceUrl = seriesUrl,
                            providerName = name
                        ))
                    }
                }

                // Kategori sayfası
                if (series.isEmpty()) {
                    doc.select("div.resimcik, div.kategori-item").forEach { el ->
                        val link = el.selectFirst("a") ?: return@forEach
                        val url2 = link.attr("href")
                        val title = el.selectFirst("h2, h3")?.text()?.trim()
                            ?: link.attr("title")
                            ?: el.selectFirst("img")?.attr("alt")
                            ?: return@forEach
                        val poster = el.selectFirst("img")?.attr("src")

                        series.add(Series(
                            id = url2,
                            title = title,
                            posterUrl = poster,
                            description = null,
                            year = null,
                            rating = null,
                            sourceUrl = url2,
                            providerName = name
                        ))
                    }
                }

                if (series.isNotEmpty()) HomeCategory(catName, series.take(20)) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun search(query: String): List<Series> = withContext(Dispatchers.IO) {
        // KoreanTurk arama motoru zayıf — kategori sayfalarını tarayarak filtrele
        try {
            val html = get("$baseUrl/bolumler/page/1")
            val doc = Jsoup.parse(html)
            val results = mutableListOf<Series>()
            val lowerQuery = query.lowercase()

            doc.select("div.standartbox").forEach { el ->
                val link = el.selectFirst("a") ?: return@forEach
                val title = el.selectFirst("h2")?.ownText()?.trim() ?: return@forEach
                if (!title.lowercase().contains(lowerQuery)) return@forEach

                var seriesUrl = link.attr("href")
                    .replace(Regex("-[0-9]+(-final)?-bolum-izle\\.html"), ".html")
                val poster = el.selectFirst("img")?.attr("src")

                if (results.none { it.id == seriesUrl }) {
                    results.add(Series(
                        id = seriesUrl,
                        title = title,
                        posterUrl = poster,
                        description = null,
                        year = null,
                        rating = null,
                        sourceUrl = seriesUrl,
                        providerName = name
                    ))
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getSeriesDetail(series: Series): SeriesDetail = withContext(Dispatchers.IO) {
        val html = get(series.sourceUrl)
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h3")?.text()?.trim() ?: series.title
        val poster = doc.selectFirst("div.resimcik img")?.attr("src") ?: series.posterUrl
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")

        val updatedSeries = series.copy(title = title, posterUrl = poster, description = description)

        val episodeMap = mutableMapOf<Int, MutableList<Episode>>()
        doc.select("div.standartbox a").forEach { link ->
            val epUrl = link.attr("href")
            val epTitle = link.selectFirst("h2")?.text()?.trim() ?: return@forEach
            val seasonNum = Regex("""(\d+)\.?\s*[Ss]ezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episodeNum = Regex("""(\d+)\.?\s*[Bb]ölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").findAll(epTitle).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: episodeMap.getOrPut(seasonNum) { mutableListOf() }.size + 1

            episodeMap.getOrPut(seasonNum) { mutableListOf() }.add(
                Episode(
                    title = epTitle,
                    seasonNumber = seasonNum,
                    episodeNumber = episodeNum,
                    thumbnailUrl = null,
                    sourceUrl = epUrl,
                    providerName = name
                )
            )
        }

        val seasons = if (episodeMap.isNotEmpty()) {
            episodeMap.entries.sortedBy { it.key }.map { (num, eps) ->
                Season(num, eps.sortedBy { it.episodeNumber })
            }
        } else {
            listOf(Season(1, listOf(Episode(title, 1, 1, poster, series.sourceUrl, name))))
        }

        SeriesDetail(updatedSeries, seasons)
    }

    override suspend fun getStreamSources(episode: Episode): List<StreamSource> = withContext(Dispatchers.IO) {
        try {
            val html = get(episode.sourceUrl)
            val doc = Jsoup.parse(html)
            val sources = mutableListOf<StreamSource>()

            doc.select("div.filmcik div.tab-pane iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.startsWith("http")) {
                    sources.addAll(VideoExtractor.extract(src, "$baseUrl/", name))
                }
            }

            doc.select("div.filmcik div.tab-pane a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http") && sources.none { it.url == href }) {
                    sources.addAll(VideoExtractor.extract(href, "$baseUrl/", name))
                }
            }

            sources
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VideoExtractor.UA)
            .header("Referer", "$baseUrl/")
            .build()
        return client.newCall(request).execute().body?.string() ?: ""
    }
}

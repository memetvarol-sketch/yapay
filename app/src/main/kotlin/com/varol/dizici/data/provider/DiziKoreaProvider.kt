package com.varol.dizici.data.provider

import com.varol.dizici.data.extractor.VideoExtractor
import com.varol.dizici.data.model.*
import com.varol.dizici.data.network.HttpClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

class DiziKoreaProvider : ContentProvider {

    override val name = "DiziKorea"

    private val baseUrl = "https://dizikorea.pw"
    private val client = HttpClient.client
    private val gson = Gson()

    private val categories = listOf(
        "Kore Dizileri" to "$baseUrl/category/kore-dizileri/page/",
        "Kore Filmleri" to "$baseUrl/category/kore-filmleri/page/",
        "Çin Dizileri" to "$baseUrl/category/cin-dizileri/page/",
        "Tayland Dizileri" to "$baseUrl/category/tayland-dizileri/page/"
    )

    override suspend fun getHomePage(page: Int): List<HomeCategory> = withContext(Dispatchers.IO) {
        categories.mapNotNull { (catName, catUrl) ->
            try {
                val html = get("$catUrl$page")
                val doc = Jsoup.parse(html)
                val series = doc.select("div.poster-long").mapNotNull { el ->
                    val link = el.selectFirst("a") ?: return@mapNotNull null
                    val url = link.attr("href")
                    val title = el.selectFirst("h2")?.text() ?: return@mapNotNull null
                    val poster = el.selectFirst("img")?.attr("data-src")
                        ?: el.selectFirst("img")?.attr("src")
                    val rating = el.selectFirst("span.rating")?.text()?.toDoubleOrNull()

                    Series(
                        id = url,
                        title = title,
                        posterUrl = poster,
                        description = null,
                        year = null,
                        rating = rating,
                        sourceUrl = url,
                        providerName = name
                    )
                }
                if (series.isNotEmpty()) HomeCategory(catName, series) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun search(query: String): List<Series> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = """{"query":"$query"}"""
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/search")
                .post(body)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/json")
                .header("Referer", "$baseUrl/")
                .header("User-Agent", VideoExtractor.UA)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()

            val searchResponse = gson.fromJson(responseBody, DiziKoreaSearch::class.java)
            val doc = Jsoup.parse(searchResponse.theme ?: return@withContext emptyList())

            doc.select("ul li").mapNotNull { el ->
                val link = el.selectFirst("a") ?: return@mapNotNull null
                val url = link.attr("href")
                if (!url.contains("/dizi/") && !url.contains("/film/")) return@mapNotNull null

                val title = link.text().ifBlank {
                    el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                }
                val poster = el.selectFirst("img")?.attr("src")

                Series(
                    id = url,
                    title = title,
                    posterUrl = poster,
                    description = null,
                    year = null,
                    rating = null,
                    sourceUrl = url,
                    providerName = name
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getSeriesDetail(series: Series): SeriesDetail = withContext(Dispatchers.IO) {
        val html = get(series.sourceUrl)
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1 a")?.text() ?: series.title
        val poster = doc.selectFirst("div.series-profile-image img")?.attr("src") ?: series.posterUrl
        val year = doc.selectFirst("h1 span")?.text()
            ?.replace(Regex("[()]"), "")?.trim()
        val description = doc.selectFirst("div.series-profile-summary p")?.text()
        val ratingText = doc.selectFirst("span.color-imdb")?.text()
        val rating = Regex("""[\d.]+""").find(ratingText ?: "")?.value?.toDoubleOrNull()

        val updatedSeries = series.copy(
            title = title,
            posterUrl = poster,
            year = year,
            description = description,
            rating = rating
        )

        val seasons = mutableListOf<Season>()
        val seasonDivs = doc.select("div.series-profile-episode-list")

        if (seasonDivs.isEmpty()) {
            // Tek sezonlu yapı
            val episodes = parseEpisodes(doc, 1, series.sourceUrl)
            if (episodes.isNotEmpty()) seasons.add(Season(1, episodes))
        } else {
            seasonDivs.forEach { seasonDiv ->
                val seasonId = seasonDiv.id().split("-").lastOrNull()?.toIntOrNull() ?: 1
                val episodes = parseEpisodes(seasonDiv, seasonId, series.sourceUrl)
                if (episodes.isNotEmpty()) seasons.add(Season(seasonId, episodes))
            }
        }

        if (seasons.isEmpty()) {
            // Film ya da tek bölümlük içerik
            seasons.add(Season(1, listOf(
                Episode(title, 1, 1, poster, series.sourceUrl, name)
            )))
        }

        SeriesDetail(updatedSeries, seasons.sortedBy { it.number })
    }

    private fun parseEpisodes(container: org.jsoup.nodes.Element, seasonNumber: Int, baseSeriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        container.select("li").forEach { li ->
            val link = li.selectFirst("h6 a") ?: li.selectFirst("a") ?: return@forEach
            val epUrl = link.attr("href").ifBlank { return@forEach }
            val epTitle = link.text()
            val epNumText = li.selectFirst("a.truncate")?.text() ?: epTitle
            val epNum = Regex("""(\d+)\.?\s*[Bb]ölüm""").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: episodes.size + 1

            episodes.add(Episode(
                title = epTitle.ifBlank { "${seasonNumber}. Sezon ${epNum}. Bölüm" },
                seasonNumber = seasonNumber,
                episodeNumber = epNum,
                thumbnailUrl = null,
                sourceUrl = epUrl,
                providerName = name
            ))
        }
        return episodes.sortedBy { it.episodeNumber }
    }

    override suspend fun getStreamSources(episode: Episode): List<StreamSource> = withContext(Dispatchers.IO) {
        try {
            val html = get(episode.sourceUrl)
            val doc = Jsoup.parse(html)
            val sources = mutableListOf<StreamSource>()

            val hhsButtons = doc.select("div.series-watch-alternatives button[data-hhs]")
            val iframes = doc.select("iframe[src]")
            android.util.Log.i("DiziKorea", "episode url=${episode.sourceUrl} htmlLen=${html.length} hhs=${hhsButtons.size} iframes=${iframes.size}")

            // data-hhs attribute'ündeki iframe URL'leri
            hhsButtons.forEach { btn ->
                val iframeUrl = btn.attr("data-hhs")
                android.util.Log.i("DiziKorea", "hhs iframe: $iframeUrl")
                if (iframeUrl.isNotBlank()) {
                    val ext = VideoExtractor.extract(iframeUrl, "$baseUrl/", name)
                    android.util.Log.i("DiziKorea", "extracted ${ext.size} sources from $iframeUrl")
                    sources.addAll(ext)
                }
            }

            // Doğrudan iframe'ler
            if (sources.isEmpty()) {
                iframes.forEach { iframe ->
                    val src = iframe.attr("src")
                    android.util.Log.i("DiziKorea", "iframe src: $src")
                    if (src.startsWith("http")) {
                        sources.addAll(VideoExtractor.extract(src, "$baseUrl/", name))
                    }
                }
            }

            // Fallback: sayfadaki tüm URL'lerde bilinen extractor host'ları ara
            if (sources.isEmpty()) {
                val knownHosts = listOf("videoseyred.in", "yourupload.com", "drive.google.com", "sbembed", "sbfull", "vidmoly")
                val urlRegex = Regex("""https?://[^\s"'<>]+""")
                val candidates = urlRegex.findAll(html)
                    .map { it.value }
                    .filter { u -> knownHosts.any { u.contains(it) } }
                    .distinct()
                    .toList()
                android.util.Log.i("DiziKorea", "fallback candidates=${candidates.size}: $candidates")
                candidates.forEach { url ->
                    sources.addAll(VideoExtractor.extract(url, "$baseUrl/", name))
                }
            }

            android.util.Log.i("DiziKorea", "total sources=${sources.size}")
            sources
        } catch (e: Exception) {
            android.util.Log.e("DiziKorea", "getStreamSources failed", e)
            throw RuntimeException("DiziKorea: ${e.javaClass.simpleName} ${e.message}", e)
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

    private data class DiziKoreaSearch(val theme: String?)
}

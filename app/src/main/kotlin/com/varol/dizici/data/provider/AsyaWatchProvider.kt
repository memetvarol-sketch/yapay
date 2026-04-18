package com.varol.dizici.data.provider

import com.varol.dizici.data.extractor.VideoExtractor
import com.varol.dizici.data.model.*
import com.varol.dizici.data.network.HttpClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.Base64

class AsyaWatchProvider : ContentProvider {

    override val name = "AsyaWatch"

    private val baseUrl = "https://asyawatch.com"
    private val client = HttpClient.client
    private val gson = Gson()

    private val commonHeaders = mapOf(
        "User-Agent" to VideoExtractor.UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Referer" to "$baseUrl/"
    )

    private val categories = listOf(
        "Yeni Diziler" to "series",
        "Yeni Filmler" to "movies",
        "Kore Draması" to "series&categoryIdsComma=48",
        "Romantik" to "series&categoryIdsComma=65",
        "Aksiyon" to "series&categoryIdsComma=59"
    )

    override suspend fun getHomePage(page: Int): List<HomeCategory> = withContext(Dispatchers.IO) {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        categories.mapNotNull { (catName, catParam) ->
            try {
                val isMovie = catParam == "movies"
                val endpoint = if (isMovie) "findMovies" else "findSeries"
                val categoryId = if (catParam.contains("categoryIdsComma="))
                    catParam.substringAfter("categoryIdsComma=") else ""

                val url = "$baseUrl/api/bg/$endpoint?releaseYearStart=1900&releaseYearEnd=$currentYear" +
                        "&imdbPointMin=1&imdbPointMax=10&categoryIdsComma=$categoryId" +
                        "&countryIdsComma=&orderType=date_desc&languageId=-1" +
                        "&currentPage=$page&currentPageCount=12&queryStr=&categorySlugsComma=&countryCodesComma="

                val json = postDecoded(url) ?: return@mapNotNull null
                val root = JsonParser.parseString(json).asJsonObject
                val results = root.getAsJsonArray("result") ?: return@mapNotNull null

                val series = results.mapNotNull { el ->
                    try {
                        val obj = el.asJsonObject
                        val slug = obj.getString("usedSlug") ?: return@mapNotNull null
                        val title = obj.getString("originalTitle") ?: obj.getString("cultureTitle") ?: return@mapNotNull null
                        val poster = obj.getString("posterUrl")?.let { fixCdnUrl(it) }
                        val year = obj.getString("releaseYear")
                        val rating = obj.get("imdbPoint")?.takeIf { !it.isJsonNull }?.asDouble
                        val type = if (isMovie) ContentType.MOVIE else ContentType.SERIES
                        val path = if (isMovie) "film" else "dizi"

                        Series(
                            id = slug,
                            title = title,
                            posterUrl = poster,
                            description = null,
                            year = year,
                            rating = rating,
                            sourceUrl = "$baseUrl/$path/$slug",
                            providerName = name,
                            type = type
                        )
                    } catch (e: Exception) { null }
                }

                if (series.isNotEmpty()) HomeCategory(catName, series) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun search(query: String): List<Series> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/bg/searchcontent?searchterm=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val json = postDecoded(url) ?: return@withContext emptyList()
            val root = JsonParser.parseString(json).asJsonObject
            val results = root.getAsJsonArray("result") ?: return@withContext emptyList()

            results.mapNotNull { el ->
                try {
                    val obj = el.asJsonObject
                    val slug = obj.getString("usedSlug") ?: return@mapNotNull null
                    val title = obj.getString("originalTitle") ?: obj.getString("cultureTitle") ?: return@mapNotNull null
                    if (slug.contains("/seri-filmler/")) return@mapNotNull null
                    val poster = obj.getString("posterUrl")?.let { fixCdnUrl(it) }
                    val isMovie = obj.getString("contentTypeName")?.contains("Film", ignoreCase = true) == true
                    val path = if (isMovie) "film" else "dizi"
                    val type = if (isMovie) ContentType.MOVIE else ContentType.SERIES

                    Series(
                        id = slug,
                        title = title,
                        posterUrl = poster,
                        description = null,
                        year = null,
                        rating = null,
                        sourceUrl = "$baseUrl/$path/$slug",
                        providerName = name,
                        type = type
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getSeriesDetail(series: Series): SeriesDetail = withContext(Dispatchers.IO) {
        val html = get(series.sourceUrl)
        val doc = Jsoup.parse(html)

        val nextDataEl = doc.selectFirst("script#__NEXT_DATA__")
        val nextDataJson = nextDataEl?.data() ?: return@withContext SeriesDetail(series, emptyList())

        val pageProps = JsonParser.parseString(nextDataJson)
            .asJsonObject?.getAsJsonObject("props")
            ?.getAsJsonObject("pageProps")

        val secureDataB64 = pageProps?.getString("secureData") ?: return@withContext SeriesDetail(series, emptyList())
        val secureJson = decodeBase64(secureDataB64) ?: return@withContext SeriesDetail(series, emptyList())
        val root = JsonParser.parseString(secureJson).asJsonObject

        val contentItem = root.getAsJsonObject("contentItem")
        val originalTitle = contentItem?.getString("originalTitle") ?: series.title
        val cultureTitle = contentItem?.getString("cultureTitle")
        val title = if (!cultureTitle.isNullOrBlank() && cultureTitle != originalTitle)
            "$originalTitle ($cultureTitle)" else originalTitle
        val poster = contentItem?.getString("posterUrl")?.let { fixCdnUrl(it) } ?: series.posterUrl
        val description = contentItem?.getString("description")
        val year = contentItem?.getString("releaseYear")
        val rating = contentItem?.get("imdbPoint")?.takeIf { !it.isJsonNull }?.asDouble

        val updatedSeries = series.copy(
            title = title,
            posterUrl = poster,
            description = description,
            year = year,
            rating = rating
        )

        val relatedResults = root.getAsJsonObject("relatedResults")
        val seasons = mutableListOf<Season>()

        val seasonsData = relatedResults?.getAsJsonObject("getSerieSeasonAndEpisodes")
            ?.getAsJsonArray("seasons")

        seasonsData?.forEach { seasonEl ->
            val seasonObj = seasonEl.asJsonObject
            val seasonNo = seasonObj.get("seasonNo")?.asInt ?: return@forEach
            val episodesArr = seasonObj.getAsJsonArray("episodes") ?: return@forEach

            val episodes = episodesArr.mapNotNull { epEl ->
                try {
                    val epObj = epEl.asJsonObject
                    val epNo = epObj.get("episodeNo")?.asInt ?: return@mapNotNull null
                    val epText = epObj.getString("epText") ?: "$epNo. Bölüm"
                    val epSlug = epObj.getString("usedSlug") ?: return@mapNotNull null
                    val seriesSlug = series.sourceUrl.substringAfterLast("/")
                    Episode(
                        title = epText,
                        seasonNumber = seasonNo,
                        episodeNumber = epNo,
                        thumbnailUrl = null,
                        sourceUrl = "$baseUrl/dizi/$seriesSlug/$epSlug",
                        providerName = name
                    )
                } catch (e: Exception) { null }
            }.sortedBy { it.episodeNumber }

            if (episodes.isNotEmpty()) seasons.add(Season(seasonNo, episodes))
        }

        if (seasons.isEmpty() && series.type == ContentType.MOVIE) {
            seasons.add(Season(1, listOf(Episode(title, 1, 1, poster, series.sourceUrl, name))))
        }

        SeriesDetail(updatedSeries, seasons.sortedBy { it.number })
    }

    override suspend fun getStreamSources(episode: Episode): List<StreamSource> = withContext(Dispatchers.IO) {
        try {
            val html = get(episode.sourceUrl)
            val doc = Jsoup.parse(html)
            val nextDataEl = doc.selectFirst("script#__NEXT_DATA__")
            val nextDataJson = nextDataEl?.data() ?: return@withContext emptyList()

            val pageProps = JsonParser.parseString(nextDataJson)
                .asJsonObject?.getAsJsonObject("props")
                ?.getAsJsonObject("pageProps")

            val secureDataB64 = pageProps?.getString("secureData") ?: return@withContext emptyList()
            val secureJson = decodeBase64(secureDataB64) ?: return@withContext emptyList()
            val root = JsonParser.parseString(secureJson).asJsonObject
            val relatedResults = root.getAsJsonObject("relatedResults") ?: return@withContext emptyList()

            val sourcesArr = relatedResults.getAsJsonObject("getEpisodeSources")
                ?.getAsJsonArray("result")
                ?: relatedResults.getAsJsonObject("getMoviePartSourcesById_1")
                    ?.getAsJsonArray("result")
                ?: return@withContext emptyList()

            val sources = mutableListOf<StreamSource>()
            sourcesArr.forEach { srcEl ->
                try {
                    val srcObj = srcEl.asJsonObject
                    val sourceContent = srcObj.getString("sourceContent") ?: return@forEach
                    val qualityName = srcObj.getString("qualityName")
                    val srcDoc = Jsoup.parse(sourceContent)
                    val iframeSrc = srcDoc.selectFirst("iframe")?.attr("src") ?: return@forEach
                    if (iframeSrc.startsWith("http")) {
                        val extracted = VideoExtractor.extract(iframeSrc, "$baseUrl/", name)
                        extracted.forEach { s ->
                            sources.add(s.copy(quality = qualityName ?: s.quality))
                        }
                    }
                } catch (e: Exception) { /* skip */ }
            }

            sources
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun postDecoded(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .post("".toRequestBody())
                .apply { commonHeaders.forEach { (k, v) -> header(k, v) } }
                .build()
            val responseBody = client.newCall(request).execute().body?.string() ?: return null
            val json = JsonParser.parseString(responseBody).asJsonObject
            val encoded = json.getString("response") ?: return null
            decodeBase64(encoded)
        } catch (e: Exception) { null }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .apply { commonHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(request).execute().body?.string() ?: ""
    }

    private fun decodeBase64(encoded: String): String? {
        return try {
            val bytes = Base64.getDecoder().decode(encoded)
            String(bytes, Charsets.ISO_8859_1).let { iso ->
                // ISO-8859-1 → UTF-8 dönüşümü
                String(iso.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            }
        } catch (e: Exception) { null }
    }

    private fun fixCdnUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (!url.startsWith("http")) "$baseUrl$url"
        else url
    }

    private fun JsonObject.getString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}

package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.util.Calendar

class AsyaWatch : MainAPI() {
    override var mainUrl        = "https://asyawatch.com"
    override var name           = "AsyaWatch"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Yeni Eklenen Filmler",
        "59" to "Aksiyon Film",
        "48" to "Dram Film",
        "45" to "Komedi Film",
        "65" to "Romantik Film",
        "68" to "Gerilim Film",
        "" to "Yeni Eklenen Diziler",
        "9"  to "Aksiyon Dizi",
        "2"  to "Dram Dizi",
        "4"  to "Komedi Dizi",
        "7"  to "Romantik Dizi",
        "3"  to "Gizem Dizi",
        "18" to "Gerilim Dizi",
    )

    private fun objectMapper() = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Referer" to "https://asyawatch.com/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val yil = Calendar.getInstance().get(Calendar.YEAR)
        var url = "$mainUrl/api/bg/findMovies?releaseYearStart=1900&releaseYearEnd=$yil&imdbPointMin=1&imdbPointMax=10&categoryIdsComma=KATID&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=${page}&currentPageCount=12&queryStr=&categorySlugsComma=&countryCodesComma="
        if (request.name.contains("Dizi")) {
            url = url.replace("findMovies", "findSeries")
        }

        val om = objectMapper()
        val catDocument = app.post(url.replace("KATID", request.data), headers = commonHeaders, referer = "${mainUrl}/")
        val searchResult: SearchResult = om.readValue(catDocument.toString())
        val decodedSearch = base64Decode(searchResult.response.toString())
        val converted = String(decodedSearch.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        val listItems: ListItems = om.readValue(converted)
        val home = listItems.result.map { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun ContentItem.toMainPageResult(): SearchResponse {
        val title = this.originalTitle
        val href = fixUrlNull(this.usedSlug)
        val posterUrl = fixUrlNull(this.posterUrl.toString().replace("images-macellan-online.cdn.ampproject.org/i/s/", ""))
        val score = this.imdbPoint

        return if (href!!.contains("/dizi/")) {
            newTvSeriesSearchResponse(title!!, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title!!, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val om = objectMapper()
        val searchReq = app.post("${mainUrl}/api/bg/searchcontent?searchterm=$query", headers = commonHeaders, referer = "${mainUrl}/")
        val searchResult: SearchResult = om.readValue(searchReq.toString())
        val decodedSearch = base64Decode(searchResult.response.toString())
        val converted = String(decodedSearch.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        val contentJson: SearchData = om.readValue(converted)

        if (contentJson.state != true) throw ErrorLoadingException("Invalid Json response")

        val veriler = mutableListOf<SearchResponse>()
        contentJson.result?.forEach {
            val name = it.title.toString()
            val link = fixUrl(it.slug.toString())
            val posterLink = it.poster.toString().replace("images-macellan-online.cdn.ampproject.org/i/s/", "")
            val type = it.type.toString()
            if (!link.contains("/seri-filmler/")) {
                veriler.add(toSearchResponse(name, link, posterLink, type))
            }
        }
        return veriler
    }

    private fun toSearchResponse(ad: String, link: String, posterLink: String, type: String): SearchResponse {
        return if (type == "Movies") {
            newMovieSearchResponse(ad, link, TvType.Movie) { this.posterUrl = posterLink }
        } else {
            newTvSeriesSearchResponse(ad, link, TvType.TvSeries) { this.posterUrl = posterLink }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val om = objectMapper()
        val encodedDoc = app.get(url).document
        val script = encodedDoc.selectFirst("script#__NEXT_DATA__")?.data()
        val secureData = om.readTree(script).get("props").get("pageProps").get("secureData")
        val decodedJson = base64Decode(secureData.toString().replace("\"", ""))
        val converted = String(decodedJson.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        val root: Root = om.readValue(converted)
        val item = root.contentItem

        val orgTitle  = item.originalTitle
        val cultTitle = item.cultureTitle.toString()
        val title     = if (orgTitle == cultTitle || cultTitle.isEmpty()) orgTitle else "$orgTitle - $cultTitle"
        val poster    = fixUrlNull(item.posterUrl?.replace("images-macellan-online.cdn.ampproject.org/i/s/", ""))
        val description = item.description
        val year      = item.releaseYear
        val tags      = item.categories?.split(",")
        val rating    = item.imdbPoint
        val duration  = item.totalMinutes
        val actors    = root.relatedResults.getMovieCastsById?.result?.map {
            Actor(it.name!!, fixUrlNull(it.castImage?.replace("images-macellan-online.cdn.ampproject.org/i/s/", "")))
        }

        var trailer = ""
        if (root.relatedResults.getContentTrailers?.state == true && root.relatedResults.getContentTrailers.result?.isNotEmpty() == true) {
            trailer = root.relatedResults.getContentTrailers.result[0].rawUrl.toString()
        }

        if (root.relatedResults.getSerieSeasonAndEpisodes != null) {
            val eps = mutableListOf<Episode>()
            root.relatedResults.getSerieSeasonAndEpisodes.seasons?.forEach { season ->
                season.episodes?.forEach { ep ->
                    eps.add(newEpisode(fixUrlNull(ep.usedSlug)) {
                        this.name    = ep.epText
                        this.season  = season.seasonNo
                        this.episode = ep.episodeNo
                    })
                }
            }
            return newTvSeriesLoadResponse(title!!, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title!!, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(rating)
            this.duration  = duration
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ASW", "data » $data")
        val om = objectMapper()
        val encodedDoc = app.get(data).document
        val script = encodedDoc.selectFirst("script#__NEXT_DATA__")?.data()
        val secureData = om.readTree(script).get("props").get("pageProps").get("secureData")
        val decodedJson = base64Decode(secureData.toString().replace("\"", ""))
        val converted = String(decodedJson.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        val root: Root = om.readValue(converted)

        val iframes = mutableListOf<SourceItem>()
        val relatedResults = root.relatedResults

        if (data.contains("/dizi/")) {
            if (relatedResults.getEpisodeSources?.state == true) {
                relatedResults.getEpisodeSources.result?.forEach {
                    iframes.add(SourceItem(it.sourceContent.toString(), it.qualityName.toString()))
                }
            }
        } else {
            if (relatedResults.getMoviePartsById?.state == true) {
                relatedResults.getMoviePartsById.result?.forEach { part ->
                    om.readTree(converted).get("RelatedResults")
                        .get("getMoviePartSourcesById_${part.id}")
                        .get("result").forEach { src ->
                            iframes.add(SourceItem(src.get("source_content").asText(), src.get("quality_name").asText()))
                        }
                }
            }
        }

        iframes.forEach { item ->
            val iframe = fixUrlNull(Jsoup.parse(item.sourceContent).select("iframe").attr("src"))
            Log.d("ASW", "iframe » $iframe")
            loadExtractor(iframe!!, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}

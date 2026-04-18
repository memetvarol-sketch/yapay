package com.varol.dizici.data.provider

import com.varol.dizici.data.extractor.VideoExtractor
import com.varol.dizici.data.model.*
import com.varol.dizici.data.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class GenericDiziProvider : ContentProvider {

    abstract override val name: String
    abstract val baseUrl: String

    open val searchPath: String get() = "/?s="
    open val homeCategories: List<Pair<String, String>>
        get() = listOf("Son Eklenenler" to "$baseUrl/page/")

    private val client = HttpClient.client

    override suspend fun getHomePage(page: Int): List<HomeCategory> =
        withContext(Dispatchers.IO) {
            homeCategories.mapNotNull { (catName, catUrl) ->
                try {
                    val url = if (catUrl.endsWith("/page/")) "$catUrl$page" else catUrl
                    val doc = getDoc(url)
                    val series = parseCards(doc)
                    if (series.isNotEmpty()) HomeCategory(catName, series.take(20)) else null
                } catch (e: Exception) {
                    android.util.Log.e("Provider.$name", "home failed: ${e.message}")
                    null
                }
            }
        }

    override suspend fun search(query: String): List<Series> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                parseCards(getDoc("$baseUrl$searchPath$encoded"))
            } catch (e: Exception) { emptyList() }
        }

    override suspend fun getSeriesDetail(series: Series): SeriesDetail =
        withContext(Dispatchers.IO) {
            val doc = getDoc(series.sourceUrl)
            val title = docTitle(doc) ?: series.title
            val poster = docPoster(doc) ?: series.posterUrl
            val description = docDesc(doc) ?: series.description
            val updatedSeries = series.copy(title = title, posterUrl = poster, description = description)
            val seasons = parseEpisodes(doc, series.sourceUrl)
            SeriesDetail(updatedSeries, seasons)
        }

    override suspend fun getStreamSources(episode: Episode): List<StreamSource> =
        withContext(Dispatchers.IO) {
            try {
                val doc = getDoc(episode.sourceUrl)
                val sources = mutableListOf<StreamSource>()

                val btnSels = listOf("button[data-hhs]", "button[data-url]", "a[data-src]")
                for (sel in btnSels) {
                    doc.select(sel).forEach { el ->
                        val u = el.attr("data-hhs").ifBlank { el.attr("data-url").ifBlank { el.attr("data-src") } }
                        if (u.startsWith("http")) sources.addAll(VideoExtractor.extract(u, "$baseUrl/", name))
                    }
                    if (sources.isNotEmpty()) break
                }

                if (sources.isEmpty()) {
                    val iframeSels = listOf("div#player iframe", "div.player iframe", "div.player-box iframe", "iframe[allowfullscreen]", "iframe[src]")
                    for (sel in iframeSels) {
                        doc.select(sel).forEach { el ->
                            val src = el.absUrl("src").ifBlank { el.attr("src") }
                            if (src.startsWith("http")) sources.addAll(VideoExtractor.extract(src, "$baseUrl/", name))
                        }
                        if (sources.isNotEmpty()) break
                    }
                }

                android.util.Log.i("Provider.$name", "sources=${sources.size}")
                sources
            } catch (e: Exception) {
                android.util.Log.e("Provider.$name", "stream failed: ${e.message}")
                emptyList()
            }
        }

    private fun docTitle(doc: Document): String? {
        for (sel in listOf("h1", "h2.title", ".series-title", ".film-title")) {
            val t = doc.selectFirst(sel)?.text()?.trim()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun docPoster(doc: Document): String? {
        for (sel in listOf("div.series-cover img", "div.poster img", "div.film-poster img", "img.poster")) {
            val el = doc.selectFirst(sel) ?: continue
            val url = el.absUrl("src").ifBlank { el.absUrl("data-src") }
            if (url.startsWith("http")) return url
        }
        return doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
    }

    private fun docDesc(doc: Document): String? {
        for (sel in listOf("div.summary", "div.description", "div.ozet", "p.desc")) {
            val t = doc.selectFirst(sel)?.text()?.trim()
            if (!t.isNullOrBlank()) return t
        }
        return doc.selectFirst("meta[property='og:description']")?.attr("content")?.ifBlank { null }
    }

    protected fun parseCards(doc: Document): List<Series> {
        val sels = listOf(
            "div.poster-long", "div.movie-poster", "div.movie-item",
            "div.item", "article.item", "div.resim", "li.item",
            "div.series-item", "div.dizi-item", "div.content-item"
        )
        val results = mutableListOf<Series>()
        for (sel in sels) {
            val cards = doc.select(sel)
            if (cards.isEmpty()) continue
            cards.forEach { el ->
                val s = parseCard(el) ?: return@forEach
                if (results.none { it.id == s.id }) results.add(s)
            }
            if (results.isNotEmpty()) break
        }
        return results
    }

    private fun parseCard(el: Element): Series? {
        val link = el.selectFirst("a") ?: return null
        val url = link.absUrl("href").ifBlank { link.attr("href") }
        if (url.isBlank() || url == baseUrl || url == "$baseUrl/") return null

        var title: String? = null
        for (ts in listOf("h2", "h3", ".title", ".name", "span.title")) {
            val t = el.selectFirst(ts)?.text()?.trim()
            if (!t.isNullOrBlank()) { title = t; break }
        }
        if (title == null) title = link.attr("title").trim().ifBlank { null }
            ?: el.selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) return null

        val imgEl = el.selectFirst("img")
        var poster: String? = null
        if (imgEl != null) {
            for (attr in listOf("data-src", "data-lazy-src", "src")) {
                val v = imgEl.attr(attr).trim()
                if (v.isNotBlank()) { poster = if (v.startsWith("http")) v else "$baseUrl$v"; break }
            }
        }

        return Series(id = url, title = title, posterUrl = poster, description = null,
            year = null, rating = null, sourceUrl = url, providerName = name)
    }

    protected fun parseEpisodes(doc: Document, seriesUrl: String): List<Season> {
        val sels = listOf(
            "ul.episodelist li", "div.episodelist li", ".episode-list li",
            "div.episodes li", "ul.episodes li", "div.bolum-list a",
            ".bolumler li"
        )
        val episodeMap = mutableMapOf<Int, MutableList<Episode>>()
        for (sel in sels) {
            val items = doc.select(sel)
            if (items.isEmpty()) continue
            items.forEach { el ->
                val link: Element = el.selectFirst("a") ?: (if (el.tagName() == "a") el else return@forEach)
                val epUrl = link.absUrl("href").ifBlank { link.attr("href") }
                if (epUrl.isBlank()) return@forEach
                val epTitle = link.text().trim().ifBlank { el.text().trim() }
                if (epTitle.isBlank()) return@forEach
                val seasonNum = Regex("""(\d+)[.\s]*[Ss]ezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("""(\d+)[.\s]*[Bb]ölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\b(\d+)\b""").findAll(epTitle).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodeMap.getOrPut(seasonNum) { mutableListOf() }.size + 1)
                episodeMap.getOrPut(seasonNum) { mutableListOf() }
                    .add(Episode(epTitle, seasonNum, epNum, null, epUrl, name))
            }
            if (episodeMap.isNotEmpty()) break
        }
        return if (episodeMap.isNotEmpty()) {
            episodeMap.entries.sortedBy { it.key }.map { (num, eps) ->
                Season(num, eps.sortedBy { it.episodeNumber })
            }
        } else {
            listOf(Season(1, listOf(Episode(docTitle(doc) ?: name, 1, 1, null, seriesUrl, name))))
        }
    }

    protected fun getDoc(url: String): Document = Jsoup.parse(get(url), url)

    protected fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", VideoExtractor.UA)
            .header("Referer", "$baseUrl/")
            .header("Accept-Language", "tr-TR,tr;q=0.9")
            .build()
        return client.newCall(req).execute().body?.string() ?: ""
    }
}

// =============================================================================
// ASYA / KORE
// =============================================================================

class AsyaFanatiklerimProvider : GenericDiziProvider() {
    override val name = "AsyaFanatiklerim"
    override val baseUrl = "https://www.asyafanatiklerim.com"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/kore-dizileri/",
        "Çin Dizileri" to "$baseUrl/cin-dizileri/"
    )
}

class AsyaMinikProvider : GenericDiziProvider() {
    override val name = "AsyaMinik"
    override val baseUrl = "https://www.asyaminik.com"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/category/kore-dizileri/"
    )
}

class AsyaAnimeleriProvider : GenericDiziProvider() {
    override val name = "AsyaAnimeleri"
    override val baseUrl = "https://asyaanimeleri.top"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziAsyaProvider : GenericDiziProvider() {
    override val name = "DiziAsya"
    override val baseUrl = "https://diziasya.com"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/category/kore-dizi/"
    )
}

class DiziAsiaProvider : GenericDiziProvider() {
    override val name = "DiziAsia"
    override val baseUrl = "https://diziasia.com"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Asya Dizileri" to "$baseUrl/asya-dizileri/"
    )
}

class BirAsyaDiziProvider : GenericDiziProvider() {
    override val name = "BirAsyaDizi"
    override val baseUrl = "https://birasyadizi.com"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziGomProvider : GenericDiziProvider() {
    override val name = "DiziGom"
    override val baseUrl = "https://dizigom1.co"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Asya Dizileri" to "$baseUrl/asya/"
    )
}

class HintDiziProvider : GenericDiziProvider() {
    override val name = "HintDizi"
    override val baseUrl = "https://www.hintdizi.com"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class WebDramaTurkeyProvider : GenericDiziProvider() {
    override val name = "WebDramaTurkey"
    override val baseUrl = "https://webdramaturkey.org"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore" to "$baseUrl/category/kore/"
    )
}

// =============================================================================
// GENEL TÜRK DİZİ
// =============================================================================

class SezonlukDiziProvider : GenericDiziProvider() {
    override val name = "SezonlukDizi"
    override val baseUrl = "https://www.sezonlukdizi6.com"
    override val homeCategories get() = listOf(
        "Son Bölümler" to "$baseUrl/page/",
        "Yabancı Diziler" to "$baseUrl/category/yabanci-dizi/"
    )
}

class YabanciDiziProvider : GenericDiziProvider() {
    override val name = "YabanciDizi"
    override val baseUrl = "https://yabancidizi.so"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziBoxProvider : GenericDiziProvider() {
    override val name = "DiziBox"
    override val baseUrl = "https://www.dizibox.de"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turk-dizileri/"
    )
}

class DizillaProvider : GenericDiziProvider() {
    override val name = "Dizilla"
    override val baseUrl = "https://www.dizilla.to"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziPalProvider : GenericDiziProvider() {
    override val name = "DiziPal"
    override val baseUrl = "https://www.dizipal1206.com"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziPalOrijinalProvider : GenericDiziProvider() {
    override val name = "DiziPalOrijinal"
    override val baseUrl = "https://www.dizipal932.com"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class TvDizilerProvider : GenericDiziProvider() {
    override val name = "TvDiziler"
    override val baseUrl = "https://www.tvdiziler.cc"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziWatchProvider : GenericDiziProvider() {
    override val name = "DiziWatch"
    override val baseUrl = "https://www.diziwatch.net"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Diziler" to "$baseUrl/diziler/"
    )
}

class DiziFilmOrgProvider : GenericDiziProvider() {
    override val name = "DiziFilmORG"
    override val baseUrl = "https://www.dizifilm.org"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziLifeProvider : GenericDiziProvider() {
    override val name = "DiziLife"
    override val baseUrl = "https://www.dizi18.life"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziMagProvider : GenericDiziProvider() {
    override val name = "DiziMag"
    override val baseUrl = "https://dizimag.mom"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziMomProvider : GenericDiziProvider() {
    override val name = "DiziMom"
    override val baseUrl = "https://dizimom.mom"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziYouProvider : GenericDiziProvider() {
    override val name = "DiziYou"
    override val baseUrl = "https://diziyou.co"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DdiziProvider : GenericDiziProvider() {
    override val name = "Ddizi"
    override val baseUrl = "https://ddizi.im"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class TurkdizileriProvider : GenericDiziProvider() {
    override val name = "Turkdizileri"
    override val baseUrl = "https://turkdizileri.net"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

// =============================================================================
// EK KAYNAKLAR
// =============================================================================

class FullHdDiziProvider : GenericDiziProvider() {
    override val name = "FullHdDizi"
    override val baseUrl = "https://www.fullhddizi.pw"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Yabancı Diziler" to "$baseUrl/category/yabanci-dizi/"
    )
}

class DiziKingProvider : GenericDiziProvider() {
    override val name = "DiziKing"
    override val baseUrl = "https://diziking.net"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class HdDizimProvider : GenericDiziProvider() {
    override val name = "HdDizim"
    override val baseUrl = "https://www.hddizim.net"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziHubProvider : GenericDiziProvider() {
    override val name = "DiziHub"
    override val baseUrl = "https://www.dizihub.net"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Diziler" to "$baseUrl/diziler/"
    )
}

class YabanciBoldProvider : GenericDiziProvider() {
    override val name = "YabanciBold"
    override val baseUrl = "https://www.yabancibold.com"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class DiziSeyretProvider : GenericDiziProvider() {
    override val name = "DiziSeyret"
    override val baseUrl = "https://www.diziseyret.net"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class JaponDiziProvider : GenericDiziProvider() {
    override val name = "JaponDizi"
    override val baseUrl = "https://japonanime.com"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Anime" to "$baseUrl/category/anime/"
    )
}

class AnimeciProvider : GenericDiziProvider() {
    override val name = "Animeci"
    override val baseUrl = "https://www.animeci.com"
    override val homeCategories get() = listOf("Son Eklenenler" to "$baseUrl/page/")
}

class TrAnimeProvider : GenericDiziProvider() {
    override val name = "TrAnime"
    override val baseUrl = "https://tranime.net"
    override val homeCategories get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Anime" to "$baseUrl/category/anime/"
    )
}

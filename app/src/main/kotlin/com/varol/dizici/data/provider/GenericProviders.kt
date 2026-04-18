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

/**
 * Genel Türk dizi sitelerini destekleyen ayarlanabilir base provider.
 * Her site sadece URL ve selector override'ları tanımlar.
 */
abstract class GenericDiziProvider : ContentProvider {

    abstract override val name: String
    abstract val baseUrl: String

    open val searchPath: String get() = "/?s="
    open val homeCategories: List<Pair<String, String>> get() = listOf(
        "Son Eklenenler" to "$baseUrl/page/"
    )

    // --- Kart (liste) selectors ---
    open val cardSelectors = listOf(
        "div.poster-long", "div.movie-poster", "div.movie-item", "div.item",
        "article.item", "div.resim", "li.item", "div.series-item",
        "div.dizi-item", "div.content-item", "div.film-item"
    )
    open val cardLinkSelector = "a"
    open val cardTitleSelectors = listOf("h2", "h3", ".title", ".name", "span.title", "div.title")
    open val cardPosterAttrs = listOf("data-src", "data-lazy-src", "src")

    // --- Detay sayfası selectors ---
    open val seriesTitleSelector = "h1, h2.title, .series-title"
    open val seriesPosterSelectors = listOf(
        "div.series-cover img", "div.poster img", "div.film-poster img",
        "div.series-poster img", "img.poster", "meta[property='og:image']"
    )
    open val seriesDescSelector = "div.summary, div.description, div.ozet, p.desc, meta[property='og:description']"

    // --- Bölüm listesi selectors ---
    open val episodeSelectors = listOf(
        "ul.episodelist li", "div.episodelist li", ".episode-list li",
        "div.episodes li", "ul.episodes li", "div.bolum-list a",
        "div.season-episodes li", ".bolumler li", "table.bolumliste tr"
    )

    // --- Stream selectors ---
    open val streamIframeSelectors = listOf(
        "div#player iframe", "div.player iframe", "div.player-box iframe",
        "div#main-player iframe", "iframe[src*='embed']", "iframe[src*='player']",
        "iframe[allowfullscreen]"
    )
    open val streamButtonSelectors = listOf(
        "button[data-hhs]", "button[data-url]", "a[data-src]",
        "div.source-btn[data-url]", "li[data-src]"
    )

    private val client = HttpClient.client

    // =====================================================================
    override suspend fun getHomePage(page: Int): List<HomeCategory> = withContext(Dispatchers.IO) {
        homeCategories.mapNotNull { (catName, catUrl) ->
            try {
                val url = if (catUrl.endsWith("/page/")) "$catUrl$page" else catUrl
                val doc = getDoc(url)
                val series = parseCards(doc)
                if (series.isNotEmpty()) HomeCategory(catName, series.take(20)) else null
            } catch (e: Exception) {
                android.util.Log.e("Provider.$name", "homepage failed", e)
                null
            }
        }
    }

    override suspend fun search(query: String): List<Series> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val doc = getDoc("$baseUrl$searchPath$encoded")
            parseCards(doc)
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getSeriesDetail(series: Series): SeriesDetail = withContext(Dispatchers.IO) {
        val doc = getDoc(series.sourceUrl)

        val title = doc.selectFirstMatch(seriesTitleSelector)?.text()?.trim() ?: series.title
        val poster = seriesPosterSelectors.firstNotNullOfOrNull { sel ->
            val el = doc.selectFirst(sel)
            when {
                el == null -> null
                el.tagName() == "meta" -> el.attr("content").takeIf { it.startsWith("http") }
                else -> el.absUrl("src").ifBlank { el.absUrl("data-src") }.ifBlank { null }
            }
        } ?: series.posterUrl
        val description = doc.selectFirstMatch(seriesDescSelector)?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        } ?: series.description

        val updatedSeries = series.copy(title = title, posterUrl = poster, description = description)

        val seasons = parseEpisodes(doc, series.sourceUrl)
        SeriesDetail(updatedSeries, seasons)
    }

    override suspend fun getStreamSources(episode: Episode): List<StreamSource> = withContext(Dispatchers.IO) {
        try {
            val doc = getDoc(episode.sourceUrl)
            val sources = mutableListOf<StreamSource>()

            // data-src / data-hhs butonları
            for (sel in streamButtonSelectors) {
                doc.select(sel).forEach { el ->
                    val url = el.attr("data-hhs").ifBlank { el.attr("data-url") }
                        .ifBlank { el.attr("data-src") }
                    if (url.startsWith("http")) {
                        sources.addAll(VideoExtractor.extract(url, "$baseUrl/", name))
                    }
                }
                if (sources.isNotEmpty()) break
            }

            // iframe'ler
            if (sources.isEmpty()) {
                for (sel in streamIframeSelectors) {
                    doc.select(sel).forEach { iframe ->
                        val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                        if (src.startsWith("http")) {
                            sources.addAll(VideoExtractor.extract(src, "$baseUrl/", name))
                        }
                    }
                    if (sources.isNotEmpty()) break
                }
            }

            // Generic fallback: tüm iframe'ler
            if (sources.isEmpty()) {
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.startsWith("http")) {
                        sources.addAll(VideoExtractor.extract(src, "$baseUrl/", name))
                    }
                }
            }

            android.util.Log.i("Provider.$name", "sources=${sources.size} url=${episode.sourceUrl}")
            sources
        } catch (e: Exception) {
            android.util.Log.e("Provider.$name", "stream failed", e)
            emptyList()
        }
    }

    // =====================================================================
    protected fun parseCards(doc: Document): List<Series> {
        val results = mutableListOf<Series>()
        for (sel in cardSelectors) {
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

    protected open fun parseCard(el: Element): Series? {
        val link = el.selectFirst(cardLinkSelector) ?: return null
        val url = link.absUrl("href").ifBlank { link.attr("href") }
        if (url.isBlank() || url == baseUrl || url == "$baseUrl/") return null

        val title = cardTitleSelectors.firstNotNullOfOrNull { sel ->
            el.selectFirst(sel)?.text()?.trim()?.ifBlank { null }
        } ?: link.attr("title").trim().ifBlank {
            el.selectFirst("img")?.attr("alt")?.trim()
        } ?: return null

        val poster = el.selectFirst("img")?.let { img ->
            cardPosterAttrs.firstNotNullOfOrNull { attr ->
                img.attr(attr).ifBlank { null }
            }
        }

        return Series(
            id = url,
            title = title,
            posterUrl = poster?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            description = null,
            year = null,
            rating = null,
            sourceUrl = url,
            providerName = name
        )
    }

    protected fun parseEpisodes(doc: Document, seriesUrl: String): List<Season> {
        val episodeMap = mutableMapOf<Int, MutableList<Episode>>()

        for (sel in episodeSelectors) {
            val items = doc.select(sel)
            if (items.isEmpty()) continue

            items.forEach { el ->
                val link = el.selectFirst("a") ?: el.takeIf { it.tagName() == "a" } ?: return@forEach
                val epUrl = link.absUrl("href").ifBlank { link.attr("href") }
                if (epUrl.isBlank()) return@forEach
                val epTitle = link.text().trim().ifBlank {
                    el.text().trim()
                }.ifBlank { return@forEach }

                val seasonNum = Regex("""(\d+)[.\s]*[Ss]ezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("""(\d+)[.\s]*[Bb]ölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""[Ee]p(?:isode)?[.\s]*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\b(\d+)\b""").findAll(epTitle).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    ?: (episodeMap.getOrPut(seasonNum) { mutableListOf() }.size + 1)

                episodeMap.getOrPut(seasonNum) { mutableListOf() }.add(
                    Episode(epTitle, seasonNum, epNum, null, epUrl, name)
                )
            }

            if (episodeMap.isNotEmpty()) break
        }

        return if (episodeMap.isNotEmpty()) {
            episodeMap.entries.sortedBy { it.key }.map { (num, eps) ->
                Season(num, eps.sortedBy { it.episodeNumber })
            }
        } else {
            // Film / tek bölüm fallback
            val title = doc.selectFirstMatch(seriesTitleSelector)?.text() ?: name
            listOf(Season(1, listOf(Episode(title, 1, 1, null, seriesUrl, name))))
        }
    }

    protected fun getDoc(url: String): Document {
        val html = get(url)
        return Jsoup.parse(html, url)
    }

    protected fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VideoExtractor.UA)
            .header("Referer", "$baseUrl/")
            .header("Accept-Language", "tr-TR,tr;q=0.9")
            .build()
        return client.newCall(request).execute().body?.string() ?: ""
    }

    private fun Document.selectFirstMatch(selector: String): Element? {
        return try { selectFirst(selector) } catch (e: Exception) { null }
    }
}

// =============================================================================
// ASYA / KORE İÇERİKLİ SİTELER
// =============================================================================

class AsyaFanatiklerimProvider : GenericDiziProvider() {
    override val name = "AsyaFanatiklerim"
    override val baseUrl = "https://www.asyafanatiklerim.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/kore-dizileri/",
        "Çin Dizileri" to "$baseUrl/cin-dizileri/",
        "Japon Dizileri" to "$baseUrl/japon-dizileri/"
    )
    override val episodeSelectors = listOf(
        "div.eplist li", "ul.eplist li",
        "div.bolum-list a", "div.episodes li"
    )
}

class AsyaMinikProvider : GenericDiziProvider() {
    override val name = "AsyaMinik"
    override val baseUrl = "https://www.asyaminik.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/category/kore-dizileri/",
        "Çin Dizileri" to "$baseUrl/category/cin-dizileri/"
    )
}

class AsyaAnimeleriProvider : GenericDiziProvider() {
    override val name = "AsyaAnimeleri"
    override val baseUrl = "https://asyaanimeleri.top"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Animeler" to "$baseUrl/anime-listesi/"
    )
}

class DiziAsyaProvider : GenericDiziProvider() {
    override val name = "DiziAsya"
    override val baseUrl = "https://diziasya.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/category/kore-dizi/",
        "Çin Dizileri" to "$baseUrl/category/cin-dizi/"
    )
    override val episodeSelectors = listOf(
        "div.bolumler a", "ul.bolum-listesi li", "div.bolum-list li"
    )
}

class DiziAsiaProvider : GenericDiziProvider() {
    override val name = "DiziAsia"
    override val baseUrl = "https://diziasia.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Asya Dizileri" to "$baseUrl/asya-dizileri/",
        "Kore Dramaları" to "$baseUrl/kore-drama/"
    )
}

class BirAsyaDiziProvider : GenericDiziProvider() {
    override val name = "BirAsyaDizi"
    override val baseUrl = "https://birasyadizi.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Kore Dizileri" to "$baseUrl/category/kore-dizileri/"
    )
}

class DiziGomProvider : GenericDiziProvider() {
    override val name = "DiziGom"
    override val baseUrl = "https://dizigom1.co"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Asya Dizileri" to "$baseUrl/asya/",
        "Türk Dizileri" to "$baseUrl/turk/"
    )
}

class HintDiziProvider : GenericDiziProvider() {
    override val name = "HintDizi"
    override val baseUrl = "https://www.hintdizi.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Hint Dizileri" to "$baseUrl/category/hint-dizileri/"
    )
}

class WebDramaTurkeyProvider : GenericDiziProvider() {
    override val name = "WebDramaTurkey"
    override val baseUrl = "https://webdramaturkey.org"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Web Dramas" to "$baseUrl/category/web-drama/",
        "Kore" to "$baseUrl/category/kore/"
    )
}

// =============================================================================
// GENEL TÜRK DİZİ SİTELERİ
// =============================================================================

class SezonlukDiziProvider : GenericDiziProvider() {
    override val name = "SezonlukDizi"
    override val baseUrl = "https://www.sezonlukdizi.tv"
    override val homeCategories = listOf(
        "Son Bölümler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/category/turk-dizileri/",
        "Yabancı Diziler" to "$baseUrl/category/yabanci-dizi/"
    )
    override val episodeSelectors = listOf(
        "div.season-list li", "div.episode-list li", "ul.bolumler li"
    )
}

class YabanciDiziProvider : GenericDiziProvider() {
    override val name = "YabanciDizi"
    override val baseUrl = "https://yabancidizi.so"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Dizi Listesi" to "$baseUrl/diziler/"
    )
    override val searchPath = "/?s="
}

class DiziBoxProvider : GenericDiziProvider() {
    override val name = "DiziBox"
    override val baseUrl = "https://www.dizibox.de"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turk-dizileri/",
        "Yabancı Diziler" to "$baseUrl/yabanci-diziler/"
    )
    override val episodeSelectors = listOf(
        "div.episode-box li", "ul.sezon-bolum li", "div.bolumler a"
    )
}

class DizillaProvider : GenericDiziProvider() {
    override val name = "Dizilla"
    override val baseUrl = "https://www.dizilla.to"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Gündemdeki Diziler" to "$baseUrl/category/trend/"
    )
}

class DiziPalProvider : GenericDiziProvider() {
    override val name = "DiziPal"
    override val baseUrl = "https://www.dizipal1206.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turkish-series/",
        "Yabancı Diziler" to "$baseUrl/foreign-series/"
    )
    override val episodeSelectors = listOf(
        "div.episode-list a", "ul.season li a", "div#seasons li a"
    )
}

class DiziPalOrijinalProvider : GenericDiziProvider() {
    override val name = "DiziPalOrijinal"
    override val baseUrl = "https://www.dizipal932.com"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/"
    )
}

class TvDizilerProvider : GenericDiziProvider() {
    override val name = "TvDiziler"
    override val baseUrl = "https://www.tvdiziler.cc"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turk-dizileri/"
    )
}

class DiziWatchProvider : GenericDiziProvider() {
    override val name = "DiziWatch"
    override val baseUrl = "https://diziwatch.tv"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Diziler" to "$baseUrl/diziler/",
        "Anime" to "$baseUrl/anime/"
    )
}

class DiziFilmOrgProvider : GenericDiziProvider() {
    override val name = "DiziFilmORG"
    override val baseUrl = "https://www.dizifilm.org"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turk-dizisi/",
        "Filmler" to "$baseUrl/filmler/"
    )
}

class DiziLifeProvider : GenericDiziProvider() {
    override val name = "DiziLife"
    override val baseUrl = "https://www.dizi18.life"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/"
    )
}

class DiziMagProvider : GenericDiziProvider() {
    override val name = "DiziMag"
    override val baseUrl = "https://dizimag.mom"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turk-dizileri/"
    )
}

class DiziMomProvider : GenericDiziProvider() {
    override val name = "DiziMom"
    override val baseUrl = "https://dizimom.mom"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/"
    )
}

class DiziYouProvider : GenericDiziProvider() {
    override val name = "DiziYou"
    override val baseUrl = "https://diziyou.co"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Diziler" to "$baseUrl/dizi/"
    )
}

class DdiziProvider : GenericDiziProvider() {
    override val name = "Ddizi"
    override val baseUrl = "https://ddizi.im"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/",
        "Türk Dizileri" to "$baseUrl/turk-dizileri/"
    )
}

class TurkdizileriProvider : GenericDiziProvider() {
    override val name = "Turkdizileri"
    override val baseUrl = "https://turkdizileri.net"
    override val homeCategories = listOf(
        "Son Eklenenler" to "$baseUrl/page/"
    )
}

package com.varol.dizici.data.extractor

import android.util.Log
import com.varol.dizici.data.model.StreamSource
import com.varol.dizici.data.model.SubtitleTrack
import com.varol.dizici.data.network.HttpClient
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object VideoExtractor {

    private val client = HttpClient.client
    private val gson = Gson()
    private const val TAG = "VideoExtractor"

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"

    /** Blocking – always call from Dispatchers.IO. */
    fun extract(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        if (iframeUrl.isBlank()) return emptyList()
        return try {
            when {
                iframeUrl.contains("videoseyred.in")  -> extractVideoSeyred(iframeUrl, referer, providerName)
                iframeUrl.contains("yourupload.com")  -> extractYourUpload(iframeUrl, referer, providerName)
                iframeUrl.contains("drive.google.com") -> extractGoogleDrive(iframeUrl, providerName)
                iframeUrl.contains("streamtape.com") || iframeUrl.contains("streamtape.net") ->
                    extractStreamtape(iframeUrl, referer, providerName)
                iframeUrl.contains("vidmoly.to") || iframeUrl.contains("vidmoly.com") ->
                    extractGenericPlayer(iframeUrl, referer, providerName, "Vidmoly")
                iframeUrl.contains("filemoon.sx") || iframeUrl.contains("filemoon.in") ||
                    iframeUrl.contains("moonplayer") ->
                    extractFilemoon(iframeUrl, referer, providerName)
                iframeUrl.contains("ok.ru") || iframeUrl.contains("odnoklassniki.ru") ->
                    extractOkRu(iframeUrl, referer, providerName)
                iframeUrl.contains("dailymotion.com") ->
                    extractDailymotion(iframeUrl, providerName)
                iframeUrl.contains("mixdrop.co") || iframeUrl.contains("mixdrop.to") ||
                    iframeUrl.contains("mixdrop.ag") ->
                    extractMixdrop(iframeUrl, referer, providerName)
                iframeUrl.contains("upstream.to") ->
                    extractGenericPlayer(iframeUrl, referer, providerName, "Upstream")
                iframeUrl.contains("streamlare.com") ->
                    extractStreamlare(iframeUrl, referer, providerName)
                iframeUrl.contains("vk.com") ->
                    extractVK(iframeUrl, referer, providerName)
                iframeUrl.contains("doo.gy") || iframeUrl.contains("doo.io") ->
                    extractGenericPlayer(iframeUrl, referer, providerName, "Doo")
                iframeUrl.contains("truba.xyz") || iframeUrl.contains("truba.net") ->
                    extractGenericPlayer(iframeUrl, referer, providerName, "Truba")
                iframeUrl.contains("watchsb.com") || iframeUrl.contains("sbembed") ||
                    iframeUrl.contains("sbfull") || iframeUrl.contains("sbplay") ||
                    iframeUrl.contains("sbspeed") || iframeUrl.contains("cloudemb") ->
                    extractSBPlay(iframeUrl, referer, providerName)
                iframeUrl.contains("vudeo.co") || iframeUrl.contains("vudeo.net") ||
                    iframeUrl.contains("vudeo.org") ->
                    extractGenericPlayer(iframeUrl, referer, providerName, "Vudeo")
                iframeUrl.contains("fembed.com") || iframeUrl.contains("femax20.com") ||
                    iframeUrl.contains("fplayer.info") ->
                    extractFembed(iframeUrl, referer, providerName)
                else -> extractGenericPlayer(iframeUrl, referer, providerName, "Generic")
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract failed for $iframeUrl: ${e.message}")
            emptyList()
        }
    }

    // ─── VideoSeyred ──────────────────────────────────────────────────────────
    private fun extractVideoSeyred(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val videoId = iframeUrl.substringAfter("embed/").substringBefore("?").substringBefore("/")
            if (videoId.isBlank()) return emptyList()
            val playlist = get("https://videoseyred.in/playlist/$videoId.json", "https://videoseyred.in/")
            val playlists = gson.fromJson(playlist, Array<VSPlaylist>::class.java)
            val sources = mutableListOf<StreamSource>()
            playlists.forEach { pl ->
                val subs = pl.tracks?.filter { it.kind == "captions" && it.label != null }
                    ?.map { SubtitleTrack(it.label!!, it.file, it.language) } ?: emptyList()
                pl.sources?.forEach { src ->
                    if (src.file.isNotBlank()) sources.add(StreamSource(
                        "VideoSeyred${if (src.label != null) " ${src.label}" else ""}",
                        providerName, src.file, src.label, subs,
                        headers = mapOf("Referer" to "https://videoseyred.in/")))
                }
            }
            sources
        } catch (e: Exception) { emptyList() }
    }

    // ─── YourUpload ───────────────────────────────────────────────────────────
    private fun extractYourUpload(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val m = Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(html) ?: return emptyList()
            listOf(StreamSource("YourUpload", providerName, m.groupValues[1], null,
                headers = mapOf("Referer" to iframeUrl)))
        } catch (e: Exception) { emptyList() }
    }

    // ─── Google Drive ─────────────────────────────────────────────────────────
    private fun extractGoogleDrive(iframeUrl: String, providerName: String): List<StreamSource> {
        return try {
            val fileId = Regex("""(?:d/|id=)([a-zA-Z0-9_-]{28,})""").find(iframeUrl)?.groupValues?.get(1)
                ?: return emptyList()
            listOf(StreamSource("Google Drive", providerName,
                "https://drive.google.com/uc?export=download&id=$fileId", null))
        } catch (e: Exception) { emptyList() }
    }

    // ─── Streamtape ───────────────────────────────────────────────────────────
    private fun extractStreamtape(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val part1 = Regex("""id='robotlink'[^>]*>\s*(//[^\s<]+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""robotlink['"]\s*\)\.innerHTML\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: ""
            val part2 = Regex("""\.innerHTML\s*\+=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
            val raw = (part1 + part2).trimStart('/')
            if (raw.isBlank()) return extractByRegex(html, iframeUrl, providerName, "Streamtape")
            val finalUrl = if (raw.startsWith("http")) raw else "https://$raw"
            listOf(StreamSource("Streamtape", providerName, finalUrl, null,
                headers = mapOf("Referer" to "https://streamtape.com/")))
        } catch (e: Exception) { emptyList() }
    }

    // ─── Filemoon ─────────────────────────────────────────────────────────────
    private fun extractFilemoon(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val direct = extractByRegex(html, iframeUrl, providerName, "Filemoon")
            if (direct.isNotEmpty()) return direct
            val fileId = Regex("""/[ef]/([a-zA-Z0-9]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val base = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val playlist = get("$base/playlist/$fileId.json", iframeUrl)
            val pls = runCatching { gson.fromJson(playlist, Array<VSPlaylist>::class.java) }.getOrNull() ?: return emptyList()
            pls.flatMap { pl -> pl.sources?.map { src ->
                StreamSource("Filemoon", providerName, src.file, src.label, headers = mapOf("Referer" to iframeUrl))
            } ?: emptyList() }
        } catch (e: Exception) { emptyList() }
    }

    // ─── ok.ru ────────────────────────────────────────────────────────────────
    private fun extractOkRu(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val url = Regex(""""hlsMasterPlaylistUrl"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1)?.replace("\\u0026", "&") ?: return emptyList()
            listOf(StreamSource("OK.ru", providerName, url, "HLS", headers = mapOf("Referer" to "https://ok.ru/")))
        } catch (e: Exception) { emptyList() }
    }

    // ─── Dailymotion ──────────────────────────────────────────────────────────
    private fun extractDailymotion(iframeUrl: String, providerName: String): List<StreamSource> {
        return try {
            val id = Regex("""/video/([a-zA-Z0-9]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val json = get("https://www.dailymotion.com/player/metadata/video/$id?locale=tr", "https://www.dailymotion.com/")
            val m3u8 = Regex(""""auto"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(json)?.groupValues?.get(1)?.replace("\\", "")
                ?: return emptyList()
            listOf(StreamSource("Dailymotion", providerName, m3u8, "HLS"))
        } catch (e: Exception) { emptyList() }
    }

    // ─── Mixdrop ──────────────────────────────────────────────────────────────
    private fun extractMixdrop(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val url = Regex("""MDCore\.wurl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return extractByRegex(html, iframeUrl, providerName, "Mixdrop")
            val finalUrl = if (url.startsWith("//")) "https:$url" else url
            listOf(StreamSource("Mixdrop", providerName, finalUrl, null,
                headers = mapOf("Referer" to "https://mixdrop.co/")))
        } catch (e: Exception) { emptyList() }
    }

    // ─── Streamlare ───────────────────────────────────────────────────────────
    private fun extractStreamlare(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val slashId = Regex("""/[ev]/([a-zA-Z0-9]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val body = """{"id":"$slashId"}""".toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://streamlare.com/api/video/stream/get")
                .post(body)
                .header("Referer", iframeUrl)
                .header("User-Agent", UA)
                .build()
            val json = client.newCall(req).execute().body?.string() ?: return emptyList()
            val url = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(json)?.groupValues?.get(1)?.replace("\\", "")
                ?: return emptyList()
            listOf(StreamSource("Streamlare", providerName, url, "HLS", headers = mapOf("Referer" to "https://streamlare.com/")))
        } catch (e: Exception) { emptyList() }
    }

    // ─── VK ───────────────────────────────────────────────────────────────────
    private fun extractVK(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val sources = mutableListOf<StreamSource>()
            listOf("1080", "720", "480", "360").forEach { q ->
                Regex(""""url$q"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.let { sources.add(StreamSource("VK ${q}p", providerName, it, q, headers = mapOf("Referer" to "https://vk.com/"))) }
            }
            if (sources.isEmpty()) extractByRegex(html, iframeUrl, providerName, "VK") else sources
        } catch (e: Exception) { emptyList() }
    }

    // ─── Fembed ───────────────────────────────────────────────────────────────
    private fun extractFembed(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val base = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val fileId = Regex("""/[ev]/([a-zA-Z0-9]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val body = "r=".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder().url("$base/api/source/$fileId").post(body)
                .header("Referer", iframeUrl).header("User-Agent", UA).build()
            val json = client.newCall(req).execute().body?.string() ?: return emptyList()
            Regex(""""file"\s*:\s*"([^"]+)","label"\s*:\s*"([^"]+)"""")
                .findAll(json).map { m ->
                    StreamSource("Fembed ${m.groupValues[2]}", providerName,
                        m.groupValues[1].replace("\\/", "/"), m.groupValues[2],
                        headers = mapOf("Referer" to iframeUrl))
                }.toList()
        } catch (e: Exception) { emptyList() }
    }

    // ─── SBPlay variants ──────────────────────────────────────────────────────
    private fun extractSBPlay(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val direct = extractByRegex(html, iframeUrl, providerName, "SBPlay")
            if (direct.isNotEmpty()) return direct
            val fileId = Regex("""(?:embed-|/e/)([a-zA-Z0-9]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val base = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
            val apiHtml = get("$base/play/$fileId-1.html", iframeUrl)
            extractByRegex(apiHtml, "$base/play/$fileId-1.html", providerName, "SBPlay")
        } catch (e: Exception) { emptyList() }
    }

    // ─── Generic player ───────────────────────────────────────────────────────
    private fun extractGenericPlayer(iframeUrl: String, referer: String, providerName: String, sourceName: String): List<StreamSource> {
        return try {
            val html = get(iframeUrl, referer)
            val sources = extractByRegex(html, iframeUrl, providerName, sourceName)
            if (sources.isNotEmpty()) return sources
            // One-level nested iframe
            val innerSrc = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1) ?: return emptyList()
            val innerUrl = if (innerSrc.startsWith("http")) innerSrc else {
                val base = Regex("""(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return emptyList()
                "$base$innerSrc"
            }
            if (innerUrl == iframeUrl) return emptyList()
            extractByRegex(get(innerUrl, iframeUrl), innerUrl, providerName, sourceName)
        } catch (e: Exception) { emptyList() }
    }

    // ─── Shared regex patterns ────────────────────────────────────────────────
    private fun extractByRegex(html: String, iframeUrl: String, providerName: String, sourceName: String): List<StreamSource> {
        val sources = mutableListOf<StreamSource>()
        val headers = mapOf("Referer" to iframeUrl)

        // HLS m3u8 (highest priority)
        Regex("""["'`](https?://[^"'`\s\\]+\.m3u8[^"'`\s]*)["'`]""").findAll(html).forEach {
            val url = it.groupValues[1].replace("\\", "")
            if (sources.none { s -> s.url == url })
                sources.add(StreamSource("$sourceName HLS", providerName, url, "HLS", headers = headers))
        }
        if (sources.isNotEmpty()) return sources

        // MP4
        Regex("""["'`](https?://[^"'`\s\\]+\.mp4[^"'`\s]*)["'`]""").findAll(html).forEach {
            val url = it.groupValues[1].replace("\\", "")
            if (sources.none { s -> s.url == url })
                sources.add(StreamSource("$sourceName MP4", providerName, url, null, headers = headers))
        }
        if (sources.isNotEmpty()) return sources

        // JWPlayer / VideoJS `file` key
        Regex("""["']?file["']?\s*:\s*["'`]([^"'`]+)["'`]""").findAll(html).forEach {
            val url = it.groupValues[1].replace("\\", "")
            if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4") || url.contains("/hls/") || url.contains("/stream")))
                if (sources.none { s -> s.url == url })
                    sources.add(StreamSource("$sourceName File", providerName, url, null, headers = headers))
        }
        if (sources.isNotEmpty()) return sources

        // JSON src/url/hls keys
        Regex(""""(?:src|source|url|hls|stream)"\s*:\s*"(https?://[^"\\]+)"""").findAll(html).forEach {
            val url = it.groupValues[1].replace("\\u0026", "&").replace("\\/", "/")
            if (url.contains(".m3u8") || url.contains(".mp4"))
                if (sources.none { s -> s.url == url })
                    sources.add(StreamSource("$sourceName Stream", providerName, url, null, headers = headers))
        }

        return sources
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────────
    private fun get(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
            .build()
        return client.newCall(req).execute().body?.string() ?: ""
    }

    // ─── Data models ─────────────────────────────────────────────────────────
    private data class VSPlaylist(val sources: List<VSSource>?, val tracks: List<VSTrack>?)
    private data class VSSource(val file: String, val label: String?, val type: String?)
    private data class VSTrack(val file: String, val kind: String, val language: String?, val label: String?)
}

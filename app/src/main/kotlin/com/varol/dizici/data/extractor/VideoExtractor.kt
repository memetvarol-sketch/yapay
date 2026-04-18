package com.varol.dizici.data.extractor

import com.varol.dizici.data.model.StreamSource
import com.varol.dizici.data.model.SubtitleTrack
import com.varol.dizici.data.network.HttpClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object VideoExtractor {

    private val client = HttpClient.client
    private val gson = Gson()

    suspend fun extract(iframeUrl: String, referer: String, providerName: String): List<StreamSource> {
        return when {
            iframeUrl.contains("videoseyred.in") -> extractVideoSeyred(iframeUrl, referer, providerName)
            iframeUrl.contains("yourupload.com") -> extractYourUpload(iframeUrl, referer, providerName)
            iframeUrl.contains("drive.google.com") -> extractGoogleDrive(iframeUrl, providerName)
            else -> extractGeneric(iframeUrl, referer, providerName)
        }
    }

    private suspend fun extractVideoSeyred(iframeUrl: String, referer: String, providerName: String): List<StreamSource> =
        withContext(Dispatchers.IO) {
            try {
                val videoId = iframeUrl.substringAfter("embed/").substringBefore("?").substringBefore("/")
                if (videoId.isBlank()) return@withContext emptyList()

                val playlistUrl = "https://videoseyred.in/playlist/$videoId.json"
                val request = Request.Builder()
                    .url(playlistUrl)
                    .header("Referer", "https://videoseyred.in/")
                    .header("User-Agent", UA)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()

                val playlists = gson.fromJson(body, Array<VSPlaylist>::class.java)
                val sources = mutableListOf<StreamSource>()

                playlists.forEach { playlist ->
                    val subtitles = playlist.tracks
                        ?.filter { it.kind == "captions" && it.label != null }
                        ?.map { SubtitleTrack(it.label!!, it.file, it.language) }
                        ?: emptyList()

                    playlist.sources?.forEach { src ->
                        if (src.file.isNotBlank()) {
                            sources.add(
                                StreamSource(
                                    name = "VideoSeyred${if (src.label != null) " ${src.label}" else ""}",
                                    providerName = providerName,
                                    url = src.file,
                                    quality = src.label,
                                    subtitles = subtitles,
                                    headers = mapOf("Referer" to "https://videoseyred.in/")
                                )
                            )
                        }
                    }
                }
                sources
            } catch (e: Exception) {
                emptyList()
            }
        }

    private suspend fun extractYourUpload(iframeUrl: String, referer: String, providerName: String): List<StreamSource> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(iframeUrl)
                    .header("Referer", referer)
                    .header("User-Agent", UA)
                    .build()
                val html = client.newCall(request).execute().body?.string() ?: return@withContext emptyList()

                val fileRegex = Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
                val match = fileRegex.find(html) ?: return@withContext emptyList()
                val url = match.groupValues[1]

                listOf(StreamSource("YourUpload", providerName, url, null, headers = mapOf("Referer" to iframeUrl)))
            } catch (e: Exception) {
                emptyList()
            }
        }

    private suspend fun extractGoogleDrive(iframeUrl: String, providerName: String): List<StreamSource> =
        withContext(Dispatchers.IO) {
            try {
                val fileId = Regex("""(?:d/|id=)([a-zA-Z0-9_-]{28,})""").find(iframeUrl)?.groupValues?.get(1)
                    ?: return@withContext emptyList()
                val directUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                listOf(StreamSource("Google Drive", providerName, directUrl, null))
            } catch (e: Exception) {
                emptyList()
            }
        }

    private suspend fun extractGeneric(iframeUrl: String, referer: String, providerName: String): List<StreamSource> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(iframeUrl)
                    .header("Referer", referer)
                    .header("User-Agent", UA)
                    .build()
                val html = client.newCall(request).execute().body?.string() ?: return@withContext emptyList()

                val sources = mutableListOf<StreamSource>()

                // m3u8 arama
                val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                m3u8Regex.findAll(html).forEach {
                    sources.add(StreamSource("HLS", providerName, it.groupValues[1], "HLS",
                        headers = mapOf("Referer" to iframeUrl)))
                }

                // mp4 arama
                if (sources.isEmpty()) {
                    val mp4Regex = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                    mp4Regex.findAll(html).forEach {
                        sources.add(StreamSource("MP4", providerName, it.groupValues[1], null,
                            headers = mapOf("Referer" to iframeUrl)))
                    }
                }

                // file: pattern
                if (sources.isEmpty()) {
                    val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                    fileRegex.findAll(html).forEach {
                        val url = it.groupValues[1]
                        if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                            sources.add(StreamSource("Video", providerName, url, null,
                                headers = mapOf("Referer" to iframeUrl)))
                        }
                    }
                }

                sources
            } catch (e: Exception) {
                emptyList()
            }
        }

    private data class VSPlaylist(
        val sources: List<VSSource>?,
        val tracks: List<VSTrack>?
    )

    private data class VSSource(
        val file: String,
        val label: String?,
        val type: String?
    )

    private data class VSTrack(
        val file: String,
        val kind: String,
        val language: String?,
        val label: String?
    )

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
}

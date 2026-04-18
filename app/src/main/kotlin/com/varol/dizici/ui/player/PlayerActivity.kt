package com.varol.dizici.ui.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.varol.dizici.DiziciApp
import com.varol.dizici.R
import com.varol.dizici.data.model.StreamSource
import com.varol.dizici.data.network.HttpClient
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private val app get() = DiziciApp.instance
    private lateinit var playerView: PlayerView
    private lateinit var sourceOverlay: View
    private lateinit var loadingView: View
    private lateinit var loadingText: TextView
    private lateinit var btnChangeSource: TextView
    private lateinit var sourcesList: RecyclerView

    private var player: ExoPlayer? = null
    private var sources: MutableList<StreamSource> = mutableListOf()
    private var currentSourceIndex = 0
    private var lastPosition = 0L
    private var sourcesAdapter: SourcesAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        sourceOverlay = findViewById(R.id.source_overlay)
        loadingView = findViewById(R.id.loading_view)
        loadingText = findViewById(R.id.loading_text)
        btnChangeSource = findViewById(R.id.btn_change_source)
        sourcesList = findViewById(R.id.sources_list)

        setupPlayer()
        setupSourceList()
        setupButtons()
        loadSources()
    }

    private fun setupPlayer() {
        val dataSourceFactory = OkHttpDataSource.Factory(HttpClient.client)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Kaynak hatalıysa otomatik olarak sonraki kaynağa geç
                        if (currentSourceIndex < sources.size - 1) {
                            currentSourceIndex++
                            playSource(sources[currentSourceIndex])
                        } else {
                            showError("Oynatılamadı. Lütfen kaynak değiştirin.")
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            hideLoading()
                            if (sources.size > 1) btnChangeSource.visibility = View.VISIBLE
                        }
                    }
                })
            }
    }

    private fun setupSourceList() {
        sourcesAdapter = SourcesAdapter(sources) { index ->
            lastPosition = player?.currentPosition ?: 0
            currentSourceIndex = index
            playSource(sources[index])
            hideSourceOverlay()
        }
        sourcesList.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = sourcesAdapter
        }
    }

    private fun setupButtons() {
        btnChangeSource.setOnClickListener { showSourceOverlay() }
        btnChangeSource.setOnFocusChangeListener { _, _ -> }
    }

    private fun loadSources() {
        val episode = app.currentEpisode ?: run { finish(); return }
        val series = app.currentSeriesDetail?.series ?: app.currentSeries

        showLoading("Kaynaklar yükleniyor…")

        lifecycleScope.launch {
            try {
                val newSources = app.repository.getAllSources(episode, series?.title ?: "")
                sources.clear()
                sources.addAll(newSources)
                app.currentSources = newSources

                if (sources.isNotEmpty()) {
                    sourcesAdapter?.notifyDataSetChanged()
                    currentSourceIndex = 0
                    playSource(sources[0])
                } else {
                    val err = app.repository.lastPrimaryError
                    showError("Kaynak bulunamadı. ${err?.message ?: ""}")
                }
            } catch (e: Exception) {
                showError("Yükleme hatası: ${e.message}")
            }
        }
    }

    private fun playSource(source: StreamSource) {
        showLoading("${source.providerName} - ${source.quality ?: source.name}")
        sourcesAdapter?.setSelected(sources.indexOf(source))

        val mediaItemBuilder = MediaItem.Builder().setUri(source.url)

        // Altyazı varsa ekle
        if (source.subtitles.isNotEmpty()) {
            val subtitleConfigs = source.subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLabel(sub.label)
                    .apply { sub.language?.let { setLanguage(it) } }
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        val mediaItem = mediaItemBuilder.build()
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            if (lastPosition > 0) seekTo(lastPosition)
            playWhenReady = true
        }
    }

    private fun showSourceOverlay() {
        player?.pause()
        sourceOverlay.visibility = View.VISIBLE
        sourcesList.requestFocus()
        btnChangeSource.visibility = View.GONE
    }

    private fun hideSourceOverlay() {
        sourceOverlay.visibility = View.GONE
        player?.play()
        if (sources.size > 1) btnChangeSource.visibility = View.VISIBLE
        playerView.requestFocus()
    }

    private fun showLoading(message: String) {
        loadingText.text = message
        loadingView.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingText.text = message
        loadingView.visibility = View.VISIBLE
        if (sources.size > 1) {
            btnChangeSource.visibility = View.VISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (sourceOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                hideSourceOverlay()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (sources.size > 1) showSourceOverlay()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        lastPosition = player?.currentPosition ?: 0
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}

class SourcesAdapter(
    private val sources: List<StreamSource>,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<SourcesAdapter.VH>() {

    private var selectedIndex = 0

    fun setSelected(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val source = sources[position]
        holder.name.text = "${source.providerName} — ${source.name}"
        holder.quality.text = source.quality ?: "Varsayılan"

        val isSelected = position == selectedIndex
        val bgColor = if (isSelected)
            holder.itemView.context.getColor(R.color.source_selected)
        else
            holder.itemView.context.getColor(R.color.source_normal)
        holder.itemView.setBackgroundColor(bgColor)

        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener { onSelect(position) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(
                if (hasFocus) v.context.getColor(R.color.accent)
                else if (isSelected) v.context.getColor(R.color.source_selected)
                else v.context.getColor(R.color.source_normal)
            )
        }
    }

    override fun getItemCount() = sources.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.source_name)
        val quality: TextView = view.findViewById(R.id.source_quality)
    }
}

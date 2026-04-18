package com.varol.dizici.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

enum class ContentType { SERIES, MOVIE }

@Parcelize
data class Series(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val description: String?,
    val year: String?,
    val rating: Double?,
    val sourceUrl: String,
    val providerName: String,
    val type: ContentType = ContentType.SERIES
) : Parcelable

@Parcelize
data class SeriesDetail(
    val series: Series,
    val seasons: List<Season>
) : Parcelable

@Parcelize
data class Season(
    val number: Int,
    val episodes: List<Episode>
) : Parcelable

@Parcelize
data class Episode(
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val thumbnailUrl: String?,
    val sourceUrl: String,
    val providerName: String
) : Parcelable

@Parcelize
data class StreamSource(
    val name: String,
    val providerName: String,
    val url: String,
    val quality: String?,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val headers: @RawValue Map<String, String> = emptyMap()
) : Parcelable

@Parcelize
data class SubtitleTrack(
    val label: String,
    val url: String,
    val language: String?
) : Parcelable

data class HomeCategory(
    val name: String,
    val series: List<Series>
)

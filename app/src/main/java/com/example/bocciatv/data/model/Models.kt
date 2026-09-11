package com.example.bocciatv.data.model

import android.util.Base64
import com.google.gson.annotations.SerializedName

data class UserAuth(
    @SerializedName("user_info") val userInfo: UserInfo?
)

data class UserInfo(
    @SerializedName("username") val username: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: Any?
)

data class Category(
    @SerializedName("category_id") val id: String?,
    @SerializedName("category_name") val name: String?
)

data class StreamItem(
    @SerializedName("stream_id") val streamId: Any?,
    @SerializedName("series_id") val seriesId: Any?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_icon") val icon: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("container_extension") val extension: String?
)

data class SeriesInfoResponse(
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?
)

data class Episode(
    @SerializedName("id") val id: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val extension: String?,
    @SerializedName("season") val season: Int?
)

data class EpgResponse(
    @SerializedName("epg_listings") val epgListings: List<EpgProgram>?
)

data class EpgProgram(
    @SerializedName("id") val id: String?,
    @SerializedName("epg_id") val epgId: String?,
    @SerializedName("title") val titleBase64: String?,
    @SerializedName("lang") val lang: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    @SerializedName("description") val descriptionBase64: String?,
    @SerializedName("channel_id") val channelId: String?,
    @SerializedName("start_timestamp") val startTimestamp: Long?,
    @SerializedName("stop_timestamp") val stopTimestamp: Long?
) {
    val decodedTitle: String
        get() {
            if (titleBase64.isNullOrEmpty()) return "Programmazione non disponibile"
            return try {
                val decoded = String(Base64.decode(titleBase64, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.isBlank()) titleBase64 else decoded
            } catch (e: Exception) {
                titleBase64
            }
        }

    val decodedDescription: String
        get() {
            if (descriptionBase64.isNullOrEmpty()) return ""
            return try {
                val decoded = String(Base64.decode(descriptionBase64, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.isBlank()) descriptionBase64 else decoded
            } catch (e: Exception) {
                descriptionBase64
            }
        }
}

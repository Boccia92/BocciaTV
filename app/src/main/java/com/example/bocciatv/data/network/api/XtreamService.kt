package com.example.bocciatv.data.network.api

import com.example.bocciatv.data.model.*
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface XtreamService {
    @GET("player_api.php")
    fun authenticate(
        @Query("username") user: String,
        @Query("password") pass: String
    ): Call<UserAuth>

    @GET("player_api.php")
    fun getCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String
    ): Call<List<Category>>

    @GET("player_api.php")
    fun getStreams(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String,
        @Query("category_id") catId: String? = null
    ): Call<List<StreamItem>>

    @GET("player_api.php")
    fun getSeriesInfo(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: String
    ): Call<SeriesInfoResponse>

    @GET("player_api.php")
    fun getShortEpg(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: String,
        @Query("limit") limit: Int = 5
    ): Call<EpgResponse>
}

package com.example.bocciatv.data.network

import com.example.bocciatv.data.network.api.XtreamService
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val BASE_URL = "http://latteax.securitysc.shop/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "IPTVSmartersPro/3.0.0 (Linux; Android TV)")
                .build()
            chain.proceed(request)
        }
        .build()

    val api: XtreamService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
        .build()
        .create(XtreamService::class.java)
}

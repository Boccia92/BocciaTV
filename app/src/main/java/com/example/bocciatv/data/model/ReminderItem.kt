package com.example.bocciatv.data.model

data class ReminderItem(
    val eventId: String,
    val programTitle: String,
    val channelId: String,
    val channelName: String,
    val streamUrl: String,
    val startTimeMillis: Long
)

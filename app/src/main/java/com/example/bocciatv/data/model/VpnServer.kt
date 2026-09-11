package com.example.bocciatv.data.model

data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val ping: Int,
    val speed: Long,
    val countryLong: String,
    val countryShort: String,
    val configDataBase64: String
) {
    val flagEmoji: String
        get() {
            if (countryShort.length != 2) return "🌐"
            return try {
                val firstLetter = Character.codePointAt(countryShort.uppercase(), 0) - 0x41 + 0x1F1E6
                val secondLetter = Character.codePointAt(countryShort.uppercase(), 1) - 0x41 + 0x1F1E6
                String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
            } catch (e: Exception) {
                "🌐"
            }
        }

    val displayName: String
        get() = "$flagEmoji $countryLong"
}

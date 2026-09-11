package com.example.bocciatv

import com.example.bocciatv.data.model.UpdateInfo
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class UpdateInfoTest {

    @Test
    fun testUpdateInfoJsonParsing() {
        val json = """
            {
                "versionCode": 2,
                "apkUrl": "http://latteax.securitysc.shop/bocciatv.apk",
                "releaseNotes": "- Test Release Notes"
            }
        """.trimIndent()

        val updateInfo = Gson().fromJson(json, UpdateInfo::class.java)

        assertNotNull("UpdateInfo should not be null", updateInfo)
        assertEquals(2, updateInfo.versionCode)
        assertEquals("http://latteax.securitysc.shop/bocciatv.apk", updateInfo.apkUrl)
        assertEquals("- Test Release Notes", updateInfo.releaseNotes)
    }

    @Test
    fun testVersionComparisonLogic() {
        val currentVersionCode = 1

        val updateInfo = UpdateInfo(
            versionCode = 2,
            apkUrl = "http://latteax.securitysc.shop/bocciatv.apk",
            releaseNotes = "Fixes"
        )

        val isUpdateAvailable = updateInfo.versionCode > currentVersionCode
        assertTrue("Update should be available when remote versionCode is higher than current", isUpdateAvailable)

        val sameVersionInfo = UpdateInfo(
            versionCode = 1,
            apkUrl = "http://latteax.securitysc.shop/bocciatv.apk",
            releaseNotes = "Fixes"
        )
        assertFalse("Update should not be available when remote versionCode is equal to current", sameVersionInfo.versionCode > currentVersionCode)
    }
}

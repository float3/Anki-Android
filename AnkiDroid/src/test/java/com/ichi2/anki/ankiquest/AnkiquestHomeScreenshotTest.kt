// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Opt-in visual QA; the normal unit-test task excludes ScreenshotTestCategory. */
@Config(sdk = [33])
class AnkiquestHomeScreenshotTest : ScreenshotTest() {
    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepareHome() {
        val identity = HomeAccount("https://anki.example.test", "sam", "visual-test-token")
        val profile =
            JSONObject()
                .put("level", 4)
                .put("streak", 3)
                .put("xp_into_level", 120)
                .put("xp_for_next", 250)
                .put(
                    "quests",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "A little practice")
                            .put("progress", 6)
                            .put("target", 10)
                            .put("done", false),
                    ),
                )
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        coEvery { AnkiquestHomeData.profile(any()) } returns profile
    }

    @After
    fun clearHomeMocks() {
        unmockkObject(AnkiquestHomeData)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun today() = captureToday()

    @Test
    fun todayLargeText() = captureToday(largeText = true)

    private fun captureToday(largeText: Boolean = false) {
        RuntimeEnvironment.setFontScale(if (largeText) 2f else 1f)
        startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, AnkiquestHomeActivity.intent(targetContext))
        advanceRobolectricLooper()
        val size = if (device == DeviceConfig.PHONE) "320dp" else "default"
        captureScreen("today_${size}${if (largeText) "_200percent" else ""}")
    }
}

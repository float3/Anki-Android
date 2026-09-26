// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestLanguageTest : RobolectricTest() {
    @Test
    fun `Anki language overrides the phone language for widgets and the web session`() {
        AnkiDroidApp.sharedPrefs().edit { putString("language", "es-ES") }
        assertEquals("es-ES", AnkiquestLanguage.tag())
        assertEquals("esta semana", AnkiquestLanguage.context(targetContext).getString(R.string.ankiquest_period_week))
        val session = AnkiquestWebSession("https://quest.example/#cerro", "cerro", "secret", AnkiquestLanguage.tag())
        val script = assertNotNull(session.script("https://quest.example/"))
        assertTrue(script.contains("window.ankiquestLanguage = \"es-ES\""))
        assertNull(session.script("https://elsewhere.example/"))
        assertTrue(session != session.copy(language = "en"))
    }

    @Test
    fun `unsupported languages keep a usable English fallback`() {
        AnkiDroidApp.sharedPrefs().edit { putString("language", "fr") }
        assertEquals("fr", AnkiquestLanguage.tag())
        assertEquals("Profile picture", AnkiquestLanguage.context(targetContext).getString(R.string.ankiquest_avatar_title))
    }

    @Test
    fun `Spanish resources cover settings home navigation and widgets`() {
        val spanish =
            targetContext.createConfigurationContext(
                Configuration(targetContext.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag("es-ES"))) },
            )
        assertEquals("Clasificación", spanish.getString(R.string.ankiquest_dashboard_title))
        assertEquals("Foto de perfil", spanish.getString(R.string.ankiquest_avatar_title))
        assertEquals("Amigos", spanish.getString(R.string.ankiquest_nav_friends))
        assertEquals("Misiones diarias", spanish.getString(R.string.aq_home_quests))
        assertEquals("esta semana", spanish.getString(R.string.ankiquest_period_week))
        assertEquals("Notificaciones que recibo", spanish.getString(R.string.ankiquest_subscriptions_title))
    }
}

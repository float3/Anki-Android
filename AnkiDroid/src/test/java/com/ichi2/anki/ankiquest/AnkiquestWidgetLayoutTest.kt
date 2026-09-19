// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Resource checks run without an Android runtime and cover the widget's scrolling viewport. */
@RunWith(Parameterized::class)
class AnkiquestWidgetLayoutTest(
    private val layoutName: String,
) {
    @Test
    fun `leaderboard scrolls within the available widget height`() {
        val root = widgetLayout()
        val list = leaderboard(root)

        assertEquals("LinearLayout", root.tagName)
        assertEquals("vertical", root.android("orientation"))
        assertEquals(root, list.parentNode, "The leaderboard must fill the space below the fixed header")
        assertEquals("0dp", list.android("layout_height"), "An unbounded list can clip people below the widget")
        assertTrue(list.android("layout_weight").toFloatOrNull()?.let { it > 0 } == true)
        assertEquals("match_parent", list.android("layout_width"))
        assertTrue(list.android("scrollbars") != "none", "Scrolling should remain discoverable")
    }

    @Test
    fun `title and refresh remain accessible while the leaderboard scrolls`() {
        val root = widgetLayout()
        val list = leaderboard(root)
        val title = root.descendants().singleOrNull { it.android("text") == "@string/ankiquest_screen_title" }
        val refresh = root.descendants().singleOrNull { it.android("id") == "@+id/ankiquest_widget_refresh" }

        for (control in listOf(assertNotNull(title), assertNotNull(refresh))) {
            assertTrue(control !in list.descendants(), "The title and refresh control must stay outside the scrolling rows")
            assertTrue(
                control.compareDocumentPosition(list).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt() != 0,
                "The title and refresh control must appear above the leaderboard",
            )
        }
    }

    private fun leaderboard(root: Element): Element =
        assertNotNull(
            root.descendants().singleOrNull { it.tagName == "ListView" },
            "The leaderboard must use a vertically scrollable ListView so people below the visible rows remain reachable",
        )

    private fun widgetLayout(): Element {
        val path = "src/main/res/layout/$layoutName.xml"
        val layout = listOf(File(path), File("AnkiDroid", path)).firstOrNull { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(assertNotNull(layout, "Cannot locate $layoutName.xml")).documentElement
    }

    private fun Element.android(name: String): String = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun Element.descendants(): List<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun layouts(): List<String> = listOf("widget_ankiquest", "widget_ankiquest_transparent")
    }
}

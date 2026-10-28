package com.angelminga.iconifypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSetTest {

    @Test
    fun `parses a collection response`() {
        val set = IconSet.parse(
            """
            {"prefix":"mdi","total":4,"title":"Material Design Icons",
             "uncategorized":["zzz"],
             "categories":{"Home":["home","home-outline"],"Arrows":["arrow-left"]},
             "hidden":["old-icon"],
             "aliases":{"house":"home"}}
            """.trimIndent(),
        )
        assertEquals(listOf("arrow-left", "home", "home-outline", "zzz"), set.visible)
        assertTrue(set.all.containsAll(listOf("home", "zzz", "old-icon", "house")))
        assertEquals(6, set.all.size)
    }

    @Test
    fun `sets without categories or aliases`() {
        val set = IconSet.parse("""{"prefix":"x","uncategorized":["b","a"]}""")
        assertEquals(listOf("a", "b"), set.visible)
        assertEquals(setOf("a", "b"), set.all)
    }
}

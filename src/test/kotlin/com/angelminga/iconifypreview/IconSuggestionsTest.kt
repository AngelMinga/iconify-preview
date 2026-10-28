package com.angelminga.iconifypreview

import org.junit.Assert.assertEquals
import org.junit.Test

class IconSuggestionsTest {

    private val names = listOf("home", "home-outline", "home-variant", "settings", "settings-outline", "account", "arrow-left")

    @Test
    fun `swapped letters count as one edit`() {
        assertEquals(1, IconSuggestions.distance("hoem", "home"))
        assertEquals(listOf("home"), IconSuggestions.similar("hoem", names).take(1))
    }

    @Test
    fun `missing letters`() {
        assertEquals("settings", IconSuggestions.similar("setings", names).first())
        assertEquals("home-outline", IconSuggestions.similar("home-outlin", names).first())
    }

    @Test
    fun `nothing for unrelated names`() {
        assertEquals(emptyList<String>(), IconSuggestions.similar("bank", names))
    }

    @Test
    fun `at most the limit, closest first`() {
        assertEquals(listOf("home", "homey"), IconSuggestions.similar("hom", listOf("homey", "home-outline", "home"), limit = 5))
        assertEquals(1, IconSuggestions.similar("homee", names, limit = 1).size)
    }
}

package com.angelminga.iconifypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconTypingTest {

    /** Text with `|` marking the caret → what is being typed there. */
    private fun typed(textWithCaret: String): TypedIcon? {
        val offset = textWithCaret.indexOf('|')
        return IconTyping.typedAt(textWithCaret.removeRange(offset, offset + 1), offset)
    }

    @Test
    fun `reads the prefix and partial name after a quote`() {
        val t = typed("""<Icon icon="mdi:arr|" />""")!!
        assertEquals("mdi:arr", t.text)
        assertEquals("mdi", t.prefix)
        assertEquals("arr", t.partialName)
    }

    @Test
    fun `plain words have no prefix`() {
        val t = typed("""icon={'wallet|'}""")!!
        assertEquals("wallet", t.text)
        assertNull(t.prefix)
        assertEquals("""icon={""".length, t.quoteOffset)
    }

    @Test
    fun `just after the colon the partial name is empty`() {
        val t = typed("""`material-symbols:|`""")!!
        assertEquals("material-symbols", t.prefix)
        assertEquals("", t.partialName)
    }

    @Test
    fun `nothing outside quoted icon-like text`() {
        assertNull(typed("""const mdi|"""))
        assertNull(typed("""title="Hello wor|""""))
        assertNull(typed(""""a:b:c|""""))
    }

    @Test
    fun `name end covers the rest of the name`() {
        val text = """"mdi:arrow-left" """
        assertEquals(text.indexOf("\" "), IconTyping.nameEnd(text, 5))
    }

    @Test
    fun `icon properties are recognized`() {
        for (before in listOf("""<Icon icon="""", """:icon="""", """startIcon={'""", """{ icon: '""", """icon = `""")) {
            assertTrue(before, IconTyping.isIconContext(before, before.length - 1))
        }
        for (before in listOf("""title="""", """className={'""", """const label = '""")) {
            assertFalse(before, IconTyping.isIconContext(before, before.length - 1))
        }
    }

    @Test
    fun `matching with and without a prefix`() {
        assertTrue(IconTyping.matches("mdi:arr", "mdi:chevron-arrow"))
        assertTrue(IconTyping.matches("mdi:", "mdi:home"))
        assertFalse(IconTyping.matches("mdi:arr", "mdi-light:arrow"))
        assertFalse(IconTyping.matches("mdi:arr", "ic:arrow"))
        assertTrue(IconTyping.matches("wallet", "solar:wallet-bold"))
        assertTrue(IconTyping.matches("md", "mdi:"))
    }
}

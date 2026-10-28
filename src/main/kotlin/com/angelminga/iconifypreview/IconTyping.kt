package com.angelminga.iconifypreview

/** What the user is typing inside a quoted string, e.g. `mdi:arr` in `icon="mdi:arr|`. No IntelliJ dependencies. */
data class TypedIcon(val text: String, val quoteOffset: Int) {
    val prefix: String? get() = text.substringBefore(':', "").ifEmpty { null }?.takeIf { ':' in text }
    val partialName: String get() = text.substringAfter(':', "")
}

object IconTyping {

    fun isIconChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == ':'

    /** The icon-like text right before [offset], if it starts just after a quote and has at most one colon. */
    fun typedAt(text: CharSequence, offset: Int): TypedIcon? {
        if (offset > text.length) return null
        var start = offset
        while (start > 0 && isIconChar(text[start - 1])) start--
        if (start == 0 || text[start - 1] !in QUOTES) return null
        val typed = text.substring(start, offset)
        if (typed.count { it == ':' } > 1) return null
        return TypedIcon(typed, start - 1)
    }

    /** End of the icon-like text that starts at [offset], so completion replaces the whole name. */
    fun nameEnd(text: CharSequence, offset: Int): Int {
        var end = offset
        while (end < text.length && isIconChar(text[end])) end++
        return end
    }

    /**
     * Whether the string opening at [quoteOffset] is the value of an icon property: `icon="`, `:icon="`,
     * `startIcon={'`, `icon: '`. Only there does completion search icons by plain words, so other strings are left alone.
     */
    fun isIconContext(text: CharSequence, quoteOffset: Int): Boolean {
        val before = text.subSequence((quoteOffset - CONTEXT_LENGTH).coerceAtLeast(0), quoteOffset)
        return ICON_PROPERTY.containsMatchIn(before)
    }

    /**
     * Completion matching: with a prefix (`mdi:arr`), names of that set containing the partial name; without one
     * (`wallet`), any id containing the word.
     */
    fun matches(typed: String, id: String): Boolean {
        val colon = typed.indexOf(':')
        if (colon < 0) return id.contains(typed)
        return id.startsWith(typed.substring(0, colon + 1)) && id.substring(colon + 1).contains(typed.substring(colon + 1))
    }

    private const val QUOTES = "\"'`"
    private const val CONTEXT_LENGTH = 40
    private val ICON_PROPERTY = Regex("""[iI]con\w*\s*[=:]\s*\{?\s*$""")
}

package com.angelminga.iconifypreview

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IconUtil
import java.awt.image.BufferedImage
import java.nio.file.Files

/** IconifyClient renders icons by writing the SVG to disk and reading it back with IconLoader. */
class SvgIconLoadingTest : BasePlatformTestCase() {

    fun `test SVG file as served by the API loads and scales`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24">""" +
            """<path fill="currentColor" d="M0 0h24v24H0z"/></svg>"""
        val file = Files.createTempFile("icon", ".svg")
        Files.writeString(file, svg.replace("currentColor", "#ff0000"))

        // The exact size depends on the UI scale (not 1 on headless Linux), so only proportions are checked.
        val icon = IconLoader.findIcon(file.toUri().toURL())!!
        assertTrue("height ${icon.iconHeight}", icon.iconHeight > 0)
        assertEquals(icon.iconHeight, icon.iconWidth)

        val large = IconUtil.scale(icon, null, 4f)
        assertEquals(icon.iconHeight * 4.0, large.iconHeight.toDouble(), 1.0)

        // The fill color must survive: the center pixel is red.
        val image = BufferedImage(large.iconWidth, large.iconHeight, BufferedImage.TYPE_INT_ARGB)
        large.paintIcon(null, image.createGraphics(), 0, 0)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(large.iconWidth / 2, large.iconHeight / 2))
    }
}

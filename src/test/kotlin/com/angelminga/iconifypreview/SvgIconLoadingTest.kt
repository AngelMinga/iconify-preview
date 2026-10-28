package com.angelminga.iconifypreview

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IconUtil
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.Icon

/** IconifyClient renders icons by writing the SVG to disk and reading it back with IconLoader. */
class SvgIconLoadingTest : BasePlatformTestCase() {

    fun `test SVG file as served by the API loads and scales`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24">""" +
            """<path fill="currentColor" d="M0 0h24v24H0z"/></svg>"""
        val file = Files.createTempFile("icon", ".svg")
        Files.writeString(file, svg.replace("currentColor", "#ff0000"))

        // Sizes depend on the UI scale, which differs on headless Linux, so they are not compared exactly.
        val icon = IconLoader.findIcon(file.toUri().toURL())!!
        assertTrue("height ${icon.iconHeight}", icon.iconHeight > 0)
        assertCenterIsRed(icon)

        val large = IconUtil.scale(icon, null, 4f)
        assertTrue("${icon.iconHeight} → ${large.iconHeight}", large.iconHeight > icon.iconHeight)
        assertCenterIsRed(large)
    }

    /** The fill color must survive loading and scaling. */
    private fun assertCenterIsRed(icon: Icon) {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        icon.paintIcon(null, image.createGraphics(), 0, 0)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(icon.iconWidth / 2, icon.iconHeight / 2))
    }
}

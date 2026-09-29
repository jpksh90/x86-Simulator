package x86sim

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x86sim.ui.AppIcon

/** The application icon (specs/008-app-icon/data-model.md). */
class AppIconTest {
    private val resource: (String) -> java.io.InputStream? = { AppIcon::class.java.getResourceAsStream(it) }

    @Test fun `all seven sizes load with alpha`() {
        val images = AppIcon.images
        assertEquals(listOf(16, 24, 32, 48, 64, 128, 256), AppIcon.sizes)
        assertEquals(AppIcon.sizes, images.map { it.width })
        assertEquals(AppIcon.sizes, images.map { it.height })
        assertTrue(images.all { it.colorModel.hasAlpha() })
    }

    @Test fun `bestFor picks the smallest image that is big enough`() {
        val table = mapOf(1 to 16, 16 to 16, 17 to 24, 24 to 24, 51 to 64, 64 to 64, 128 to 128, 160 to 256, 320 to 256)
        for ((px, side) in table) assertEquals(side, AppIcon.bestFor(px)?.width, "bestFor($px)")
    }

    @Test fun `missing resources make the icon unavailable without throwing`() {
        assertEquals(emptyList(), AppIcon.load { null })
        // One missing size means no icon at all, never a partial set.
        assertEquals(emptyList(), AppIcon.load { if (it.endsWith("-64.png")) null else resource(it) })
        assertEquals(emptyList(), AppIcon.load { error("broken jar") })
        assertEquals(emptyList(), AppIcon.load { "not a png".byteInputStream() })
    }

    @Test fun `about icon picks a sharp image for zoom and screen scale`() {
        // (logical size = 64 × zoom, device scale) → image side
        val cases = listOf(Triple(64, 1.0, 64), Triple(64, 2.0, 128), Triple(160, 1.0, 256), Triple(51, 1.0, 64))
        for ((logical, scale, side) in cases) {
            assertEquals(side, AppIcon.bestFor(AppIcon.aboutPixels(logical, scale))?.width, "$logical px at ${scale}x")
        }
        assertEquals(64, AppIcon.aboutPixels(51, 1.25)) // 63.75 rounds up, never down to a blurrier image
    }

    @Test fun `bestFor on an unavailable icon is null`() {
        assertNull(AppIcon.bestFor(64, emptyList<BufferedImage>()))
    }
}

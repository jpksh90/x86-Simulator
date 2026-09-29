package x86sim.ui

import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.math.ceil

/**
 * The app's icon, prepared at seven sizes (resources/icons/x86learn-<size>.png).
 * If any size can't be read, [images] is empty and every window keeps the default icon.
 */
object AppIcon {
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)

    val images: List<BufferedImage> by lazy { load { AppIcon::class.java.getResourceAsStream(it) } }

    /** Reads every size through [open]; empty if any is missing or unreadable (never a partial set). */
    internal fun load(open: (String) -> InputStream?): List<BufferedImage> = try {
        sizes.map { size ->
            open("/icons/x86learn-$size.png")?.use { ImageIO.read(it) } ?: return emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Title bar, taskbar and switcher icon on Windows/Linux; dialogs owned by [w] inherit it. */
    fun installOn(w: Window) {
        if (images.isNotEmpty()) w.iconImages = images
    }

    /** The macOS Dock and ⌘-Tab icon. GUI only: the terminal commands never call this. */
    fun installDockIcon() {
        if (images.isEmpty()) return
        try {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = images.last()
        } catch (_: UnsupportedOperationException) {
        } catch (_: SecurityException) {
        }
    }

    /** Device pixels needed to draw [logical] pixels at [deviceScale] (2.0 on Retina), rounded up. */
    internal fun aboutPixels(logical: Int, deviceScale: Double): Int = ceil(logical * deviceScale).toInt()

    /** The icon for Help → About: 64 px at 100% zoom, drawn from the sharpest size; null when there's no icon. */
    fun aboutIcon(): Icon? {
        if (images.isEmpty()) return null
        val size = Theme.z(64)
        return object : Icon {
            override fun getIconWidth() = size
            override fun getIconHeight() = size
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    val img = bestFor(aboutPixels(size, g2.transform.scaleX)) ?: return
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g2.drawImage(img, x, y, size, size, null)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /** The smallest image at least [px] wide, else the largest; null when there's no icon. */
    internal fun bestFor(px: Int, from: List<BufferedImage> = images): BufferedImage? =
        from.firstOrNull { it.width >= px } ?: from.lastOrNull()
}

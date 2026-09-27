package x86sim

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import x86sim.ui.MainWindow

/** Dev helper: drives the UI through a scenario and saves offscreen renders as PNGs. */
fun main(args: Array<String>) {
    System.setProperty("x86sim.noprefs", "true")
    val out = File(args.getOrElse(0) { "build/snapshots" }).apply { mkdirs() }
    val example = args.getOrElse(1) { "03_factorial" }
    val steps = args.getOrElse(2) { "12" }.toInt()
    val tab = args.getOrElse(3) { "0" }.toInt()
    SwingUtilities.invokeAndWait {
        x86sim.ui.Theme.install()
        if (args.getOrElse(4) { "" } == "light" && x86sim.ui.Theme.isDark ||
            args.getOrElse(4) { "" } == "dark" && !x86sim.ui.Theme.isDark) x86sim.ui.Theme.toggle()
        val w = MainWindow()
        w.setLocation(0, 0)
        w.isVisible = true
        if (example == "ERR") {
            w.loadExample("01_hello")
            w.editor.text = w.editor.text.replace("mov rdx, len", "mov [rdx], len").replace("xor edi, edi", "xor edi, rdi")
        } else if (example == "RUNBP") {
            w.loadExample("03_factorial")
            w.breakpoints += 27          // the 'imul rax, rbx' line
            w.speedSlider.value = w.speedSlider.maximum
            w.run()
        } else if (example.startsWith("FILE:")) {
            w.loadExample("01_hello")
            w.editor.text = File(example.removePrefix("FILE:")).readText()
        } else if (example != "ABOUT") w.loadExample(example.removePrefix("CFG:"))
        repeat(steps) { w.step() }
        repeat(args.getOrElse(5) { "0" }.toInt()) { x86sim.ui.Theme.zoomIn() }
        repeat(args.getOrElse(6) { "0" }.toInt()) { w.stepBack() }
        if (example.startsWith("CFG:")) w.showCfg()
        w.bottomTabs.selectedIndex = tab
        w.validate()
    }
    // The About dialog is modal, so open it later and let its own event loop keep running.
    if (example == "ABOUT") SwingUtilities.invokeLater {
        (java.awt.Frame.getFrames().first { it.isVisible } as MainWindow).showAbout()
    }
    Thread.sleep(300)
    // Step once more after the window is laid out, as a user would.
    if (args.getOrElse(7) { "" } == "after") SwingUtilities.invokeAndWait {
        (java.awt.Frame.getFrames().first { it.isVisible } as MainWindow).step()
    }
    Thread.sleep(500)
    SwingUtilities.invokeAndWait {
        java.awt.Window.getWindows().filter { it.isVisible }.forEachIndexed { i, w ->
            val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            w.printAll(g)
            g.dispose()
            ImageIO.write(img, "png", File(out, "${example.substringAfterLast('/').replace(':', '_')}-$steps-$tab-$i.png"))
        }
    }
    System.exit(0)
}

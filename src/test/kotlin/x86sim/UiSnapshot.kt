package x86sim

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import x86sim.ui.MainWindow

/** Dev helper: drives the UI through a scenario and saves offscreen renders as PNGs. */
fun main(args: Array<String>) {
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
        } else w.loadExample(example.removePrefix("CFG:"))
        repeat(steps) { w.step() }
        if (example.startsWith("CFG:")) w.showCfg()
        w.bottomTabs.selectedIndex = tab
        w.validate()
    }
    Thread.sleep(800)
    SwingUtilities.invokeAndWait {
        java.awt.Frame.getFrames().filter { it.isVisible }.forEachIndexed { i, w ->
            val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            w.printAll(g)
            g.dispose()
            ImageIO.write(img, "png", File(out, "${example.replace(':', '_')}-$steps-$tab-$i.png"))
        }
    }
    System.exit(0)
}

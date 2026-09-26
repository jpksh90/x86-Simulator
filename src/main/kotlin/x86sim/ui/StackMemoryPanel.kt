package x86sim.ui

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ToolTipManager
import x86sim.Machine
import x86sim.analysis.ControlFlowGraph
import x86sim.cpu.Registers

/**
 * A teaching view of the stack: one cell per 64-bit word (the unit push/pop/call/ret
 * move RSP by). Each cell shows its rbp/rsp-relative offset, its value, what it holds
 * (return address, saved rbp, a local written by some line, or reserved-but-unwritten
 * space), which registers point at it, and which function's frame it belongs to.
 */
class StackMemoryPanel(private val machine: Machine) : JPanel(BorderLayout()) {
    private enum class Format(val label: String) { SIGNED("Signed"), UNSIGNED("Unsigned"), HEX("Hex"), CHARS("Chars") }

    private var format = Format.SIGNED
    private var cfg: ControlFlowGraph? = null
    private val highAtTop = JCheckBox("High on top", true).apply {
        toolTipText = "Stack grows downward"
    }
    private val showBytes = JCheckBox("Bytes", false).apply {
        toolTipText = "Show the 8 bytes of each word (little-endian)"
    }
    private val summary = JLabel(" ").apply { Theme.onChange { foreground = Theme.dim; font = Theme.ui(11f) } }
    private val diagram = Diagram()
    private val scroll = JScrollPane(diagram).apply {
        verticalScrollBar.unitIncrement = 16
    }

    init {
        val format = JComboBox(Format.entries.map { it.label }.toTypedArray()).apply {
            toolTipText = "Value format"
            addActionListener { this@StackMemoryPanel.format = Format.entries[selectedIndex]; diagram.repaint() }
        }
        highAtTop.addActionListener { refresh() }
        showBytes.addActionListener { refresh() }
        val title = JPanel(BorderLayout()).apply {
            add(Theme.sectionLabel("Stack · 64-bit cells"), BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply { add(highAtTop); add(showBytes); add(format) }, BorderLayout.EAST)
        }
        val legend = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2)).apply {
            add(swatch({ Theme.changed }, "last write"))
            add(swatch(null, "unwritten"))
            add(swatch({ Theme.freeBg }, "free"))
        }
        add(JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            listOf(title, legend, summary.apply { border = BorderFactory.createEmptyBorder(2, 10, 6, 8) })
                .forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        preferredSize = Dimension(580, 600)
        Theme.onChange { scroll.border = BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.grid); refresh() }
        minimumSize = Dimension(420, 300)
    }

    private fun swatch(c: (() -> Color)?, text: String) = JLabel(text).apply {
        Theme.onChange { foreground = Theme.dim; font = Theme.ui(11f) }
        icon = object : javax.swing.Icon {
            override fun getIconWidth() = 14
            override fun getIconHeight() = 12
            override fun paintIcon(comp: java.awt.Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.paint = c?.invoke() ?: hatch()
                g2.fillRoundRect(x, y, 13, 11, 4, 4)
                g2.color = Theme.cellBorder
                g2.drawRoundRect(x, y, 13, 11, 4, 4)
            }
        }
    }

    fun onProgramLoaded() {
        cfg = machine.program?.let { ControlFlowGraph(it) }
        refresh()
    }

    fun refresh() {
        diagram.rebuild()
        diagram.scrollToRsp()
    }

    // ------------------------------------------------------------------------------

    /** [bp] is the frame's base (the value rbp has while that function runs), or null if it has none. */
    private class Frame(val name: String, val low: Long, val high: Long, val current: Boolean, val bp: Long?)

    private class Word(
        val addr: Long,
        val value: Long,
        val touched: Boolean,
        val free: Boolean,
        val writtenNow: Boolean,
        val writtenBytes: Set<Int>,
        val offset: String,
        val offset2: String,
        val note: String,
        val noteColor: Color,
        val pointers: List<String>,
    )

    private inner class Diagram : JComponent() {
        private var words: List<Word> = emptyList()
        private var frames: List<Frame> = emptyList()

        // Layout in pixels at 100%, scaled with the zoom.
        private val rowH get() = Theme.z(if (showBytes.isSelected) 62 else 44)
        private val top get() = Theme.z(8)
        private val xFrame get() = Theme.z(6)
        private val xOff get() = Theme.z(40)
        private val xCell get() = Theme.z(136)
        private val wCell get() = Theme.z(196)
        private val xNote get() = xCell + wCell + Theme.z(10)

        init {
            ToolTipManager.sharedInstance().registerComponent(this)
            Theme.onChange { background = Theme.surface }
            isOpaque = true
        }

        private fun rsp() = machine.cpu.regs[Registers.RSP]
        private fun rbp() = machine.cpu.regs[Registers.RBP]
        private fun inStack(a: Long) = a >= Machine.STACK_TOP - Machine.STACK_SIZE && a < Machine.STACK_TOP

        private fun functionAt(addr: Long): String =
            cfg?.functionContaining(addr)?.name ?: machine.program?.describeCodeAddress(addr)?.substringBefore('+') ?: "?"

        /** Walks the rbp chain: each frame runs from its lowest word up to and including its return address. */
        private fun computeFrames(): List<Frame> {
            val out = mutableListOf<Frame>()
            var low = rsp()
            var bp = rbp()
            var fn = if (machine.isFinished) "?" else functionAt(machine.cpu.rip)
            var current = true
            var guard = 0
            while (inStack(bp) && bp >= low && guard++ < 200) {
                out += Frame(fn, low, bp + 8, current, bp)
                val ret = machine.memory.peekQword(bp + 8) ?: break
                fn = if (ret == Machine.EXIT_ADDRESS) "(loader)" else functionAt(ret)
                current = false
                low = bp + 16
                bp = machine.memory.peekQword(bp) ?: break
            }
            if (low < Machine.STACK_TOP) out += Frame(fn, low, Machine.STACK_TOP - 8, current, null)
            return out
        }

        fun rebuild() {
            if (machine.program == null) { words = emptyList(); frames = emptyList(); revalidate(); repaint(); return }
            val mem = machine.memory
            val sp = rsp()
            val bp = rbp()
            frames = computeFrames()
            val savedRbps = mutableSetOf<Long>()
            val retSlots = mutableSetOf<Long>()
            run {
                var b = bp; var guard = 0
                while (inStack(b) && b >= sp && guard++ < 200) {
                    savedRbps += b; retSlots += b + 8
                    b = mem.peekQword(b) ?: break
                }
            }
            val lastWrites = mem.writeLog.toList()
            val lines = machine.program?.byLine ?: emptyMap()
            val regs = machine.cpu.regs

            val lowest = maxOf((sp and 7L.inv()) - FREE_WORDS * 8, Machine.STACK_TOP - Machine.STACK_SIZE)
            val count = ((Machine.STACK_TOP - lowest) / 8).toInt().coerceAtMost(MAX_ROWS)
            val list = (0 until count).map { i ->
                val a = lowest + i * 8L
                val value = mem.peekQword(a) ?: 0
                val touched = (0 until 8).any { mem.isTouched(a + it) }
                val free = a < sp
                val bytesNow = lastWrites.flatMap { (wa, ws) -> (0 until ws).map { wa + it } }
                    .filter { it >= a && it < a + 8 }.map { (it - a).toInt() }.toSet()
                // Label each word relative to the base of the frame it belongs to, the way that
                // function's code addresses it (e.g. [rbp-8]); free words use the current frame.
                val frame = frames.firstOrNull { a >= it.low && a <= it.high }
                val base = if (free) frames.firstOrNull()?.bp else frame?.bp
                val offset = if (base != null) rel("rbp", a - base) else rel("rsp", a - sp)
                val offset2 = rel("rsp", a - sp).takeIf { base != null } ?: "…%06x".format(a and 0xFFFFFF)
                val writer = mem.writerOf(a)
                val src = lines[writer]?.source?.substringBefore(';')?.trim()
                val (note, color) = when {
                    free && touched -> "stale" to Theme.dim
                    free -> "free" to Theme.dim
                    a in retSlots -> "ret → ${describeCode(value)}" to Theme.accent
                    a in savedRbps -> "saved rbp" to Theme.synRegister
                    !touched -> "unwritten" to Theme.warn
                    writer < 0 && value == Machine.EXIT_ADDRESS -> "ret → exit" to Theme.accent
                    src != null && src.startsWith("call") -> "ret → ${describeCode(value)}" to Theme.accent
                    src != null -> "L${writer + 1}  $src" to Theme.text
                    else -> "" to Theme.dim
                }
                val pointers = buildList {
                    if (a == sp) add("RSP")
                    if (a == bp) add("RBP")
                    for (r in Registers.displayOrder) {
                        if (r == Registers.RSP || r == Registers.RBP) continue
                        if (regs[r] >= a && regs[r] < a + 8) add(Registers.names64[r] + if (regs[r] != a) "+${regs[r] - a}" else "")
                    }
                }
                Word(a, value, touched, free, bytesNow.isNotEmpty(), bytesNow, offset, offset2, note, color, pointers)
            }
            words = if (highAtTop.isSelected) list.reversed() else list

            val used = Machine.STACK_TOP - sp
            summary.text = "${used / 8} word${if (used / 8 == 1L) "" else "s"} used" +
                (if (inStack(bp)) " · frame ${(bp + 16 - sp) / 8}" else "") +
                " · grows ${if (highAtTop.isSelected) "↓" else "↑"}"
            preferredSize = Dimension(xNote + Theme.z(215), top * 2 + words.size * rowH)
            revalidate(); repaint()
        }

        private fun rel(reg: String, d: Long) = when {
            d == 0L -> reg
            d > 0 -> "$reg+$d"
            else -> "$reg$d"
        }

        private fun describeCode(v: Long): String =
            if (v == Machine.EXIT_ADDRESS) "exit" else machine.program?.describeCodeAddress(v) ?: "0x%x (not code!)".format(v)

        /** Keeps the whole current frame in view: from RSP up to the return address at rbp+8. */
        fun scrollToRsp() {
            val spRow = rowOf(rsp())
            if (spRow < 0) return
            val bp = rbp()
            val retRow = if (inStack(bp) && bp >= rsp()) rowOf(bp + 8) else -1
            val rows = listOf(spRow, retRow).filter { it >= 0 }
            // include one free word below RSP, and the word above the return address, for context
            val first = (rows.min() - 1).coerceAtLeast(0)
            val last = (rows.max() + 1).coerceAtMost(words.size - 1)
            val view = scroll.viewport.viewRect
            val want = Rectangle(0, top + first * rowH, 1, (last - first + 1) * rowH)
            if (want.height > view.height) {
                // Frame taller than the view: show the RSP end of it.
                val y = top + spRow * rowH
                scrollRectToVisible(Rectangle(0, y - view.height + 2 * rowH, 1, view.height))
            } else if (want.y < view.y || want.y + want.height > view.y + view.height) {
                scrollRectToVisible(Rectangle(0, maxOf(0, want.y - (view.height - want.height) / 2), 1, view.height))
            }
        }

        private fun rowOf(addr: Long) = words.indexOfFirst { it.addr == addr }

        private fun formatValue(w: Word): String = when (format) {
            Format.SIGNED -> w.value.toString()
            Format.UNSIGNED -> java.lang.Long.toUnsignedString(w.value)
            Format.HEX -> "0x%x".format(w.value)
            Format.CHARS -> (0 until 8).map { ((w.value ushr (8 * it)) and 0xFF).toInt() }
                .joinToString("") { if (it in 32..126) it.toChar().toString() else "·" }
        }

        override fun getToolTipText(e: MouseEvent): String? {
            val i = (e.y - top) / rowH
            val w = words.getOrNull(i) ?: return null
            val frame = frames.firstOrNull { w.addr >= it.low && w.addr <= it.high }
            val whose = if (frame?.bp != null && !frame.current && w.offset.startsWith("rbp"))
                "<br><i>offset from ${frame.name}'s frame base (its rbp = 0x%x)</i>".format(frame.bp) else ""
            return "<html><b>${w.offset}</b> = 0x%x%s<br>value: %d (0x%016x)<br>%s</html>".format(
                w.addr, whose, w.value, w.value, w.note.replace("<", "&lt;"))
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = background
            g.fillRect(0, 0, width, height)
            if (words.isEmpty()) {
                g.color = Theme.dim
                g.drawString("Build a program to see its stack", Theme.z(12), Theme.z(24))
                return
            }
            val mono = Theme.mono
            val small = Theme.mono(10.5f)
            val bold = Theme.monoBold

            // Frame brackets.
            frames.forEachIndexed { k, f ->
                val r1 = rowOf(f.low); val r2 = rowOf(f.high)
                if (r1 < 0 || r2 < 0) return@forEachIndexed
                val a = minOf(r1, r2); val b = maxOf(r1, r2)
                val y1 = top + a * rowH + Theme.z(2); val y2 = top + (b + 1) * rowH - Theme.z(2)
                val col = Theme.frameColor(k)
                g.color = col
                g.fillRoundRect(xFrame, y1, Theme.z(24), y2 - y1, Theme.z(8), Theme.z(8))
                if (f.current) {
                    g.color = Theme.accent
                    g.stroke = BasicStroke(2f)
                    g.drawRoundRect(xFrame, y1, Theme.z(24), y2 - y1, Theme.z(8), Theme.z(8))
                }
                // function name, rotated along the bracket
                val label = (if (f.current) "▶ " else "") + f.name
                val old = g.transform
                g.font = Theme.mono(11f, Font.BOLD)
                val fm = g.fontMetrics
                val cy = (y1 + y2) / 2
                g.transform(AffineTransform.getRotateInstance(-Math.PI / 2, (xFrame + Theme.z(12)).toDouble(), cy.toDouble()))
                g.color = Theme.text
                val text = if (fm.stringWidth(label) > y2 - y1 - 6) label.take(maxOf(1, (y2 - y1 - 6) / fm.charWidth('m'))) else label
                g.drawString(text, xFrame + Theme.z(12) - fm.stringWidth(text) / 2, cy + fm.ascent / 2 - 1)
                g.transform = old
            }

            val sp = rsp()
            words.forEachIndexed { i, w ->
                val y = top + i * rowH
                // divider at the RSP boundary
                val boundary = if (highAtTop.isSelected) w.addr == sp else w.addr == sp - 8
                if (boundary) {
                    g.color = Theme.accent
                    g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(6f, 4f), 0f)
                    val ly = if (highAtTop.isSelected) y + rowH else y + rowH
                    g.drawLine(xOff - Theme.z(4), ly, width, ly)
                    g.stroke = BasicStroke(1f)
                }

                // offset + address
                g.font = if (w.pointers.contains("RBP") || w.pointers.contains("RSP")) bold else mono
                g.color = if (w.free) Theme.dim else Theme.text
                g.drawString(w.offset, xOff, y + Theme.z(18))
                g.font = small
                g.color = Theme.dim
                g.drawString(w.offset2, xOff, y + Theme.z(34))

                // the word cell
                val cell = Rectangle(xCell, y + Theme.z(3), wCell, rowH - Theme.z(6))
                g.paint = when {
                    w.free -> Theme.freeBg
                    w.writtenNow -> Theme.changed
                    !w.touched -> hatch()
                    else -> Theme.cellBg
                }
                g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, Theme.z(6), Theme.z(6))
                g.color = if (w.writtenNow) Theme.changedStrong else Theme.cellBorder
                g.stroke = BasicStroke(if (w.writtenNow) 2f else 1f)
                g.drawRoundRect(cell.x, cell.y, cell.width, cell.height, Theme.z(6), Theme.z(6))
                g.stroke = BasicStroke(1f)

                g.font = bold
                g.color = when { w.free -> Theme.dim; !w.touched -> Theme.warn; else -> Theme.text }
                g.drawString(if (!w.touched && !w.free) "?" else formatValue(w), cell.x + Theme.z(8), y + Theme.z(18))
                g.font = small
                g.color = Theme.dim
                g.drawString("%016x".format(w.value), cell.x + Theme.z(8), y + Theme.z(33))

                if (showBytes.isSelected) {
                    val bw = (wCell - Theme.z(12)) / 8
                    for (b in 0 until 8) {
                        val bx = cell.x + Theme.z(6) + b * bw
                        val by = y + Theme.z(39)
                        val byteTouched = machine.memory.isTouched(w.addr + b)
                        g.color = when {
                            b in w.writtenBytes -> Theme.changedStrong
                            !byteTouched && !w.free -> Theme.hatchBg
                            else -> Theme.surface
                        }
                        g.fillRect(bx, by, bw - 2, Theme.z(16))
                        g.color = Theme.grid
                        g.drawRect(bx, by, bw - 2, Theme.z(16))
                        g.font = small
                        g.color = if (w.free) Theme.dim else Theme.text
                        val s = if (byteTouched || w.free) "%02x".format((w.value ushr (8 * b)) and 0xFF) else "??"
                        g.drawString(s, bx + (bw - 2 - g.fontMetrics.stringWidth(s)) / 2, by + Theme.z(12))
                    }
                }

                // pointers (line 1) and note (line 2)
                if (w.pointers.isNotEmpty()) {
                    var px = xNote
                    g.font = Theme.mono(11.5f, Font.BOLD)
                    for (p in w.pointers) {
                        val label = "◀ $p"
                        g.color = when (p) { "RSP" -> Theme.accent; "RBP" -> Theme.synRegister; else -> Theme.synDirective }
                        g.drawString(label, px, y + Theme.z(18))
                        px += g.fontMetrics.stringWidth(label) + Theme.z(10)
                    }
                }
                g.font = Theme.mono(11f)
                g.color = w.noteColor
                val fm = g.fontMetrics
                var note = w.note
                val maxW = width - xNote - Theme.z(6)
                if (fm.stringWidth(note) > maxW) {
                    while (note.isNotEmpty() && fm.stringWidth("$note…") > maxW) note = note.dropLast(1)
                    note += "…"
                }
                g.drawString(note, xNote, y + Theme.z(if (w.pointers.isEmpty()) 26 else 34))
            }
        }
    }

    companion object {
        private const val FREE_WORDS = 3
        private const val MAX_ROWS = 400
        /** Diagonal hatching for reserved-but-unwritten cells, in the current theme's colours. */
        private fun hatch(): TexturePaint {
            val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Theme.hatchBg; g.fillRect(0, 0, 8, 8)
            g.color = Theme.hatchLine; g.drawLine(0, 8, 8, 0)
            g.dispose()
            return TexturePaint(img, Rectangle(0, 0, 8, 8))
        }
    }
}

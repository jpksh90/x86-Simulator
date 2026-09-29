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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import x86sim.Machine
import x86sim.analysis.BasicBlock
import x86sim.analysis.ControlFlowGraph
import x86sim.analysis.Edge
import x86sim.analysis.EdgeKind
import x86sim.analysis.FunctionGraph

/**
 * Shows the control flow graph of one function at a time. Blocks are stacked in address
 * order; an edge to the very next block is a straight arrow down, other forward jumps are
 * routed on the right and backward jumps (loops) on the left.
 */
class CfgWindow(
    owner: JFrame,
    private val machine: Machine,
    private val breakpoints: Set<Int>,
    private val onSelectLine: (Int) -> Unit,
) : JFrame("Control Flow Graph") {

    private var cfg: ControlFlowGraph? = null
    private val functionBox = JComboBox<String>()
    private val followBox = JCheckBox("Follow", true).apply {
        toolTipText = "Show the running function"
    }
    private val zoom = JSlider(50, 150, 100).apply {
        preferredSize = Dimension(120, 24)
        toolTipText = "Zoom"
    }
    private val info = JLabel(" ").apply { foreground = Theme.dim }
    private val canvas = Canvas()
    private var updatingBox = false

    init {
        AppIcon.installOn(this)
        val legend = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            add(legendItem({ Theme.edgeTaken }, "taken"))
            add(legendItem({ Theme.edgeNotTaken }, "not taken"))
            add(legendItem({ Theme.edgeAlways }, "jump"))
            add(legendItem({ Theme.accent }, "current", thick = true))
        }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            add(functionBox); add(followBox)
            add(JLabel("Zoom").apply { Theme.onChange { foreground = Theme.dim } }); add(zoom)
        }
        val top = JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(legend.apply { border = BorderFactory.createEmptyBorder(0, 4, 6, 4) }, BorderLayout.SOUTH)
        }
        functionBox.addActionListener { if (!updatingBox) { followBox.isSelected = false; rebuildCanvas() } }
        followBox.addActionListener { refresh() }
        zoom.addChangeListener { canvas.relayout() }
        Theme.onChange { canvas.relayout() }

        contentPane = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JScrollPane(canvas).apply {
                border = BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.grid)
                verticalScrollBar.unitIncrement = 16
                horizontalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
            add(info.apply { border = BorderFactory.createEmptyBorder(5, 12, 5, 12); Theme.onChange { foreground = Theme.dim } }, BorderLayout.SOUTH)
        }
        size = Dimension(760, 820)
        setLocation(owner.x + owner.width - 780, owner.y + 60)
        defaultCloseOperation = HIDE_ON_CLOSE
    }

    private fun legendItem(c: () -> Color, text: String, thick: Boolean = false) = JLabel(text).apply {
        Theme.onChange { foreground = Theme.dim }
        border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
        icon = object : javax.swing.Icon {
            override fun getIconWidth() = 22
            override fun getIconHeight() = 10
            override fun paintIcon(comp: java.awt.Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g as Graphics2D
                g2.color = c()
                g2.stroke = BasicStroke(if (thick) 3f else 2f)
                g2.drawLine(x + 1, y + 5, x + 20, y + 5)
            }
        }
        Theme.onChange { font = Theme.ui(11f) }
    }

    /** Call after assembling: rebuilds the graph for the new program. */
    fun programChanged() {
        val p = machine.program
        cfg = p?.let { ControlFlowGraph(it) }
        updatingBox = true
        functionBox.removeAllItems()
        cfg?.functions?.forEach { functionBox.addItem(it.name) }
        updatingBox = false
        followBox.isSelected = true
        refresh()
    }

    /** Call after every step: highlights the current block and follows execution. */
    fun refresh() {
        val g = cfg ?: run { canvas.show(null); return }
        if (followBox.isSelected) {
            val f = g.functionContaining(machine.cpu.rip)
            if (f != null && functionBox.selectedItem != f.name) {
                updatingBox = true
                functionBox.selectedItem = f.name
                updatingBox = false
            }
        }
        rebuildCanvas()
    }

    private fun rebuildCanvas() {
        val g = cfg ?: return
        val f = g.functions.getOrNull(functionBox.selectedIndex) ?: g.functions.firstOrNull()
        canvas.show(f)
        info.text = if (f == null) " " else "${f.blocks.size} blocks · ${f.edges.size} edges · click to jump to code"
    }

    // ------------------------------------------------------------------------------

    private inner class Canvas : JPanel() {
        private var fn: FunctionGraph? = null
        private val boxes = LinkedHashMap<Int, Rectangle>()          // block id -> bounds
        private val lineRows = mutableListOf<Pair<Rectangle, Int>>()   // instruction row -> source line
        private val routes = mutableListOf<Pair<Edge, Int>>()          // side-routed edge -> x of its lane
        private var codeFont = Theme.monoSmall
        private var headFont = Theme.monoBold

        private val scale get() = zoom.value / 100f * Theme.zoom
        private val lineH get() = (17 * scale).toInt()
        private val pad get() = (8 * scale).toInt()
        private val gap get() = (40 * scale).toInt()
        private val laneStep get() = (14 * scale).toInt()

        init {
            Theme.onChange { background = Theme.surface }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    lineRows.firstOrNull { it.first.contains(e.point) }?.let { onSelectLine(it.second) }
                }
            })
        }

        fun show(f: FunctionGraph?) {
            val same = f?.name == fn?.name && f?.blocks?.size == fn?.blocks?.size
            fn = f
            if (!same) relayout() else repaint()
            scrollToCurrent()
        }

        fun relayout() {
            codeFont = Theme.mono(12f * zoom.value / 100f)
            headFont = Theme.mono(12f * zoom.value / 100f, Font.BOLD)
            boxes.clear(); routes.clear()
            val f = fn ?: run { preferredSize = Dimension(10, 10); revalidate(); repaint(); return }
            val fm = getFontMetrics(codeFont)
            val order = f.blocks.map { it.id }
            val pos = order.withIndex().associate { it.value to it.index }

            // Box sizes.
            val sizes = f.blocks.associate { b ->
                val w = (listOf(header(b)) + b.instructions.map { text(it.source) } + footer(b).let { if (it == null) emptyList() else listOf(it) })
                    .maxOf { fm.stringWidth(it) } + pad * 2 + (10 * scale).toInt()
                val rows = b.instructions.size + 1 + (if (footer(b) != null) 1 else 0)
                b.id to Dimension(maxOf(w, (160 * scale).toInt()), rows * lineH + pad * 2)
            }
            val maxW = sizes.values.maxOf { it.width }

            // Lanes for edges that don't go straight to the next block.
            val side = f.edges.filter { pos.getValue(it.to) != pos.getValue(it.from) + 1 }
            fun lanes(list: List<Edge>): Map<Edge, Int> {
                val used = mutableListOf<MutableList<IntRange>>()
                val out = HashMap<Edge, Int>()
                for (e in list.sortedBy { kotlin.math.abs(pos.getValue(it.to) - pos.getValue(it.from)) }) {
                    val a = pos.getValue(e.from); val b = pos.getValue(e.to)
                    val span = minOf(a, b)..maxOf(a, b)
                    var lane = used.indexOfFirst { l -> l.none { it.first <= span.last && span.first <= it.last } }
                    if (lane < 0) { used += mutableListOf<IntRange>(); lane = used.size - 1 }
                    used[lane] += span
                    out[e] = lane
                }
                return out
            }
            val back = lanes(side.filter { pos.getValue(it.to) <= pos.getValue(it.from) })
            val fwd = lanes(side.filter { pos.getValue(it.to) > pos.getValue(it.from) })
            val leftRoom = ((back.values.maxOrNull() ?: -1) + 1) * laneStep + (30 * scale).toInt()
            val rightRoom = ((fwd.values.maxOrNull() ?: -1) + 1) * laneStep + (30 * scale).toInt()

            val cx = leftRoom + maxW / 2 + (10 * scale).toInt()
            var y = (20 * scale).toInt()
            for (b in f.blocks) {
                val d = sizes.getValue(b.id)
                boxes[b.id] = Rectangle(cx - d.width / 2, y, d.width, d.height)
                y += d.height + gap
            }
            val boxLeft = cx - maxW / 2; val boxRight = cx + maxW / 2
            back.forEach { (e, lane) -> routes += e to (boxLeft - (18 * scale).toInt() - lane * laneStep) }
            fwd.forEach { (e, lane) -> routes += e to (boxRight + (18 * scale).toInt() + lane * laneStep) }

            preferredSize = Dimension(boxRight + rightRoom + (10 * scale).toInt(), y)
            revalidate(); repaint()
        }

        private fun text(src: String) = src.substringBefore(';').trim()
        private fun header(b: BasicBlock): String {
            val f = fn
            val n = b.name?.let { if (f != null && it.startsWith(f.name + ".")) it.removePrefix(f.name) else it }
            return (n?.let { "$it:" } ?: "") + "  0x%x".format(b.start)
        }
        private fun footer(b: BasicBlock): String? = when (b.exit) {
            null -> null
            "ret" -> "↩ return"
            "exit" -> "■ exit"
            "hlt" -> "■ halt"
            else -> "⚠ ${b.exit}"
        }

        fun scrollToCurrent() {
            val b = currentBlock() ?: return
            boxes[b.id]?.let { scrollRectToVisible(Rectangle(it.x, it.y - 20, it.width, it.height + 40)) }
        }

        private fun currentBlock(): BasicBlock? {
            if (machine.program == null || machine.isFinished) return null
            val f = fn ?: return null
            return f.blocks.firstOrNull { b -> b.instructions.any { it.address == machine.cpu.rip } }
        }

        private fun color(k: EdgeKind) = when (k) {
            EdgeKind.TAKEN -> Theme.edgeTaken
            EdgeKind.NOT_TAKEN -> Theme.edgeNotTaken
            EdgeKind.ALWAYS -> Theme.edgeAlways
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val f = fn
            if (f == null) {
                g.color = Theme.dim
                g.drawString("Build a program to see its graph", 20, 30)
                return
            }
            lineRows.clear()
            val current = currentBlock()
            val routed = routes.toMap()

            // Edges first, so boxes sit on top of them.
            val stroke = BasicStroke(1.8f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (e in f.edges) {
                val a = boxes[e.from] ?: continue
                val b = boxes[e.to] ?: continue
                g.color = color(e.kind)
                g.stroke = stroke
                val lane = routed[e]
                if (lane == null) {
                    // Straight down to the next block. Offset when two edges share this path.
                    val dx = if (e.kind == EdgeKind.NOT_TAKEN) -(12 * scale).toInt() else 0
                    val x = a.centerX.toInt() + dx
                    g.drawLine(x, a.y + a.height, x, b.y - 2)
                    arrowDown(g, x, b.y)
                } else {
                    val leftSide = lane < a.x
                    val sx = if (leftSide) a.x else a.x + a.width
                    val tx = if (leftSide) b.x else b.x + b.width
                    val sy = a.y + a.height - (10 * scale).toInt()
                    val ty = b.y + (10 * scale).toInt()
                    val p = Path2D.Double().apply {
                        moveTo(sx.toDouble(), sy.toDouble())
                        lineTo(lane.toDouble(), sy.toDouble())
                        lineTo(lane.toDouble(), ty.toDouble())
                        lineTo(tx.toDouble(), ty.toDouble())
                    }
                    g.draw(p)
                    arrowSide(g, tx, ty, pointingRight = leftSide)
                }
            }

            // Blocks.
            val fm = g.getFontMetrics(codeFont)
            for (bl in f.blocks) {
                val r = boxes[bl.id] ?: continue
                val isCur = bl.id == current?.id
                g.color = Theme.shadow
                g.fillRoundRect(r.x + 2, r.y + 3, r.width, r.height, 10, 10)
                g.color = Theme.cellBg
                g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10)
                // header band
                g.color = if (isCur) Theme.accentSoft else Theme.surfaceAlt
                g.fillRoundRect(r.x, r.y, r.width, lineH + pad, 10, 10)
                g.fillRect(r.x, r.y + lineH, r.width, pad)
                g.font = headFont
                g.color = Theme.synLabel
                var y = r.y + pad + fm.ascent - 2
                g.drawString(header(bl), r.x + pad, y)
                y += lineH
                g.font = codeFont
                for (ins in bl.instructions) {
                    val row = Rectangle(r.x + 1, y - fm.ascent - 1, r.width - 2, lineH)
                    lineRows += row to ins.line
                    if (isCur && ins.address == machine.cpu.rip) {
                        g.color = Theme.currentLine
                        g.fillRect(row.x, row.y, row.width, row.height)
                        g.color = Theme.currentLineArrow
                        g.drawString("▶", r.x + 2, y)
                    }
                    if (ins.line in breakpoints) {
                        g.color = Theme.breakpoint
                        val d = (7 * scale).toInt()
                        g.fillOval(r.x + r.width - d - 5, y - fm.ascent / 2 - d / 2 - 1, d, d)
                    }
                    val t = text(ins.source)
                    val m = t.substringBefore(' ')
                    g.color = if (ControlFlowGraph.isJump(m) || m == "ret" || m == "call") Theme.synMnemonic else Theme.text
                    g.font = if (m == "call" || ControlFlowGraph.isJump(m)) codeFont.deriveFont(Font.BOLD) else codeFont
                    g.drawString(t, r.x + pad + (10 * scale).toInt(), y)
                    y += lineH
                }
                footer(bl)?.let {
                    g.font = codeFont.deriveFont(Font.ITALIC)
                    g.color = if (bl.exit == "ret" || bl.exit == "exit") Theme.synDirective else Theme.warn
                    g.drawString(it, r.x + pad, y)
                }
                g.color = if (isCur) Theme.accent else Theme.cellBorder
                g.stroke = BasicStroke(if (isCur) 3f else 1.2f)
                g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10)
            }
        }

        private fun arrowDown(g: Graphics2D, x: Int, y: Int) {
            val s = (7 * scale).toInt()
            g.fillPolygon(intArrayOf(x - s / 2 - 1, x + s / 2 + 1, x), intArrayOf(y - s, y - s, y), 3)
        }

        private fun arrowSide(g: Graphics2D, x: Int, y: Int, pointingRight: Boolean) {
            val s = (7 * scale).toInt()
            val bx = if (pointingRight) x - s else x + s
            g.fillPolygon(intArrayOf(bx, bx, x), intArrayOf(y - s / 2 - 1, y + s / 2 + 1, y), 3)
        }
    }

}

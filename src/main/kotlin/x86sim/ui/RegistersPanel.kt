package x86sim.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import x86sim.Machine
import x86sim.cpu.Registers

/** Snapshot of everything the register view shows, used to highlight what changed. */
data class CpuSnapshot(val regs: List<Long>, val rip: Long, val flags: Map<String, Boolean>) {
    companion object {
        fun of(m: Machine) = CpuSnapshot(m.cpu.regs.toList(), m.cpu.rip, mapOf(
            "CF" to m.cpu.cf, "PF" to m.cpu.pf, "AF" to m.cpu.af, "ZF" to m.cpu.zf,
            "SF" to m.cpu.sf, "DF" to m.cpu.df, "OF" to m.cpu.of))
    }
}

class RegistersPanel(private val machine: Machine) : JPanel(BorderLayout()) {
    private enum class Format(val label: String) { SIGNED("Signed"), UNSIGNED("Unsigned"), CHARS("Chars") }

    private var before: CpuSnapshot? = null
    private var format = Format.SIGNED
    private val rows = Registers.displayOrder.map { Registers.names64[it] to it } + listOf("rip" to -1, "rflags" to -2)

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(c: Int) = listOf("Reg", "Hex", format.label)[c]
        override fun getValueAt(r: Int, c: Int): Any {
            val (name, idx) = rows[r]
            val v = value(idx)
            return when (c) {
                0 -> name
                1 -> Theme.hex64(v)
                else -> when {
                    idx < 0 -> if (idx == -1) machine.program?.describeCodeAddress(v) ?: "" else flagString()
                    format == Format.SIGNED -> v.toString()
                    format == Format.UNSIGNED -> java.lang.Long.toUnsignedString(v)
                    else -> chars(v)
                }
            }
        }
    }

    private fun value(idx: Int) = when (idx) {
        -1 -> machine.cpu.rip
        -2 -> machine.cpu.rflags
        else -> machine.cpu.regs[idx]
    }

    private fun changed(row: Int): Boolean {
        val b = before ?: return false
        val idx = rows[row].second
        return when (idx) {
            -1 -> false
            -2 -> b.flags != CpuSnapshot.of(machine).flags
            else -> b.regs[idx] != machine.cpu.regs[idx]
        }
    }

    private fun flagString() = CpuSnapshot.of(machine).flags.filterValues { it }.keys.joinToString(" ").ifEmpty { "—" }

    private fun chars(v: Long) = (0 until 8).map { ((v ushr (8 * it)) and 0xFF).toInt() }
        .joinToString("") { if (it in 32..126) it.toChar().toString() else "·" }

    private val table = JTable(tableModel).apply {
        font = Theme.mono
        rowHeight = font.size + 8
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        tableHeader.reorderingAllowed = false
        fillsViewportHeight = true
        columnModel.getColumn(0).preferredWidth = 55
        columnModel.getColumn(1).preferredWidth = 150
        columnModel.getColumn(2).preferredWidth = 150
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, v, sel, false, row, col)
                font = if (col == 0) Theme.monoBold else Theme.mono
                border = javax.swing.BorderFactory.createEmptyBorder(0, 10, 0, 6)
                val (name, idx) = rows[row]
                toolTipText = Docs.registers[name]
                if (!sel) {
                    background = when {
                        changed(row) -> Theme.changed
                        idx < 0 -> Theme.bg
                        else -> Theme.surface
                    }
                    foreground = when {
                        col == 0 -> if (changed(row)) Theme.changedStrong else Theme.synRegister
                        col == 1 && value(idx) == 0L -> Theme.dim
                        else -> Theme.text
                    }
                }
                return this
            }
        })
    }

    private val lamps = Docs.flags.map { (name, tip) -> FlagLamp(name).apply { toolTipText = "<html><b>$name</b>: $tip</html>" } }

    init {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(Theme.sectionLabel("Registers"), BorderLayout.WEST)
            add(JComboBox(Format.entries.map { it.label }.toTypedArray()).apply {
                toolTipText = "How to show register values"
                addActionListener { format = Format.entries[selectedIndex]; tableModel.fireTableStructureChanged(); configureColumns() }
            }, BorderLayout.EAST)
        }
        val flagsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 6)).apply {
            add(Theme.sectionLabel("Flags").apply { border = javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 4) })
            lamps.forEach { add(it) }
        }
        add(header, BorderLayout.NORTH)
        add(JScrollPane(table).apply {
            preferredSize = Dimension(520, 18 * 21 + 30)
            Theme.onChange { border = javax.swing.BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.grid); viewport.background = Theme.surface }
        }, BorderLayout.CENTER)
        add(flagsRow, BorderLayout.SOUTH)
    }

    private fun configureColumns() {
        table.columnModel.getColumn(0).preferredWidth = 55
        table.columnModel.getColumn(1).preferredWidth = 150
        table.columnModel.getColumn(2).preferredWidth = 150
    }

    /** Refreshes the view; values that differ from [previous] are highlighted. */
    fun refresh(previous: CpuSnapshot?) {
        before = previous
        tableModel.fireTableRowsUpdated(0, rows.size - 1)
        val now = CpuSnapshot.of(machine).flags
        lamps.forEach { it.on = now.getValue(it.flag); it.changed = previous != null && previous.flags[it.flag] != it.on; it.repaint() }
    }
}

/** A small rounded "lamp" showing one status flag: filled when set, outlined when it just changed. */
class FlagLamp(val flag: String) : JComponent() {
    var on = false
    var changed = false

    init {
        font = Theme.monoBold.deriveFont(10.5f)
        preferredSize = Dimension(44, 24)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = if (on) Theme.flagOn else Theme.flagOff
        g.fillRoundRect(1, 1, width - 2, height - 2, height - 2, height - 2)
        if (changed) {
            g.color = Theme.changedStrong
            g.stroke = java.awt.BasicStroke(2.5f)
            g.drawRoundRect(2, 2, width - 4, height - 4, height - 4, height - 4)
        }
        g.color = if (on) Color.WHITE else Theme.flagOffText
        val fm = g.fontMetrics
        val label = "$flag=${if (on) 1 else 0}"
        g.drawString(label, (width - fm.stringWidth(label)) / 2, (height + fm.ascent - fm.descent) / 2)
    }
}

package x86sim.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import x86sim.Machine
import x86sim.asm.ExprException
import x86sim.asm.ExprParser

/** A classic hex dump: 16 bytes per row plus their ASCII rendering. */
class MemoryPanel(private val machine: Machine) : JPanel(BorderLayout()) {
    private var base = 0x600000L
    private val rowsShown = 64
    private var written: Set<Long> = emptySet()

    private val gotoField = JTextField("msg", 14).apply {
        font = Theme.mono
        toolTipText = "Address, label or register: msg, fib+16, rbp-8"
        putClientProperty("JTextField.placeholderText", "address or label")
    }
    private val sectionBox = JComboBox(arrayOf(".data", ".rodata", ".bss", "stack (rsp)"))
    private val followWrites = JCheckBox("Follow writes").apply {
        toolTipText = "Jump to the latest write"
    }
    private val status = JLabel(" ").apply { foreground = Theme.dim }

    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rowsShown
        override fun getColumnCount() = 18
        override fun getColumnName(c: Int) = when (c) { 0 -> "Address"; 17 -> "ASCII"; else -> "%02x".format(c - 1) }
        override fun getValueAt(r: Int, c: Int): Any {
            val rowAddr = base + r * 16L
            return when (c) {
                0 -> Theme.hexAddr(rowAddr)
                17 -> (0 until 16).joinToString("") { i ->
                    val b = machine.memory.peek(rowAddr + i)
                    when { b == null -> " "; b in 32..126 -> b.toChar().toString(); else -> "·" }
                }
                else -> machine.memory.peek(rowAddr + c - 1)?.let { "%02x".format(it) } ?: "··"
            }
        }
    }

    private val table = JTable(model).apply {
        font = Theme.mono
        rowHeight = font.size + 9
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        tableHeader.reorderingAllowed = false
        tableHeader.font = Theme.monoSmall
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        cellSelectionEnabled = true
        columnModel.getColumn(0).preferredWidth = 120
        for (c in 1..16) columnModel.getColumn(c).preferredWidth = 28
        columnModel.getColumn(17).preferredWidth = 160
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                super.getTableCellRendererComponent(t, v, sel, false, row, col)
                val addr = base + row * 16L + (col - 1)
                horizontalAlignment = if (col in 1..16) CENTER else LEFT
                border = javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 4)
                toolTipText = if (col in 1..16) tooltipFor(addr) else null
                if (!sel) {
                    background = when {
                        col in 1..16 && addr in written -> Theme.changed
                        col == 0 -> Theme.bg
                        col in 1..16 && ((col - 1) / 4) % 2 == 1 -> Theme.surfaceAlt
                        else -> Theme.surface
                    }
                    foreground = when {
                        col in 1..16 && machine.memory.peek(addr) == null -> Theme.grid
                        col in 1..16 && machine.memory.peek(addr) == 0 -> Theme.dim
                        col == 17 -> Theme.synString
                        col == 0 -> Theme.dim
                        else -> Theme.text
                    }
                }
                return this
            }
        })
    }

    private fun tooltipFor(addr: Long): String {
        val region = machine.memory.regionAt(addr) ?: return "0x%x: unmapped".format(addr)
        val sym = machine.program?.describeDataAddress(addr)?.let { " ($it)" } ?: ""
        return "0x%x%s in %s".format(addr, sym, region.name)
    }

    init {
        val go = JButton("Go").apply { addActionListener { goTo(gotoField.text) } }
        gotoField.addActionListener { goTo(gotoField.text) }
        sectionBox.addActionListener {
            when (sectionBox.selectedIndex) {
                0 -> show(0x600000); 1 -> show(0x500000); 2 -> show(0x700000)
                else -> show(machine.cpu.regs[x86sim.cpu.Registers.RSP] and 0xFL.inv())
            }
        }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(gotoField); add(go)
            add(sectionBox)
            add(followWrites)
            add(status)
        }
        add(bar, BorderLayout.NORTH)
        add(JScrollPane(table).apply { Theme.onChange { viewport.background = Theme.surface } }, BorderLayout.CENTER)
    }

    fun goTo(expr: String) {
        val m = machine
        try {
            val lin = ExprParser(expr.trim(), { m.program?.symbols?.get(it) }, allowRegisters = true).parse()
            val addr = lin.constant + lin.regs.sumOf { (r, k) -> m.cpu.get(r) * k }
            show(addr)
            status.text = "0x%x".format(addr) + (m.program?.describeDataAddress(addr)?.let { " = $it" } ?: "")
            status.foreground = Theme.dim

        } catch (e: ExprException) {
            status.text = e.message
            status.foreground = Theme.bad
        }
    }

    private fun show(addr: Long) {
        base = addr and 0xFL.inv()
        model.fireTableDataChanged()
        table.scrollRectToVisible(table.getCellRect(0, 0, true))
    }

    fun onProgramLoaded() {
        val p = machine.program ?: return
        // Start on the first data label, if any.
        val first = p.symbols.entries.filter { it.value >= 0x500000 }.minByOrNull { it.value }
        if (first != null) { gotoField.text = first.key; goTo(first.key) } else show(0x600000)
    }

    fun refresh() {
        val log = machine.memory.writeLog
        written = log.flatMap { (a, s) -> (0 until s).map { a + it } }.toSet()
        if (followWrites.isSelected && log.isNotEmpty()) {
            val a = log.last().first
            if (a < base || a >= base + rowsShown * 16) show(a - 32)
        }
        model.fireTableDataChanged()
    }
}

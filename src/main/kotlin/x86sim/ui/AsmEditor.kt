package x86sim.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import javax.swing.undo.UndoManager
import x86sim.asm.Assembler
import x86sim.cpu.Registers

/**
 * Assembly source editor: syntax highlighting, current-line and error-line highlights,
 * hover documentation and an undo history.
 */
class AsmEditor : JTextPane() {
    val undo = UndoManager()

    /** 0-based line of the next instruction to execute, or -1. */
    var currentLine = -1
        set(v) { field = v; repaint() }
    var errorLines: Set<Int> = emptySet()
        set(v) { field = v; repaint() }

    var onEdited: () -> Unit = {}
    private var highlightPending = false

    init {
        isOpaque = false
        margin = java.awt.Insets(4, 10, 4, 10)
        Theme.onChange {
            font = Theme.mono
            background = Theme.editorBg
            caretColor = Theme.text
            selectionColor = if (Theme.isDark) Color(0x214283) else Color(0xA6D2FF)
            selectedTextColor = null
            SwingUtilities.invokeLater { highlight() } // after all fields are initialised
        }
        ToolTipManager.sharedInstance().registerComponent(this)
        (document as StyledDocument).apply {
            addUndoableEditListener { e ->
                // Only record text edits; attribute changes from highlighting aren't undoable steps.
                if (e.edit.presentationName != "style change") undo.addEdit(e.edit)
            }
            addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) {}
            })
        }
        // A tab becomes four spaces, which keeps columns aligned in every font.
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("TAB"), "insert-4-spaces")
        actionMap.put("insert-4-spaces", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = replaceSelection("    ")
        })
    }

    private fun changed() {
        onEdited()
        if (!highlightPending) {
            highlightPending = true
            SwingUtilities.invokeLater { highlightPending = false; highlight() }
        }
    }

    fun setSource(text: String) {
        this.text = text
        caretPosition = 0
        undo.discardAllEdits()
        highlight()
    }

    val lineCount: Int get() = document.defaultRootElement.elementCount

    fun lineOfOffset(offset: Int) = document.defaultRootElement.getElementIndex(offset)

    fun lineTop(line: Int): Rectangle? {
        val el = document.defaultRootElement.getElement(line) ?: return null
        return try { modelToView2D(el.startOffset)?.bounds } catch (_: Exception) { null }
    }

    fun lineAtY(y: Int): Int = lineOfOffset(viewToModel2D(Point(0, y)))

    fun scrollToLine(line: Int) {
        val r = lineTop(line) ?: return
        scrollRectToVisible(Rectangle(0, r.y - r.height * 2, 1, r.height * 5))
    }

    fun goToLine(line: Int) {
        val el = document.defaultRootElement.getElement(line) ?: return
        caretPosition = el.startOffset
        scrollToLine(line)
        requestFocusInWindow()
    }

    // Keep lines from wrapping: never shrink below the longest line (the preferred width);
    // grow to fill the viewport when that is wider, and let the scroll pane scroll horizontally.
    override fun getScrollableTracksViewportWidth(): Boolean = false

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val fill = (parent as? javax.swing.JViewport)?.width ?: 0
        super.setBounds(x, y, maxOf(width, fill, ui.getPreferredSize(this).width), height)
    }

    override fun paintComponent(g: Graphics) {
        g.color = background
        g.fillRect(0, 0, width, height)
        for (l in errorLines) lineTop(l)?.let { g.color = Theme.errorLine; g.fillRect(0, it.y, width, it.height) }
        if (currentLine >= 0) lineTop(currentLine)?.let { g.color = Theme.currentLine; g.fillRect(0, it.y, width, it.height) }
        super.paintComponent(g)
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val pos = viewToModel2D(event.point)
        val word = wordAt(pos)?.lowercase() ?: return null
        Docs.lookup(word)?.let { e ->
            return "<html><b><code>${e.syntax}</code></b><br>${e.description}" +
                (if (e.flags != "none") "<br><font color='${Theme.hex(Theme.dim)}'>Flags: ${e.flags}</font>" else "") + "</html>"
        }
        Registers.lookup(word)?.let { r ->
            val full = Registers.names64[r.num]
            val part = if (r.size == 8) "" else " (the low ${r.size * 8} bits of $full)".let {
                if (r.high) " (bits 8–15 of $full)" else it
            }
            return "<html><b>$word</b>$part<br>${Docs.registers[full] ?: ""}</html>"
        }
        return null
    }

    private fun wordAt(pos: Int): String? {
        val t = text.replace("\r\n", "\n")
        if (pos < 0 || pos >= t.length) return null
        fun isW(c: Char) = c.isLetterOrDigit() || c == '_'
        if (!isW(t[pos])) return null
        var s = pos; var e = pos
        while (s > 0 && isW(t[s - 1])) s--
        while (e < t.length && isW(t[e])) e++
        return t.substring(s, e)
    }

    // ---------------- syntax highlighting ----------------

    private fun style(color: Color, bold: Boolean = false, italic: Boolean = false): AttributeSet =
        SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, color)
            StyleConstants.setBold(this, bold)
            StyleConstants.setItalic(this, italic)
        }

    // Recomputed from the palette each time, so a theme switch recolours the code.
    private val plain get() = style(Theme.text)
    private val sComment get() = style(Theme.synComment, italic = true)
    private val sMnemonic get() = style(Theme.synMnemonic, bold = true)
    private val sRegister get() = style(Theme.synRegister)
    private val sNumber get() = style(Theme.synNumber)
    private val sString get() = style(Theme.synString)
    private val sLabel get() = style(Theme.synLabel, bold = true)
    private val sDirective get() = style(Theme.synDirective, bold = true)

    private val tokenRe = Regex("""(;.*)|("[^"]*"?|'[^']*'?|`[^`]*`?)|([A-Za-z_.@?$][\w.@?$#]*)(\s*:)?|(\b\d[\w]*\b)""")
    private val directiveWords = Assembler.DIRECTIVES + setOf("byte", "word", "dword", "qword", "ptr", "rel")

    fun highlight() {
        val plain = plain; val sComment = sComment; val sMnemonic = sMnemonic; val sRegister = sRegister
        val sNumber = sNumber; val sString = sString; val sLabel = sLabel; val sDirective = sDirective
        val doc = styledDocument
        val t = doc.getText(0, doc.length)
        doc.setCharacterAttributes(0, t.length, plain, true)
        var lineStart = 0
        for (line in t.split("\n")) {
            var firstWord = true
            for (m in tokenRe.findAll(line)) {
                val start = lineStart + m.range.first
                val len = m.value.length
                val g = m.groups
                when {
                    g[1] != null -> doc.setCharacterAttributes(start, len, sComment, true)
                    g[2] != null -> doc.setCharacterAttributes(start, len, sString, true)
                    g[5] != null -> doc.setCharacterAttributes(start, len, sNumber, true)
                    g[3] != null -> {
                        val w = g[3]!!.value.lowercase()
                        val st = when {
                            g[4] != null -> sLabel
                            Registers.isRegister(w) -> sRegister
                            w in directiveWords -> sDirective
                            firstWord && Docs.lookup(w) != null -> sMnemonic
                            firstWord && w !in Assembler.MNEMONICS -> sLabel // e.g. "msg db ..."
                            else -> null
                        }
                        if (st != null) doc.setCharacterAttributes(start, g[3]!!.value.length, st, true)
                    }
                }
                if (g[1] == null) firstWord = g[4] != null && firstWord
            }
            lineStart += line.length + 1
        }
    }
}

/** Line numbers, breakpoint dots and the "next instruction" arrow, shown left of the editor. */
class Gutter(private val editor: AsmEditor, private val breakpoints: MutableSet<Int>) : JComponent() {
    /** Lines holding an instruction (only those can stop execution); null = unknown / not assembled. */
    var instructionLines: Set<Int>? = null
        set(v) { field = v; repaint() }
    var onToggle: (Int) -> Unit = {}

    init {
        Theme.onChange { font = Theme.monoSmall; relayout() }
        toolTipText = "Click to toggle a breakpoint"
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val line = editor.lineAtY(e.y)
                if (line in 0 until editor.lineCount) onToggle(line)
            }
        })
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = relayout()
            override fun removeUpdate(e: DocumentEvent) = relayout()
            override fun changedUpdate(e: DocumentEvent) {}
        })
    }

    private fun relayout() = SwingUtilities.invokeLater { revalidate(); repaint() }

    override fun getPreferredSize(): Dimension {
        val digits = maxOf(3, editor.lineCount.toString().length)
        return Dimension(getFontMetrics(font).charWidth('0') * digits + Theme.z(34), editor.preferredSize.height)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Theme.gutterBg
        g.fillRect(0, 0, width, height)
        val clip = g.clipBounds
        val fm = g.fontMetrics
        val first = editor.lineAtY(clip.y)
        val last = editor.lineAtY(clip.y + clip.height)
        for (line in first..last) {
            val r = editor.lineTop(line) ?: continue
            val cy = r.y + r.height / 2
            if (line == editor.currentLine) {
                g.color = Theme.currentLine
                g.fillRect(0, r.y, width - 1, r.height)
            }
            if (line in breakpoints) {
                val active = instructionLines?.contains(line) ?: true
                g.color = if (active) Theme.breakpoint else Theme.breakpointInactive
                val d = Theme.z(10)
                g.fillOval(Theme.z(5), cy - d / 2, d, d)
            }
            if (line == editor.currentLine) {
                g.color = Theme.currentLineArrow
                g.stroke = BasicStroke(2f)
                val a = Theme.z(18); val b = Theme.z(25); val h = Theme.z(5)
                val xs = intArrayOf(a, b, a); val ys = intArrayOf(cy - h, cy, cy + h)
                g.fillPolygon(xs, ys, 3)
            }
            val s = (line + 1).toString()
            g.color = if (line == editor.currentLine) Theme.gutterFgActive else Theme.gutterFg
            g.drawString(s, width - 6 - fm.stringWidth(s), r.y + fm.ascent + (r.height - fm.height) / 2)
        }
    }
}

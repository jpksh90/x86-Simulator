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
import javax.swing.KeyStroke
import javax.swing.text.AbstractDocument
import javax.swing.text.DefaultEditorKit
import javax.swing.undo.CompoundEdit
import javax.swing.undo.UndoManager
import x86sim.asm.Assembler
import x86sim.asm.SourceLayout
import x86sim.asm.SourceLayout.LineKind
import x86sim.cpu.Registers

/**
 * Assembly source editor: syntax highlighting, current-line and error-line highlights,
 * hover documentation, an undo history and assembly-style indentation ([SourceLayout]).
 */
class AsmEditor : JTextPane() {
    companion object {
        const val ENTER = "asm-enter"
        const val INDENT = "asm-indent"
        const val UNINDENT = "asm-unindent"
        const val BACKSPACE = "asm-backspace"
        const val COLON = "asm-colon"
        const val SEMICOLON = "asm-semicolon"
    }

    val undo = UndoManager()

    /** 0-based line of the next instruction to execute, or -1. */
    var currentLine = -1
        set(v) { field = v; repaint() }
    var errorLines: Set<Int> = emptySet()
        set(v) { field = v; repaint() }
    /** 0-based line blamed for the current crash, or -1. */
    var faultLine = -1
        set(v) { field = v; repaint() }

    var onEdited: () -> Unit = {}
    private var highlightPending = false
    /** While set, text edits collect here so one key press is one Undo step. */
    private var compound: CompoundEdit? = null

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
                if (e.edit.presentationName != "style change") (compound ?: undo).addEdit(e.edit)
            }
            addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) {}
            })
        }
        // Keys that lay code out in assembly columns. Indents are spaces, so columns line up in
        // every font.
        val deletePrevious = actionMap[DefaultEditorKit.deletePrevCharAction]
        bind(KeyStroke.getKeyStroke("ENTER"), ENTER) { enter() }
        bind(KeyStroke.getKeyStroke("TAB"), INDENT) { indent() }
        bind(KeyStroke.getKeyStroke("shift TAB"), UNINDENT) { unindent() }
        bind(KeyStroke.getKeyStroke("BACK_SPACE"), BACKSPACE) { if (!backspace()) deletePrevious.actionPerformed(it) }
        bind(KeyStroke.getKeyStroke(':'), COLON) { colon() }
        bind(KeyStroke.getKeyStroke(';'), SEMICOLON) { semicolon() }
    }

    private fun bind(key: KeyStroke, name: String, body: (java.awt.event.ActionEvent) -> Unit) {
        inputMap.put(key, name)
        actionMap.put(name, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (isEditable && isEnabled) body(e ?: java.awt.event.ActionEvent(this@AsmEditor, java.awt.event.ActionEvent.ACTION_PERFORMED, name))
            }
        })
    }

    /** Runs [block] so that all the text edits it makes are undone together. */
    fun compoundEdit(block: () -> Unit) {
        if (compound != null) return block()
        val edit = CompoundEdit()
        compound = edit
        try {
            block()
        } finally {
            compound = null
            edit.end()
            if (edit.isSignificant) undo.addEdit(edit)
        }
    }

    // ---------------- assembly layout ----------------

    private fun lineStart(line: Int) = document.defaultRootElement.getElement(line).startOffset
    private fun lineEnd(line: Int) = document.defaultRootElement.getElement(line).endOffset - 1
    private fun lineText(line: Int) = document.getText(lineStart(line), lineEnd(line) - lineStart(line))
    private fun replaceRange(start: Int, end: Int, s: String) =
        (document as AbstractDocument).replace(start, end - start, s, null)

    /** Text of the caret's line up to the caret. */
    private fun beforeCaret(): String {
        val pos = caretPosition
        val start = lineStart(lineOfOffset(pos))
        return document.getText(start, pos - start)
    }

    private fun isLevelZero(s: String) = SourceLayout.kind(s).let { it == LineKind.Label || it == LineKind.Directive }

    /** New line, indented for what comes next; a label or directive being left goes to column 0. */
    private fun enter() = compoundEdit {
        replaceSelection("")
        val pos = caretPosition
        val line = lineOfOffset(pos)
        val start = lineStart(line)
        val before = document.getText(start, pos - start)
        val rest = document.getText(pos, lineEnd(line) - pos).trimStart(' ', '\t')
        val head = when {
            before.isBlank() -> ""
            isLevelZero(before) -> SourceLayout.placed(before, 0)
            else -> before
        }
        val tail = (if (isLevelZero(rest)) "" else " ".repeat(SourceLayout.indentAfter(before))) + rest
        replaceRange(start, lineEnd(line), head + "\n" + tail)
        caretPosition = start + head.length + 1 + tail.length - rest.length
    }

    /** `:` that completes a label moves the line to column 0. */
    private fun colon() = compoundEdit {
        replaceSelection(":")
        val before = beforeCaret()
        val label = Assembler.LABEL_RE.find(before)
        if (label != null && label.range.last == before.lastIndex) {
            val indent = SourceLayout.split(before).indent.length
            if (indent > 0) document.remove(caretPosition - before.length, indent)
        }
    }

    /** `;` after code lands on the comment column of the surrounding lines. */
    private fun semicolon() = compoundEdit {
        replaceSelection("")
        val before = beforeCaret()
        val code = SourceLayout.split(before).code
        if (code.isEmpty() || Assembler.commentStart("$before;") != before.length) {
            replaceSelection(";")
            return@compoundEdit
        }
        val line = lineOfOffset(caretPosition)
        val start = lineStart(line)
        val head = before.trimEnd()
        val lines = (0 until lineCount).map { if (it == line) head else lineText(it) }
        val pad = maxOf(1, SourceLayout.commentColumnFor(lines, line) - SourceLayout.visualWidth(head))
        replaceRange(start + head.length, caretPosition, " ".repeat(pad) + ";")
        caretPosition = start + head.length + pad + 1
    }

    /** Lines touched by the selection, or null when Tab should just insert spaces. */
    private fun selectedLines(): IntRange? {
        val a = selectionStart
        val b = selectionEnd
        if (a == b) return null
        val first = lineOfOffset(a)
        var last = lineOfOffset(b)
        if (last > first && b == lineStart(last)) last--
        val wholeLine = a == lineStart(first) && b >= lineEnd(first)
        return if (first == last && !wholeLine) null else first..last
    }

    private fun indent() {
        val lines = selectedLines()
        if (lines == null) {
            val col = SourceLayout.visualWidth(document.getText(lineStart(lineOfOffset(selectionStart)),
                selectionStart - lineStart(lineOfOffset(selectionStart))))
            compoundEdit { replaceSelection(" ".repeat(SourceLayout.INDENT - col % SourceLayout.INDENT)) }
            return
        }
        compoundEdit {
            for (l in lines.reversed()) if (lineText(l).isNotEmpty())
                document.insertString(lineStart(l), " ".repeat(SourceLayout.INDENT), null)
        }
        select(lineStart(lines.first), lineEnd(lines.last))
    }

    private fun unindent() {
        val lines = selectedLines()
        compoundEdit {
            for (l in (lines ?: lineOfOffset(caretPosition).let { it..it }).reversed()) {
                val t = lineText(l)
                val n = if (t.startsWith("\t")) 1 else t.takeWhile { it == ' ' }.length.coerceAtMost(SourceLayout.INDENT)
                if (n > 0) document.remove(lineStart(l), n)
            }
        }
        if (lines != null) select(lineStart(lines.first), lineEnd(lines.last))
    }

    /** Backspace in leading spaces goes back one level; false means "do a normal backspace". */
    private fun backspace(): Boolean {
        if (selectionStart != selectionEnd) return false
        val before = beforeCaret()
        if (before.isEmpty() || before.any { it != ' ' }) return false
        val n = (before.length - 1) % SourceLayout.INDENT + 1
        compoundEdit { document.remove(caretPosition - n, n) }
        return true
    }

    /** Edit → Format Program: lays out every line; only whitespace changes, as one Undo step. */
    fun formatProgram() {
        if (!isEditable) return
        val old = document.getText(0, document.length).split("\n")
        val new = SourceLayout.format(old.joinToString("\n")).split("\n")
        if (old == new) return
        val line = lineOfOffset(caretPosition)
        val col = caretPosition - lineStart(line)
        compoundEdit {
            for (l in old.indices.reversed()) if (old[l] != new[l]) replaceRange(lineStart(l), lineEnd(l), new[l])
        }
        val shift = SourceLayout.split(new[line]).indent.length - SourceLayout.split(old[line]).indent.length
        caretPosition = lineStart(line) + (col + shift).coerceIn(0, new[line].length)
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
        if (faultLine >= 0) lineTop(faultLine)?.let { g.color = Theme.errorLine; g.fillRect(0, it.y, width, it.height) }
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
                if (g[1] == null) firstWord = (g[4] != null || g[3]?.value?.lowercase() in Assembler.PREFIXES) && firstWord
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
            g.color = when (line) {
                editor.currentLine -> Theme.gutterFgActive
                editor.faultLine -> Theme.bad
                else -> Theme.gutterFg
            }
            g.drawString(s, width - 6 - fm.stringWidth(s), r.y + fm.ascent + (r.height - fm.height) / 2)
        }
    }
}

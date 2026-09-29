package x86sim

import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.test.Test
import kotlin.test.assertEquals
import x86sim.asm.SourceLayout
import x86sim.ui.AsmEditor

/** Editor keys that follow assembly layout (specs/006-asm-editor-indentation/contracts/editor-keys.md). */
class EditorIndentTest {
    init {
        System.setProperty("java.awt.headless", "true")
        System.setProperty("x86sim.noprefs", "true")
    }

    private lateinit var editor: AsmEditor

    /** Runs [block] on the event thread with a fresh editor, the way key actions run in the app. */
    private fun edt(block: () -> Unit) {
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try { editor = AsmEditor(); block() } catch (t: Throwable) { error = t }
        }
        error?.let { throw it }
    }

    private val doc get() = editor.document.getText(0, editor.document.length)

    /** Sets the text; `|` marks the caret and a second `|` (if any) the end of a selection. */
    private fun given(s: String) {
        val a = s.indexOf('|')
        val b = s.indexOf('|', a + 1)
        editor.setSource(s.replace("|", ""))
        if (b < 0) editor.caretPosition = a else editor.select(a, b - 1)
    }

    private fun state(): String {
        val t = doc
        val s = editor.selectionStart
        val e = editor.selectionEnd
        return if (s == e) t.substring(0, s) + "|" + t.substring(s)
        else t.substring(0, s) + "|" + t.substring(s, e) + "|" + t.substring(e)
    }

    private fun press(name: String) = editor.actionMap[name].actionPerformed(null)

    /** Applies [key] to [before] and checks [after]; one Undo must bring the text back. */
    private fun check(before: String, key: String, after: String) = edt {
        given(before)
        press(key)
        assertEquals(after, state(), "after $key on \"$before\"")
        editor.undo.undo()
        assertEquals(before.replace("|", ""), doc, "undo of $key on \"$before\"")
    }

    // ---------------- foundation ----------------

    @Test fun `compound edits undo in one step`() = edt {
        given("ab|")
        editor.compoundEdit {
            editor.replaceSelection("c")
            editor.replaceSelection("d")
        }
        assertEquals("abcd", doc)
        editor.undo.undo()
        assertEquals("ab", doc)
    }

    @Test fun `keys are bound to layout actions`() = edt {
        val map = editor.inputMap
        assertEquals(AsmEditor.ENTER, map[KeyStroke.getKeyStroke("ENTER")])
        assertEquals(AsmEditor.INDENT, map[KeyStroke.getKeyStroke("TAB")])
        assertEquals(AsmEditor.UNINDENT, map[KeyStroke.getKeyStroke("shift TAB")])
        assertEquals(AsmEditor.BACKSPACE, map[KeyStroke.getKeyStroke("BACK_SPACE")])
        assertEquals(AsmEditor.COLON, map[KeyStroke.getKeyStroke(':')])
        assertEquals(AsmEditor.SEMICOLON, map[KeyStroke.getKeyStroke(';')])
    }

    // ---------------- US1: Enter and ':' ----------------

    @Test fun `enter after a label indents`() = check("_start:|", AsmEditor.ENTER, "_start:\n    |")

    @Test fun `enter after code keeps the indent`() = check("    mov rcx, 100|", AsmEditor.ENTER, "    mov rcx, 100\n    |")

    @Test fun `enter moves an indented directive to column zero`() =
        check("    section .bss|", AsmEditor.ENTER, "section .bss\n    |")

    @Test fun `enter empties a whitespace-only line`() = check("x\n    |", AsmEditor.ENTER, "x\n\n    |")

    @Test fun `enter mid-line places the moved text`() {
        check("    dec rcx|.l: nop", AsmEditor.ENTER, "    dec rcx\n|.l: nop")
        check("_start:|mov rax, 1", AsmEditor.ENTER, "_start:\n    |mov rax, 1")
    }

    @Test fun `colon after a label name moves it to column zero`() {
        check("    .again|", AsmEditor.COLON, ".again:|")
        check("    mov|", AsmEditor.COLON, "mov:|")
    }

    @Test fun `colon inside an operand is plain`() {
        check("    mov al, 'a|", AsmEditor.COLON, "    mov al, 'a:|")
        check("    db 1|", AsmEditor.COLON, "    db 1:|")
    }

    @Test fun `retyping an example reproduces its columns`() = edt {
        val src = Examples.load("02_loop_sum").split("\n")
        val kept = src.filter { SourceLayout.kind(it) != SourceLayout.LineKind.Comment }
        editor.setSource("")
        for ((i, line) in kept.withIndex()) {
            val p = SourceLayout.split(line)
            for (ch in p.code) if (ch == ':') press(AsmEditor.COLON) else editor.replaceSelection(ch.toString())
            if (i < kept.lastIndex) press(AsmEditor.ENTER)
        }
        val typed = doc.split("\n")
        for ((i, line) in kept.withIndex()) {
            if (SourceLayout.kind(line) == SourceLayout.LineKind.Blank) continue
            assertEquals(SourceLayout.split(line).indent, SourceLayout.split(typed[i]).indent, "line \"$line\"")
            assertEquals(SourceLayout.split(line).code, typed[i].trim())
        }
    }

    // ---------------- US2: Tab, Shift+Tab, Backspace ----------------

    @Test fun `tab goes to the next stop`() {
        check("ab|", AsmEditor.INDENT, "ab  |")
        check("    |x", AsmEditor.INDENT, "        |x")
    }

    @Test fun `tab indents selected lines`() =
        check("|a\n\n  b|\nc", AsmEditor.INDENT, "|    a\n\n      b|\nc")

    @Test fun `shift-tab unindents selected lines`() =
        check("|a\n  b\n        c|", AsmEditor.UNINDENT, "|a\nb\n    c|")

    @Test fun `shift-tab without a selection`() {
        check("\tmov|", AsmEditor.UNINDENT, "mov|")
        check("      mo|v", AsmEditor.UNINDENT, "  mo|v")
    }

    @Test fun `backspace removes a level of leading spaces`() {
        check("        |mov", AsmEditor.BACKSPACE, "    |mov")
        check("      |mov", AsmEditor.BACKSPACE, "    |mov")
        check("    x|", AsmEditor.BACKSPACE, "    |")
        check("ab|cd|", AsmEditor.BACKSPACE, "ab|")
    }

    // ---------------- US3: ';' ----------------

    @Test fun `semicolon joins the run's comment column`() = edt {
        given("_start:\n    xor eax, eax        ; a\n    mov rcx, 100        ; b\n    inc rax|")
        press(AsmEditor.SEMICOLON)
        assertEquals(24, doc.substringAfterLast("\n").indexOf(';'))
        assertEquals(1, editor.caretPosition - doc.lastIndexOf(';'))
        editor.undo.undo()
        assertEquals("_start:\n    xor eax, eax        ; a\n    mov rcx, 100        ; b\n    inc rax", doc)
    }

    @Test fun `semicolon defaults to column 28`() =
        check("    inc rax |", AsmEditor.SEMICOLON, "    inc rax" + " ".repeat(28 - 11) + ";|")

    @Test fun `semicolon after long code keeps one space`() {
        val code = "    " + "x".repeat(36)
        check("$code|", AsmEditor.SEMICOLON, "$code ;|")
    }

    @Test fun `semicolon with no code or inside a literal is plain`() {
        check("|", AsmEditor.SEMICOLON, ";|")
        check("    |", AsmEditor.SEMICOLON, "    ;|")
        check("    msg db \"a|", AsmEditor.SEMICOLON, "    msg db \"a;|")
        check("    nop ; x|", AsmEditor.SEMICOLON, "    nop ; x;|")
    }

    // ---------------- US4: Format Program ----------------

    @Test fun `formatting a formatted program makes no edit`() = edt {
        editor.setSource(Examples.load("03_factorial"))
        var events = 0
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { events++ }
            override fun removeUpdate(e: DocumentEvent) { events++ }
            override fun changedUpdate(e: DocumentEvent) {}
        })
        editor.formatProgram()
        assertEquals(0, events)
    }

    @Test fun `format program is one undo step`() = edt {
        val src = Examples.load("02_loop_sum")
        val stripped = src.split("\n").joinToString("\n") { it.trimStart() }
        editor.setSource(stripped)
        val lines = editor.lineCount
        editor.caretPosition = stripped.indexOf("mov rcx")
        editor.formatProgram()
        assertEquals(src, doc)
        assertEquals(lines, editor.lineCount)
        assertEquals(doc.indexOf("mov rcx"), editor.caretPosition, "caret stays on its line")
        editor.undo.undo()
        assertEquals(stripped, doc)
    }
}

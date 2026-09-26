package x86sim.ui

import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** The program's terminal: stdout/stderr above, a line of keyboard input below. */
class ConsolePanel : JPanel(BorderLayout()) {
    private val output = JTextPane().apply {
        isEditable = false
        margin = java.awt.Insets(8, 12, 8, 12)
    }
    private val input = JTextField().apply {
        toolTipText = "Type a line, press Enter"
        putClientProperty("JTextField.placeholderText", "Type input for the program…")
    }
    private val sendButton = JButton("Send")
    private val eofButton = JButton("EOF").apply { toolTipText = "End of input (Ctrl-D)" }
    private val hint = JLabel("stdin").apply { border = BorderFactory.createEmptyBorder(0, 6, 0, 4) }

    var onInput: (String) -> Unit = {}
    var onEof: () -> Unit = {}

    private fun attrs(color: Color, italic: Boolean = false) = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, color); StyleConstants.setItalic(this, italic)
    }
    private val outAttrs get() = attrs(Theme.consoleText)
    private val inAttrs get() = attrs(Theme.consoleInput)
    private val sysAttrs get() = attrs(Theme.consoleSys, italic = true)
    private val errAttrs get() = attrs(Theme.consoleErr)

    init {
        val send = {
            val line = input.text
            input.text = ""
            onInput(line + "\n")
        }
        input.addActionListener { send() }
        sendButton.addActionListener { send() }
        eofButton.addActionListener { onEof() }
        val row = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(hint, BorderLayout.WEST)
            add(input, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply { add(sendButton); add(eofButton) }, BorderLayout.EAST)
        }
        Theme.onChange {
            output.background = Theme.consoleBg
            output.foreground = Theme.consoleText
            output.caretColor = Theme.consoleText
            output.font = Theme.mono
            input.font = Theme.mono
        }
        add(JScrollPane(output).apply { border = null }, BorderLayout.CENTER)
        add(row, BorderLayout.SOUTH)
    }

    private fun append(text: String, a: SimpleAttributeSet) {
        val doc = output.styledDocument
        doc.insertString(doc.length, text, a)
        output.caretPosition = doc.length
    }

    fun print(text: String) = append(text, outAttrs)
    fun echoInput(text: String) = append(text, inAttrs)
    fun system(text: String) = append(ensureNewline() + "● $text\n", sysAttrs)
    fun error(text: String) = append(ensureNewline() + "× $text\n", errAttrs)

    private fun ensureNewline(): String {
        val d = output.document
        return if (d.length > 0 && d.getText(d.length - 1, 1) != "\n") "\n" else ""
    }

    fun clear() { output.text = "" }

    fun setWaiting(waiting: Boolean) {
        hint.text = if (waiting) "Input needed" else "stdin"
        hint.foreground = if (waiting) Theme.warn else Theme.dim
        input.putClientProperty("JComponent.outline", if (waiting) "warning" else null)
        if (waiting) input.requestFocusInWindow()
    }
}

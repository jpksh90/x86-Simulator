package x86sim

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import x86sim.ui.Document
import x86sim.ui.labelText
import x86sim.ui.titleName
import x86sim.ui.tooltipText

/** The toolbar label that names the open program (specs/004-file-name-label/contracts/toolbar-label.md). */
class DocumentLabelTest {
    private val example = Document.Example("Hello, world")
    private val loop = File("/home/u/loop.asm")
    private val opened = Document.Opened(loop)

    @Test fun `new document label`() {
        assertEquals("*New File", labelText(Document.New, false))
        assertEquals("New File (not saved yet)", tooltipText(Document.New))
    }

    @Test fun `example label`() {
        assertEquals("Hello, world", labelText(example, false))
        assertEquals("Example: Hello, world", tooltipText(example))
    }

    @Test fun `opened file label shows name only`() {
        assertEquals("loop.asm", labelText(opened, false))
        assertEquals(loop.absolutePath, tooltipText(opened))
    }

    @Test fun `unsaved example gets a star`() = assertEquals("*Hello, world", labelText(example, true))

    @Test fun `unsaved file gets a star`() = assertEquals("*loop.asm", labelText(opened, true))

    @Test fun `new file never gets a double star`() = assertEquals("*New File", labelText(Document.New, true))

    @Test fun `at most one leading star`() {
        for (doc in listOf(Document.New, example, opened)) for (unsaved in listOf(false, true)) {
            val text = labelText(doc, unsaved)
            assertTrue(text.takeWhile { it == '*' }.length <= 1, text)
            assertTrue(text.isNotBlank(), "$doc")
        }
    }

    @Test fun `title name unchanged`() {
        assertEquals("untitled", Document.New.titleName)
        assertEquals("Hello, world", example.titleName)
        assertEquals("loop.asm", opened.titleName)
    }
}

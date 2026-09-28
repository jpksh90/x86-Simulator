package x86sim.ui

import java.io.File

/** The program in the editor: where it came from and what to call it. */
sealed interface Document {
    /** Created with File → New and never saved. */
    data object New : Document
    /** A built-in example, named as in the Examples menu. */
    data class Example(val displayName: String) : Document
    /** Opened from or saved to disk. */
    data class Opened(val file: File) : Document
    /** A listing made from a compiled program; never saved over [binary]. */
    data class Disassembly(val binary: File, val summary: String) : Document
}

/** The name shown in the window title (unchanged from before the toolbar label existed). */
val Document.titleName: String
    get() = when (this) {
        Document.New -> "untitled"
        is Document.Example -> displayName
        is Document.Opened -> file.name
        is Document.Disassembly -> "${binary.name} (disassembly)"
    }

/** Toolbar label naming the open program; a leading `*` means unsaved (a new file always is). */
fun labelText(doc: Document, unsaved: Boolean): String = when (doc) {
    Document.New -> "*New File"
    is Document.Example, is Document.Opened, is Document.Disassembly -> (if (unsaved) "*" else "") + doc.titleName
}

/** Hover text for the toolbar label: the full name, or the full path of a file on disk. */
fun tooltipText(doc: Document): String = when (doc) {
    Document.New -> "New File (not saved yet)"
    is Document.Example -> "Example: ${doc.displayName}"
    is Document.Opened -> doc.file.absolutePath
    is Document.Disassembly -> "${doc.binary.absolutePath} — ${doc.summary}"
}

/** The file name the save dialog suggests: a disassembly becomes `<binary name>.asm`. */
fun defaultSaveName(doc: Document): String = when (doc) {
    is Document.Opened -> doc.file.name
    is Document.Disassembly -> doc.binary.nameWithoutExtension.ifEmpty { doc.binary.name } + ".asm"
    Document.New, is Document.Example -> "program.asm"
}

/** The folder the save dialog opens in: next to the binary for a disassembly, otherwise the dialog's default. */
fun saveDirectory(doc: Document): File? = (doc as? Document.Disassembly)?.binary?.absoluteFile?.parentFile

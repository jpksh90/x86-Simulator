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
}

/** The name shown in the window title (unchanged from before the toolbar label existed). */
val Document.titleName: String
    get() = when (this) {
        Document.New -> "untitled"
        is Document.Example -> displayName
        is Document.Opened -> file.name
    }

/** Toolbar label naming the open program; a leading `*` means unsaved (a new file always is). */
fun labelText(doc: Document, unsaved: Boolean): String = when (doc) {
    Document.New -> "*New File"
    is Document.Example, is Document.Opened -> (if (unsaved) "*" else "") + doc.titleName
}

/** Hover text for the toolbar label: the full name, or the full path of a file on disk. */
fun tooltipText(doc: Document): String = when (doc) {
    Document.New -> "New File (not saved yet)"
    is Document.Example -> "Example: ${doc.displayName}"
    is Document.Opened -> doc.file.absolutePath
}

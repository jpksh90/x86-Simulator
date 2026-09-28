package x86sim.disasm

/** The disassembly text shown in the editor or printed by `disasm` (specs/005-disassemble-binary/contracts/listing-format.md). */
data class Listing(
    val text: String,
    /** e.g. "ELF64 x86-64 executable". */
    val summary: String,
    val instructionCount: Int,
    val invalidByteCount: Int,
    /** True when the listing was cut at [MAX_LINES]. */
    val truncated: Boolean,
) {
    companion object {
        const val MAX_LINES = 10_000
        const val MAX_DATA_BYTES = 4096
        const val COMMENT_COLUMN = 48
    }
}

/** Thrown when the caller's cancel check returns true part-way through. */
class DisassemblyCancelled : Exception("disassembly cancelled")

/** Appends listing lines, stopping with a cut-off line once [Listing.MAX_LINES] is reached. */
internal class ListingBuilder {
    private val sb = StringBuilder()
    private var lines = 0
    private var last: String? = null
    var truncated = false; private set

    /** Adds [s]; [at] is the address the line is about, named in the cut-off line if this is where it stops. */
    fun add(s: String, at: Long) {
        if (truncated) return
        if (lines == Listing.MAX_LINES - 1) {
            append("; --- listing cut off here: limit of ${Listing.MAX_LINES} lines reached (address 0x${at.toString(16)}) ---")
            truncated = true
            return
        }
        append(s)
    }

    private fun append(s: String) { sb.append(s).append('\n'); lines++; last = s }

    fun blank(at: Long) { if (last != null && last != "") add("", at) }

    fun section(name: String, at: Long) { blank(at); add("section $name", at); add("", at) }

    fun label(name: String, at: Long) { blank(at); add("$name:", at) }

    /** An indented line with a `;` comment at [Listing.COMMENT_COLUMN] (or one space after long text). */
    fun commented(text: String, comment: String, at: Long) {
        val body = "        $text"
        add((if (body.length < Listing.COMMENT_COLUMN) body.padEnd(Listing.COMMENT_COLUMN) else "$body ") + "; $comment", at)
    }

    override fun toString() = sb.toString()
}

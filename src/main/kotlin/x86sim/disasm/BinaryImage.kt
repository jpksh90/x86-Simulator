package x86sim.disasm

/** The executable formats that can be disassembled. */
enum class BinaryFormat(val display: String) {
    ELF64("ELF64"), MACHO64("Mach-O 64-bit"), PE32PLUS("PE32+"),
}

/** A named range of bytes at a virtual address. */
class Section(val name: String, val address: Long, val bytes: ByteArray) {
    val end: Long get() = address + bytes.size
    operator fun contains(addr: Long) = addr >= address && addr < end
}

/** What a format reader found in a supported x86-64 file. */
data class BinaryImage(
    val format: BinaryFormat,
    /** "executable", "shared library", "object file", "DLL", ... */
    val kind: String,
    /** Set when the code came from one slice of a Mach-O universal binary. */
    val fatSliceNote: String?,
    /** Virtual address of the entry point; null when the file has none (e.g. an object file). */
    val entry: Long?,
    val codeSections: List<Section>,
    val dataSections: List<Section>,
    /** Raw symbol names from the file, by address. */
    val symbols: Map<Long, String>,
    /** Library call stubs or import slots, by address (e.g. `puts@plt`). */
    val imports: Map<Long, String>,
)

/** e.g. "ELF64 x86-64 executable". */
val BinaryImage.summary: String get() = "${format.display} x86-64 $kind"

/** Throws [DamagedException] when the image can't be disassembled as it stands. */
fun BinaryImage.validate() {
    if (codeSections.none { it.bytes.isNotEmpty() }) throw DamagedException("it has no code to disassemble")
    val e = entry
    if (e != null && e != 0L && codeSections.none { e in it })
        throw DamagedException("its entry point 0x${e.toString(16)} is outside its code")
}

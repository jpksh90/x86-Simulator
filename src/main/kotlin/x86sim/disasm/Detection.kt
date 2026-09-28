package x86sim.disasm

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/** Why a file can't be disassembled. */
enum class RejectReason { NOT_EXECUTABLE, WRONG_ARCHITECTURE, THIRTY_TWO_BIT, DAMAGED, UNREADABLE }

/** The result of checking a file: either a supported x86-64 program or a reason it isn't one. */
sealed interface Detection {
    data class Supported(val image: BinaryImage) : Detection
    data class Rejected(val reason: RejectReason, val message: String) : Detection
}

/** Decides from its contents (never its name) whether [bytes] is an x86-64 program we can read. */
fun detect(bytes: ByteArray, fileName: String): Detection {
    val r = ByteReader(bytes)
    val format = when {
        startsWith(bytes, 0x7F, 0x45, 0x4C, 0x46) -> "an ELF"
        startsWith(bytes, 0xCF, 0xFA, 0xED, 0xFE) || startsWith(bytes, 0xCE, 0xFA, 0xED, 0xFE) -> "a Mach-O"
        startsWith(bytes, 0xFE, 0xED, 0xFA, 0xCE) || startsWith(bytes, 0xFE, 0xED, 0xFA, 0xCF) -> "a Mach-O"
        startsWith(bytes, 0xCA, 0xFE, 0xBA, 0xBE) -> "a Mach-O universal"
        startsWith(bytes, 0x4D, 0x5A) -> "a Windows (PE)"
        else -> null
    }
    return try {
        when (format) {
            "an ELF" -> detectElf(r, fileName)
            "a Mach-O" -> detectMachO(r, fileName)
            "a Mach-O universal" -> detectFat(r, fileName)
            "a Windows (PE)" -> detectPe(r, fileName)
            else -> null
        } ?: notExecutable(bytes, fileName)
    } catch (e: DamagedException) {
        Detection.Rejected(RejectReason.DAMAGED, "$fileName looks like $format file but is damaged or incomplete: ${e.message}.")
    }
}

private fun startsWith(b: ByteArray, vararg magic: Int) =
    b.size >= magic.size && magic.indices.all { b[it].toInt() and 0xff == magic[it] }

private const val ONLY_X64 = "Only x86-64 programs can be disassembled."
private const val ONLY_64 = "Only 64-bit (x86-64) programs can be disassembled."

private fun wrongCpu(message: String) = Detection.Rejected(RejectReason.WRONG_ARCHITECTURE, "$message $ONLY_X64")
private fun thirtyTwoBit(name: String, format: String) =
    Detection.Rejected(RejectReason.THIRTY_TWO_BIT, "$name is a 32-bit x86 $format program. $ONLY_64")

private fun detectElf(r: ByteReader, name: String): Detection? {
    val cls = r.u8(4); val data = r.u8(5)
    val machine = if (data == 2) (r.u8(18) shl 8) or r.u8(19) else r.u16le(18)
    if (data == 2) return wrongCpu("$name is a big-endian ELF program for ${elfCpu(machine)}.")
    if (cls == 2 && machine == 0x3E) return Detection.Supported(ElfReader.read(r))
    if (machine == 3 || machine == 0x3E) return thirtyTwoBit(name, "ELF")
    return wrongCpu("$name is an ELF program for ${elfCpu(machine)}.")
}

private fun elfCpu(machine: Int) = when (machine) {
    3 -> "x86"
    0x3E -> "x86-64"
    0xB7 -> "ARM64"
    0x28 -> "ARM"
    0xF3 -> "RISC-V"
    0x08 -> "MIPS"
    0x14 -> "PowerPC"
    0x15 -> "PowerPC64"
    0x16 -> "IBM S/390"
    0x2B -> "SPARC V9"
    else -> "an unknown CPU (machine 0x${machine.toString(16)})"
}

private fun detectMachO(r: ByteReader, name: String): Detection? {
    val bigEndian = r.u8(0) == 0xFE
    val cpu = if (bigEndian) r.u32be(4) else r.u32le(4)
    val is64 = r.u8(if (bigEndian) 3 else 0) == 0xCF
    if (!bigEndian && is64 && cpu == CPU_X86_64) return Detection.Supported(MachOReader.read(r))
    if (cpu == 7L) return thirtyTwoBit(name, "Mach-O")
    return wrongCpu("$name is a Mach-O program for ${machOCpu(cpu)}.")
}

private fun detectFat(r: ByteReader, name: String): Detection? {
    val n = r.u32be(4)
    // Java class files share the CAFEBABE magic; there the next word is a version number (45 and up).
    if (n == 0L || n > 32) return null
    val slices = (0 until n).map { i -> r.u32be(8 + 20 * i) to r.u32be(8 + 20 * i + 8) }
    val x64 = slices.firstOrNull { it.first == CPU_X86_64 }
        ?: return wrongCpu("$name is a universal binary containing only ${slices.joinToString(", ") { machOCpuShort(it.first) }}.")
    if (x64.second >= r.size) throw DamagedException("its x86-64 slice starts past the end of the file")
    val others = slices.filter { it !== x64 }.map { machOCpuShort(it.first) }
    val note = "x86-64 slice of a universal binary" + if (others.isEmpty()) "" else " (also contains ${others.joinToString(", ")})"
    if (r.u32le(x64.second) != 0xFEEDFACFL) throw DamagedException("its x86-64 slice is not a Mach-O image")
    return Detection.Supported(MachOReader.read(r, x64.second, note))
}

private fun detectPe(r: ByteReader, name: String): Detection {
    val dos = Detection.Rejected(RejectReason.NOT_EXECUTABLE,
        "$name is an old DOS program, not a modern executable. Only x86-64 ELF, Mach-O and Windows (PE) programs can be disassembled.")
    if (r.size < 0x40) return dos
    val pe = r.u32le(0x3C)
    if (pe + 24 > r.size || r.u32le(pe) != 0x00004550L) return dos
    val machine = r.u16le(pe + 4)
    return when (machine) {
        0x8664 -> {
            if (r.u16le(pe + 24) != 0x20B) throw DamagedException("its optional header is not the 64-bit (PE32+) kind")
            Detection.Supported(PeReader.read(r))
        }
        0x14C -> thirtyTwoBit(name, "Windows")
        else -> wrongCpu("$name is a Windows program for ${peCpu(machine)}.")
    }
}

private fun peCpu(machine: Int) = when (machine) {
    0xAA64 -> "ARM64"
    0x1C0, 0x1C2, 0x1C4 -> "ARM"
    0x200 -> "Itanium"
    else -> "an unknown CPU (machine 0x${machine.toString(16)})"
}

private const val CPU_X86_64 = 0x01000007L

private fun machOCpu(cpu: Long) = when (cpu) {
    0x0100000CL -> "ARM64 (Apple Silicon)"
    12L -> "ARM"
    18L -> "PowerPC"
    0x01000012L -> "PowerPC64"
    else -> "an unknown CPU (type 0x${cpu.toString(16)})"
}

private fun machOCpuShort(cpu: Long) = when (cpu) {
    7L -> "x86"
    CPU_X86_64 -> "x86-64"
    12L -> "arm"
    0x0100000CL -> "arm64"
    0x0200000CL -> "arm64_32"
    18L -> "ppc"
    0x01000012L -> "ppc64"
    else -> "cpu 0x${cpu.toString(16)}"
}

/** Names what a non-executable file looks like, when that's easy to tell. */
private fun notExecutable(bytes: ByteArray, name: String): Detection {
    fun looksLike(what: String) =
        Detection.Rejected(RejectReason.NOT_EXECUTABLE, "$name is not an executable program — it looks like $what.")
    return when {
        bytes.isEmpty() -> Detection.Rejected(RejectReason.NOT_EXECUTABLE, "$name is empty — it is not an executable program.")
        startsWith(bytes, 0x89, 0x50, 0x4E, 0x47) -> looksLike("a PNG image")
        startsWith(bytes, 0xFF, 0xD8, 0xFF) -> looksLike("a JPEG image")
        startsWith(bytes, 0x47, 0x49, 0x46, 0x38) -> looksLike("a GIF image")
        startsWith(bytes, 0x25, 0x50, 0x44, 0x46) -> looksLike("a PDF document")
        startsWith(bytes, 0x50, 0x4B, 0x03, 0x04) -> looksLike("a ZIP archive")
        startsWith(bytes, 0xCA, 0xFE, 0xBA, 0xBE) -> looksLike("a Java class file")
        looksLikeText(bytes) -> Detection.Rejected(RejectReason.NOT_EXECUTABLE,
            "$name is not an executable program — it looks like a text file (assembly or source code?). Use File → Open for .asm files.")
        else -> Detection.Rejected(RejectReason.NOT_EXECUTABLE,
            "$name is not an executable program: it isn't an ELF, Mach-O or Windows (PE) file.")
    }
}

/** The first 512 bytes are UTF-8 with no NUL and nearly all printable characters or whitespace. */
private fun looksLikeText(bytes: ByteArray): Boolean {
    val head = bytes.copyOf(minOf(bytes.size, 512))
    if (head.any { it == 0.toByte() }) return false
    val text = String(head, Charsets.UTF_8)
    val bad = text.count { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t\u000c") }
    return bad <= 1 + text.length / 20 && text.count { it == '\uFFFD' } <= 1
}

/** Reads [file] and runs [detect]; a file that can't be read becomes [RejectReason.UNREADABLE]. */
fun readAndDetect(file: File): Detection {
    fun unreadable(why: String) = Detection.Rejected(RejectReason.UNREADABLE, "Can't read ${file.path}: $why.")
    if (file.isDirectory) return unreadable("it is a folder, not a file")
    if (!file.exists()) return unreadable("file not found")
    if (!file.canRead()) return unreadable("permission denied")
    val bytes = try {
        file.readBytes()
    } catch (e: NoSuchFileException) {
        return unreadable("file not found")
    } catch (e: FileNotFoundException) {
        return unreadable(if (file.exists()) "permission denied" else "file not found")
    } catch (e: AccessDeniedException) {
        return unreadable("permission denied")
    } catch (e: java.io.IOException) {
        return unreadable(e.message ?: "read error")
    } catch (e: OutOfMemoryError) {
        return unreadable("it is too large")
    }
    return detect(bytes, file.name)
}

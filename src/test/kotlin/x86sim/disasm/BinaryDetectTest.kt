package x86sim.disasm

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import x86sim.disasm.TestBinaries.elf32
import x86sim.disasm.TestBinaries.elf64
import x86sim.disasm.TestBinaries.fat
import x86sim.disasm.TestBinaries.hex
import x86sim.disasm.TestBinaries.machO32
import x86sim.disasm.TestBinaries.machO64
import x86sim.disasm.TestBinaries.patch64
import x86sim.disasm.TestBinaries.pe64
import x86sim.disasm.TestBinaries.truncate

/** Every reason a file is turned away, with the exact learner-facing message (FR-004, SC-003). */
class BinaryDetectTest {
    private fun rejected(b: ByteArray, name: String = "prog"): Detection.Rejected = assertIs(detect(b, name))

    private fun check(b: ByteArray, reason: RejectReason, message: String, name: String = "prog") {
        val r = rejected(b, name)
        assertEquals(reason, r.reason)
        assertEquals(message, r.message)
    }

    private val only64 = "Only 64-bit (x86-64) programs can be disassembled."
    private val onlyX64 = "Only x86-64 programs can be disassembled."

    @Test fun `text file`() = check(
        File("src/main/resources/examples/01_hello.asm").readBytes(), RejectReason.NOT_EXECUTABLE,
        "01_hello.asm is not an executable program — it looks like a text file (assembly or source code?). Use File → Open for .asm files.",
        "01_hello.asm",
    )

    @Test fun `empty file`() = check(ByteArray(0), RejectReason.NOT_EXECUTABLE, "prog is empty — it is not an executable program.")

    @Test fun `images, documents and archives`() {
        val what = "prog is not an executable program — it looks like"
        check(hex("89 50 4e 47 0d 0a 1a 0a 00 00"), RejectReason.NOT_EXECUTABLE, "$what a PNG image.")
        check(hex("ff d8 ff e0 00 10"), RejectReason.NOT_EXECUTABLE, "$what a JPEG image.")
        check("GIF89a\u0000\u0001".toByteArray(), RejectReason.NOT_EXECUTABLE, "$what a GIF image.")
        check("%PDF-1.7\n\u0000".toByteArray(), RejectReason.NOT_EXECUTABLE, "$what a PDF document.")
        check(hex("50 4b 03 04 14 00 00 00"), RejectReason.NOT_EXECUTABLE, "$what a ZIP archive.")
        check(hex("ca fe ba be 00 00 00 34 00 10"), RejectReason.NOT_EXECUTABLE, "$what a Java class file.")
    }

    @Test fun `unknown binary data`() = check(
        hex("00 01 02 03 04 05 06 07 00 ff"), RejectReason.NOT_EXECUTABLE,
        "prog is not an executable program: it isn't an ELF, Mach-O or Windows (PE) file.",
    )

    @Test fun `elf for other cpus`() {
        check(elf64(machine = 0xB7), RejectReason.WRONG_ARCHITECTURE, "prog is an ELF program for ARM64. $onlyX64")
        check(elf64(machine = 0xF3), RejectReason.WRONG_ARCHITECTURE, "prog is an ELF program for RISC-V. $onlyX64")
        check(elf32(machine = 0x28), RejectReason.WRONG_ARCHITECTURE, "prog is an ELF program for ARM. $onlyX64")
        check(elf64(machine = 0x1234), RejectReason.WRONG_ARCHITECTURE,
            "prog is an ELF program for an unknown CPU (machine 0x1234). $onlyX64")
    }

    @Test fun `32-bit elf`() = check(elf32(), RejectReason.THIRTY_TWO_BIT, "prog is a 32-bit x86 ELF program. $only64")

    @Test fun `big-endian elf`() {
        val b = elf64(bigEndian = true, machine = 0x1500) // written as bytes 00 15; read big-endian that is 0x15 = PowerPC64
        check(b, RejectReason.WRONG_ARCHITECTURE, "prog is a big-endian ELF program for PowerPC64. $onlyX64")
    }

    @Test fun `mach-o for other cpus`() {
        check(machO64(cputype = 0x0100000C), RejectReason.WRONG_ARCHITECTURE,
            "prog is a Mach-O program for ARM64 (Apple Silicon). $onlyX64")
        check(machO32(), RejectReason.THIRTY_TWO_BIT, "prog is a 32-bit x86 Mach-O program. $only64")
        check(machO32(cputype = 12), RejectReason.WRONG_ARCHITECTURE, "prog is a Mach-O program for ARM. $onlyX64")
        check(hex("fe ed fa ce 00 00 00 12"), RejectReason.WRONG_ARCHITECTURE, "prog is a Mach-O program for PowerPC. $onlyX64")
    }

    @Test fun `universal binary without x86-64`() {
        val arm = machO64(cputype = 0x0100000C)
        check(fat(0x0100000C to arm, 12 to machO32(12)), RejectReason.WRONG_ARCHITECTURE,
            "prog is a universal binary containing only arm64, arm. $onlyX64")
    }

    @Test fun `windows programs for other cpus`() {
        check(pe64(machine = 0xAA64), RejectReason.WRONG_ARCHITECTURE, "prog is a Windows program for ARM64. $onlyX64")
        check(pe64(machine = 0x14C, optMagic = 0x10B), RejectReason.THIRTY_TWO_BIT, "prog is a 32-bit x86 Windows program. $only64")
    }

    @Test fun `dos program`() {
        val b = ByteArray(128).also { it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte(); it[0x3C] = 0x40 }
        check(b, RejectReason.NOT_EXECUTABLE,
            "prog is an old DOS program, not a modern executable. Only x86-64 ELF, Mach-O and Windows (PE) programs can be disassembled.")
    }

    @Test fun `truncated elf`() {
        val r = rejected(truncate(elf64(), 100), "hello")
        assertEquals(RejectReason.DAMAGED, r.reason)
        assertTrue(r.message.startsWith("hello looks like an ELF file but is damaged or incomplete: "), r.message)
    }

    @Test fun `section past the end of the file`() {
        val b = elf64()
        val shoff = ByteReader(b).u64le(40).toInt()
        check(patch64(b, shoff + 64 + 24, 0xFFFFFF), RejectReason.DAMAGED,
            "prog looks like an ELF file but is damaged or incomplete: section .text runs past the end of the file.")
    }

    @Test fun `entry point outside the code`() = check(
        elf64(entry = 0x500000), RejectReason.DAMAGED,
        "prog looks like an ELF file but is damaged or incomplete: its entry point 0x500000 is outside its code.",
    )

    @Test fun `truncated windows and mach-o programs`() {
        assertEquals(RejectReason.DAMAGED, rejected(truncate(pe64(), 0x300)).reason)
        assertEquals(RejectReason.DAMAGED, rejected(truncate(machO64(), 0x300)).reason)
        assertEquals(RejectReason.DAMAGED, rejected(truncate(fat(0x01000007 to machO64()), 0x1100)).reason)
    }

    @Test fun `files that can't be read`() {
        val missing = File("does/not/exist")
        assertEquals(Detection.Rejected(RejectReason.UNREADABLE, "Can't read does/not/exist: file not found."), readAndDetect(missing))
        val dir = Files.createTempDirectory("disasm").toFile()
        try {
            assertEquals(RejectReason.UNREADABLE, (readAndDetect(dir) as Detection.Rejected).reason)
            val f = File(dir, "secret").apply { writeBytes(elf64()); setReadable(false) }
            assumeTrue(!f.canRead(), "running as a user who can read anything")
            assertEquals(Detection.Rejected(RejectReason.UNREADABLE, "Can't read ${f.path}: permission denied."), readAndDetect(f))
        } finally {
            dir.walkBottomUp().forEach { it.setReadable(true); it.delete() }
        }
    }

    @Test fun `no truncation of a valid file ever throws`() {
        for (b in listOf(elf64(data = byteArrayOf(1)), machO64(symbols = mapOf("_m" to 0x100000400L)), pe64(imports = mapOf("k.dll" to listOf("f"))),
            fat(0x01000007 to machO64()))) {
            for (n in 0..b.size step 7) detect(truncate(b, n), "x") // must return, not throw
        }
    }
}

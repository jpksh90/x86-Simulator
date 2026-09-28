package x86sim.disasm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import x86sim.disasm.TestBinaries.HELLO_CODE
import x86sim.disasm.TestBinaries.MACHO_BASE
import x86sim.disasm.TestBinaries.MACHO_CODE_OFF
import x86sim.disasm.TestBinaries.fat
import x86sim.disasm.TestBinaries.machO64

class MachOReaderTest {
    private val codeAddr = MACHO_BASE + MACHO_CODE_OFF

    @Test fun `executable with LC_MAIN`() {
        val img = MachOReader.read(ByteReader(machO64()))
        assertEquals(BinaryFormat.MACHO64, img.format)
        assertEquals("executable", img.kind)
        assertEquals("Mach-O 64-bit x86-64 executable", img.summary)
        assertEquals(listOf("__TEXT,__text"), img.codeSections.map { it.name })
        assertEquals(codeAddr, img.codeSections[0].address)
        assertContentEquals(HELLO_CODE, img.codeSections[0].bytes)
        assertEquals(codeAddr, img.entry)
        assertNull(img.fatSliceNote)
    }

    @Test fun `entry from LC_UNIXTHREAD`() {
        assertEquals(codeAddr, MachOReader.read(ByteReader(machO64(unixThread = true))).entry)
    }

    @Test fun `symbols are read and debug stabs skipped`() {
        val img = MachOReader.read(ByteReader(machO64(symbols = mapOf("_main" to codeAddr), stabs = mapOf("junk" to codeAddr + 5))))
        assertEquals("_main", img.symbols[codeAddr])
        assertFalse(img.symbols.containsKey(codeAddr + 5))
    }

    @Test fun `data sections`() {
        val img = MachOReader.read(ByteReader(machO64(data = byteArrayOf(1, 2, 3))))
        assertEquals(listOf("__DATA,__data"), img.dataSections.map { it.name })
        assertContentEquals(byteArrayOf(1, 2, 3), img.dataSections[0].bytes)
    }

    @Test fun `file types`() {
        assertEquals("object file", MachOReader.read(ByteReader(machO64(filetype = 1))).kind)
        assertEquals("dynamic library", MachOReader.read(ByteReader(machO64(filetype = 6))).kind)
    }

    @Test fun `universal binary uses the x86-64 slice`() {
        val arm = machO64(cputype = 0x0100000C, code = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte()))
        val d = detect(fat(0x0100000C to arm, 0x01000007 to machO64()), "uni")
        d as Detection.Supported
        assertEquals("x86-64 slice of a universal binary (also contains arm64)", d.image.fatSliceNote)
        assertContentEquals(HELLO_CODE, d.image.codeSections[0].bytes)
        assertEquals(codeAddr, d.image.entry)
    }
}

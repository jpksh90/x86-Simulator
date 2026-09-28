package x86sim.disasm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import x86sim.disasm.TestBinaries.HELLO_CODE
import x86sim.disasm.TestBinaries.PE_IMAGE_BASE
import x86sim.disasm.TestBinaries.pe64
import x86sim.disasm.TestBinaries.peIatSlot

class PeReaderTest {
    private fun read(b: ByteArray) = PeReader.read(ByteReader(b))

    @Test fun `executable with text section and entry`() {
        val img = read(pe64())
        assertEquals(BinaryFormat.PE32PLUS, img.format)
        assertEquals("executable", img.kind)
        assertEquals("PE32+ x86-64 executable", img.summary)
        assertEquals(PE_IMAGE_BASE + 0x1000, img.entry)
        assertEquals(listOf(".text"), img.codeSections.map { it.name })
        assertEquals(PE_IMAGE_BASE + 0x1000, img.codeSections[0].address)
        // raw data is padded to 0x200 but VirtualSize trims it back to the real code
        assertContentEquals(HELLO_CODE, img.codeSections[0].bytes)
    }

    @Test fun `dll kind and exports`() {
        val img = read(pe64(dll = true, exports = mapOf("DoThing" to 0x1005L)))
        assertEquals("DLL", img.kind)
        assertEquals("DoThing", img.symbols[PE_IMAGE_BASE + 0x1005])
    }

    @Test fun `imports name their IAT slots`() {
        val b = pe64(imports = mapOf("kernel32.dll" to listOf("ExitProcess", "#7")))
        val img = read(b)
        assertEquals("__imp_kernel32.dll!ExitProcess", img.imports[peIatSlot(b, 0)])
        assertEquals("__imp_kernel32.dll!#7", img.imports[peIatSlot(b, 1)])
    }

    @Test fun `data sections`() {
        val img = read(pe64(data = byteArrayOf(9, 8, 7)))
        // .rdata (initialised data holding the tables) and .data
        assertEquals(listOf(".rdata", ".data"), img.dataSections.map { it.name })
        assertContentEquals(byteArrayOf(9, 8, 7), img.dataSections[1].bytes)
    }
}

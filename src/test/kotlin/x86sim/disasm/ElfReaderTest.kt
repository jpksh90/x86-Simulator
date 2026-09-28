package x86sim.disasm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import x86sim.disasm.TestBinaries.HELLO_CODE
import x86sim.disasm.TestBinaries.elf64

class ElfReaderTest {
    private fun read(b: ByteArray) = ElfReader.read(ByteReader(b))

    @Test fun `executable with text section and entry`() {
        val img = read(elf64())
        assertEquals(BinaryFormat.ELF64, img.format)
        assertEquals("executable", img.kind)
        assertEquals(0x401000L, img.entry)
        assertEquals(1, img.codeSections.size)
        val text = img.codeSections[0]
        assertEquals(".text", text.name)
        assertEquals(0x401000L, text.address)
        assertContentEquals(HELLO_CODE, text.bytes)
        assertEquals("ELF64 x86-64 executable", img.summary)
    }

    @Test fun `file types`() {
        assertEquals("shared library", read(elf64(type = 3)).kind)
        val obj = read(elf64(type = 1, entry = 0, codeAddr = 0))
        assertEquals("object file (sections placed one after another from address 0)", obj.kind)
        assertNull(obj.entry)
    }

    @Test fun `data sections and symbols`() {
        val img = read(elf64(data = "Hi!\n".toByteArray(), withSymbols = mapOf("_start" to 0x401000L, "msg" to 0x402000L)))
        assertEquals(listOf(".data"), img.dataSections.map { it.name })
        assertEquals(0x402000L, img.dataSections[0].address)
        assertContentEquals("Hi!\n".toByteArray(), img.dataSections[0].bytes)
        assertEquals("_start", img.symbols[0x401000L])
        assertEquals("msg", img.symbols[0x402000L])
    }

    @Test fun `nobits sections are ignored`() {
        val img = read(elf64(data = byteArrayOf(1, 2), withBss = true))
        assertEquals(listOf(".data"), img.dataSections.map { it.name })
    }

    @Test fun `without section headers the executable segment is used`() {
        val img = read(elf64(withSectionHeaders = false))
        assertEquals(listOf("LOAD"), img.codeSections.map { it.name })
        assertEquals(0x401000L, img.codeSections[0].address)
        assertContentEquals(HELLO_CODE, img.codeSections[0].bytes)
    }

    @Test fun `plt entries are named after their imports`() {
        val img = read(elf64(data = ByteArray(0x200), pltImports = listOf("puts", "exit")))
        assertEquals("puts@plt", img.imports[0x401800L + 16])
        assertEquals("exit@plt", img.imports[0x401800L + 32])
        assertEquals(listOf(".text", ".plt"), img.codeSections.map { it.name })
    }

    @Test fun `object file sections are laid out one after another`() {
        // .text (21 bytes) and .data both at address 0 in a relocatable file
        val img = read(elf64(type = 1, entry = 0, codeAddr = 0, dataAddr = 0, data = byteArrayOf(7, 7),
            withSymbols = mapOf("_start" to 0L, "msg" to 1L), symShndx = mapOf("msg" to 2)))
        assertEquals(0L, img.codeSections[0].address)
        assertEquals(0x20L, img.dataSections[0].address) // after .text, 16-byte aligned
        assertEquals("_start", img.symbols[0L])
        assertEquals("msg", img.symbols[0x21L])
        assertEquals("object file (sections placed one after another from address 0)", img.kind)
    }
}

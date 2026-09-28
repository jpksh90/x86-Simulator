package x86sim.disasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import x86sim.disasm.TestBinaries.elf64
import x86sim.disasm.TestBinaries.hex

class DisassemblerTest {
    private fun listing(b: ByteArray, name: String = "hello") = Disassembler.listing(b, name) { false }
    private fun lines(b: ByteArray) = listing(b).text.lines()

    @Test fun `full listing of the hello program`() {
        val l = listing(elf64(withSymbols = mapOf("_start" to 0x401000L)))
        assertEquals(
            """
            ; Disassembly of hello (ELF64 x86-64 executable)
            ; Entry point: 0x401000
            ; Code: .text 0x401000-0x401015 (21 bytes)
            ; This is a read-only listing made from a compiled program. It may not assemble or run in x86Learn.
            ; Decoded with iced-x86 (MIT license).

            section .text

            _start:
                    mov     eax, 1                          ; 401000: b8 01 00 00 00
                    mov     edi, 1                          ; 401005: bf 01 00 00 00
                    call    loc_401012                      ; 40100a: e8 03 00 00 00
                    ud2                                     ; 40100f: 0f 0b
                    db      0x06                            ; 401011: 06  (not a valid instruction)

            loc_401012:
                    syscall                                 ; 401012: 0f 05
                    ret                                     ; 401014: c3

            """.trimIndent(),
            l.text,
        )
        assertEquals(6, l.instructionCount)
        assertEquals(1, l.invalidByteCount)
        assertFalse(l.truncated)
        assertEquals("ELF64 x86-64 executable", l.summary)
    }

    @Test fun `entry point is labelled _start when no symbol names it`() {
        assertTrue("_start:" in lines(elf64()))
    }

    @Test fun `entry point keeps its symbol name`() {
        val ls = lines(elf64(withSymbols = mapOf("main" to 0x401000L)))
        assertTrue("main:" in ls)
        assertFalse("_start:" in ls)
    }

    @Test fun `symbol names are sanitised and made unique`() {
        val code = hex("90 90 90 c3")
        val ls = lines(elf64(code = code, withSymbols = mapOf("foo::bar" to 0x401000L, "1abc" to 0x401001L, "a b" to 0x401002L, "a-b" to 0x401003L)))
        assertTrue("foo__bar:" in ls)
        assertTrue("_1abc:" in ls)
        assertTrue("a_b:" in ls)
        assertTrue("a_b_2:" in ls, ls.joinToString("\n"))
    }

    @Test fun `branch into the middle of an instruction gets no label`() {
        // jmp 0x401001 (inside itself), then ret
        val ls = lines(elf64(code = hex("eb ff c3")))
        assertTrue(ls.any { it.startsWith("        jmp     0x401001 ") }, ls.joinToString("\n"))
        assertFalse(ls.any { it.startsWith("loc_") })
    }

    @Test fun `plt calls use the import name`() {
        // call 0x401810 (puts@plt): e8 + rel32 from 0x401005
        val code = hex("e8 0b 08 00 00 c3")
        val ls = lines(elf64(code = code, data = ByteArray(0x200), pltImports = listOf("puts")))
        assertTrue(ls.any { it.startsWith("        call    puts@plt ") }, ls.joinToString("\n"))
        assertTrue("puts@plt:" in ls)
    }

    @Test fun `data sections are listed as db rows`() {
        val data = ByteArray(20) { it.toByte() }
        val l = listing(elf64(data = data, withSymbols = mapOf("msg" to 0x402004L)))
        val ls = l.text.lines()
        assertTrue("; Data: .data 0x402000-0x402014 (20 bytes)" in ls)
        val s = ls.indexOf("section .data")
        assertEquals("", ls[s + 1])
        assertEquals("        db      0x00, 0x01, 0x02, 0x03".padEnd(48) + "; 402000", ls[s + 2])
        assertEquals("", ls[s + 3])
        assertEquals("msg:", ls[s + 4])
        assertEquals(
            "        db      0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13 ; 402004",
            ls[s + 5],
        )
    }

    @Test fun `rip-relative operands use data labels`() {
        // lea rsi, [rel msg] at 0x401000, msg = 0x402000: disp = 0x402000 - 0x401007 = 0xff9
        val ls = lines(elf64(code = hex("48 8d 35 f9 0f 00 00 c3"), data = byteArrayOf(1), withSymbols = mapOf("msg" to 0x402000L)))
        assertTrue(ls.any { it.startsWith("        lea     rsi, [rel msg] ") }, ls.joinToString("\n"))
    }

    @Test fun `long data sections are cut after 4 KiB`() {
        val ls = lines(elf64(data = ByteArray(5000)))
        val s = ls.indexOf("section .data")
        val rows = ls.drop(s).count { it.startsWith("        db") }
        assertEquals(256, rows)
        assertTrue("        ; … 904 more bytes not shown" in ls)
    }

    @Test fun `listing is capped at 10000 lines`() {
        val l = listing(elf64(code = ByteArray(30_000) { 0x90.toByte() }))
        val ls = l.text.removeSuffix("\n").split("\n")
        assertEquals(Listing.MAX_LINES, ls.size)
        assertTrue(ls.last().startsWith("; --- listing cut off here: limit of 10000 lines reached (address 0x"), ls.last())
        assertTrue(l.truncated)
        assertTrue(l.text.endsWith("---\n"))
    }

    @Test fun `cancelling stops the work`() {
        assertFailsWith<DisassemblyCancelled> { Disassembler.listing(elf64(), "hello") { true } }
    }

    @Test fun `a megabyte of code is disassembled quickly`() {
        val code = ByteArray(1 shl 20) { i -> TestBinaries.HELLO_CODE[i % TestBinaries.HELLO_CODE.size] }
        val t = System.nanoTime()
        listing(elf64(code = code))
        assertTrue((System.nanoTime() - t) / 1_000_000 < 5000)
    }
}

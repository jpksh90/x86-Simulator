package x86sim.disasm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Full listings of real and synthetic programs, pinned byte for byte (SC-002). Each `.expected.asm`
 * was checked by hand against `objdump -d --x86-asm-syntax=intel`; see src/test/resources/binaries/README.md.
 */
class ListingGoldenTest {
    private val dir = File("src/test/resources/binaries")

    private fun expected(name: String) = File(dir, "$name.expected.asm").readText()

    private fun check(bytes: ByteArray, name: String) {
        val d = detect(bytes, name)
        d as Detection.Supported
        assertEquals(expected(name), Disassembler.listing(d.image, name) { false }.text)
    }

    @Test fun `mach-o executable built by clang`() = check(File(dir, "hello-macho").readBytes(), "hello-macho")

    @Test fun `elf object built by clang`() = check(File(dir, "hello-elf.o").readBytes(), "hello-elf.o")

    @Test fun `synthetic pe executable with an import`() = check(TestBinaries.helloPe(), "hello-pe.exe")
}

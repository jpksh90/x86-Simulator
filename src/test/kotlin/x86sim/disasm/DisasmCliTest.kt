package x86sim.disasm

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import x86sim.runDisasm

/** `x86learn disasm <binary>` (specs/005-disassemble-binary/contracts/cli-disasm.md). */
class DisasmCliTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun run(path: String): Run {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = runDisasm(path, PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Run(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test fun `prints the same listing as the editor`() {
        val r = run("src/test/resources/binaries/hello-macho")
        assertEquals(0, r.code)
        assertEquals(File("src/test/resources/binaries/hello-macho.expected.asm").readText(), r.out)
        assertEquals("", r.err)
    }

    @Test fun `rejects a text file on stderr with status 1`() {
        val r = run("src/main/resources/examples/01_hello.asm")
        assertEquals(1, r.code)
        assertEquals("", r.out)
        val msg = (readAndDetect(File("src/main/resources/examples/01_hello.asm")) as Detection.Rejected).message
        assertEquals("error: $msg\n", r.err)
    }

    @Test fun `missing file`() {
        val r = run("no/such/file")
        assertEquals(1, r.code)
        assertEquals("error: Can't read no/such/file: file not found.\n", r.err)
    }

    @Test fun `warns when the listing is cut off`() {
        val f = Files.createTempFile("nops", "").toFile()
        try {
            f.writeBytes(TestBinaries.elf64(code = ByteArray(30_000) { 0x90.toByte() }))
            val r = run(f.path)
            assertEquals(0, r.code)
            assertEquals("warning: listing cut off at 10000 lines\n", r.err)
            assertTrue(r.out.endsWith("---\n"))
        } finally { f.delete() }
    }

    @Test fun `usage lists the command`() {
        assertTrue("  x86learn disasm <binary>          disassemble an x86-64 ELF, Mach-O or PE program" in x86sim.USAGE, x86sim.USAGE)
    }
}

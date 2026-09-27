package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import x86sim.analysis.ProgramLint
import x86sim.asm.Assembler

class ProgramLintTest {
    private fun warnings(src: String) = ProgramLint.warnings(Assembler.assemble(src))

    @Test fun `running past the end and an empty label are both warned about`() {
        val w = warnings("_start:\n mov rdi, 2\n mov rsi, 10\n mov rax, 1\n.loop:\n test rsi, rsi\n jz .done\n imul rax, rdi\n.done:\n")
        assertEquals(listOf(7, 8), w.map { it.line })
        assertTrue(w.all { it.warning })
        assertTrue(w[0].message.contains("execution can run past the last instruction"), w[0].message)
        assertTrue(w[1].message.contains("label '.done' has no instruction after it"), w[1].message)
    }

    @Test fun `unreachable code at the end is not warned about`() {
        val w = warnings("_start:\n jmp .done\n nop\n.done:\n")
        assertEquals(listOf(3), w.map { it.line })
        assertTrue(w.single().message.contains("label '.done'"))
    }

    @Test fun `programs that end properly have no warnings`() {
        assertEquals(emptyList(), warnings("_start:\n mov eax, 60\n xor edi, edi\n syscall\n"))
        assertEquals(emptyList(), warnings("_start:\n ret\n"))
    }

    @Test fun `bundled examples have no warnings`() {
        for ((id, _) in Examples.names) assertEquals(emptyList(), warnings(Examples.load(id)), id)
    }
}

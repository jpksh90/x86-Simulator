package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x86sim.analysis.FaultKind
import x86sim.asm.Assembler

class FaultExplainerTest {
    private fun machine(src: String) = Machine().apply {
        onOutput = {}
        load(Assembler.assemble(src))
        runToEnd(1_000_000)
    }

    /** Runs [src] to the end and checks that it crashed on a fetch. */
    private fun fault(src: String): Machine = machine(src).also {
        assertEquals(MachineState.FAULTED, it.state, it.message)
        val f = it.fault!!
        assertTrue(it.message.startsWith("Segmentation fault:"), it.message)
        assertTrue(it.message.contains("RIP=0x%x".format(f.rip)), it.message)
        assertEquals(f.headline, it.message)
    }

    private fun assertHas(text: String, vararg phrases: String) {
        for (p in phrases) assertTrue(text.contains(p), "expected \"$p\" in: $text")
    }

    // ---------------- running off the end (US1) ----------------

    /** The reported bug: `RIP=0x401018 does not point to an instruction`. */
    @Test fun `running past the last instruction names the line`() {
        val m = fault("_start:\n mov rdi, 2\n mov rsi, 10\n mov rax, 1\n.loop:\n test rsi, rsi\n jz .done\n imul rax, rdi\n.done:\n")
        val f = m.fault!!
        assertEquals(FaultKind.RAN_PAST_END, f.kind)
        assertEquals(0x401018L, f.rip)
        assertEquals(7, f.line)
        assertEquals(".done", f.label)
        assertHas(m.message, "ran past the last instruction", "RIP=0x401018", "line 8", "imul rax, rdi")
        assertHas(f.hint, "exit syscall", "'.done'")
    }

    @Test fun `a conditional jump not taken at the end runs past the end`() {
        val f = fault("_start:\n xor eax, eax\n inc eax\n jz _start\n").fault!!
        assertEquals(FaultKind.RAN_PAST_END, f.kind)
        assertEquals(3, f.line)
        assertNull(f.label)
        assertTrue(!f.hint.contains("label"), f.hint)
    }

    @Test fun `jumping to a label with nothing after it`() {
        val m = fault("_start:\n jmp .done\n nop\n.done:\n")
        val f = m.fault!!
        assertEquals(FaultKind.EMPTY_LABEL, f.kind)
        assertEquals(1, f.line)
        assertEquals(".done", f.label)
        assertHas(m.message, "jumped to label '.done'", "no instruction after it", "line 2")
    }

    @Test fun `calling a label with nothing after it`() {
        val m = fault("_start:\n call f\n ret\n nop\nf:\n")
        assertEquals(FaultKind.EMPTY_LABEL, m.fault!!.kind)
        assertHas(m.message, "called", "'f'")
    }

    @Test fun `an empty _start is reported as the entry point`() {
        val m = fault("nop\n_start:\n")
        val f = m.fault!!
        assertEquals(FaultKind.ENTRY, f.kind)
        assertNull(f.line)
        assertHas(m.message, "no instruction after _start")
    }

    @Test fun `ret from _start still exits cleanly`() {
        val m = machine("_start:\n mov eax, 3\n ret\n")
        assertEquals(MachineState.EXITED, m.state)
        assertEquals(3, m.exitCode)
        assertNull(m.fault)
    }

    // ---------------- bad ret and indirect targets (US2) ----------------

    @Test fun `ret to a value that is not a return address`() {
        val m = fault("_start:\n push 1\n ret\n")
        val f = m.fault!!
        assertEquals(FaultKind.BAD_RETURN, f.kind)
        assertEquals(1L, f.rip)
        assertEquals(2, f.line)
        assertHas(m.message, "ret on line 3", "which is not a return address")
        assertHas(f.hint, "matching pop")
    }

    @Test fun `unbalanced stack inside a called function`() {
        val f = fault("_start:\n call f\n ret\nf:\n push 9\n ret\n").fault!!
        assertEquals(FaultKind.BAD_RETURN, f.kind)
        assertEquals(9L, f.rip)
        assertEquals(5, f.line)
    }

    @Test fun `indirect jmp or call to a non-code address`() {
        val m = fault("_start:\n mov rax, 5\n jmp rax\n")
        assertEquals(FaultKind.BAD_TARGET, m.fault!!.kind)
        assertEquals(5L, m.fault!!.rip)
        assertHas(m.message, "which is not an instruction", "line 3")
        assertEquals(FaultKind.BAD_TARGET, fault("_start:\n mov rax, 7\n call rax\n").fault!!.kind)
    }

    @Test fun `jumping into the middle of an instruction`() {
        val m = fault("_start:\n mov rax, _start\n add rax, 2\n jmp rax\n")
        val f = m.fault!!
        assertEquals(FaultKind.MISALIGNED, f.kind)
        assertEquals(0x401002L, f.rip)
        assertHas(m.message, "inside the code but not at the start of an instruction")
        assertHas(f.hint, "line 2", "0x401000")
    }
}

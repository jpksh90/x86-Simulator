package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import x86sim.asm.Assembler
import x86sim.cpu.Registers

class StepBackTest {
    /** A machine whose output goes to a buffer that stepping back can rewind. */
    private class Rig(src: String) {
        val out = StringBuilder()
        val m = Machine().apply {
            onOutput = { out.append(it) }
            outputMark = { out.length }
            onRewindOutput = { out.setLength(it) }
            load(Assembler.assemble(src))
        }

        /** Everything observable: registers, flags, rip, state, steps and all mapped memory. */
        fun snapshot(): List<Any?> = listOf(
            m.cpu.regs.toList(), m.cpu.rip, m.cpu.rflags, m.cpu.repeating, m.state, m.steps, m.exitCode, out.toString(),
            m.memory.mapped.map { r -> Triple(r.bytes.toList(), r.touched.clone(), r.writer.toList()) },
        )
    }

    @Test fun `stepping back to the start restores the exact initial state`() {
        for ((id, _) in Examples.names) {
            val rig = Rig(Examples.load(id))
            val initial = rig.snapshot()
            while (true) {
                rig.m.runToEnd()
                if (rig.m.state == MachineState.WAITING_INPUT) { rig.m.provideInput("Ada\n"); continue }
                break
            }
            assertEquals(MachineState.EXITED, rig.m.state, id)
            val n = rig.m.steps
            var back = 0
            while (rig.m.stepBack()) back++
            assertEquals(n.toInt(), back, "$id: every executed step can be undone")
            assertEquals(initial, rig.snapshot(), "$id: state after stepping all the way back")
        }
    }

    @Test fun `step back then forward replays identically`() {
        val rig = Rig(Examples.load("03_factorial"))
        repeat(60) { rig.m.step() }
        val at60 = rig.snapshot()
        repeat(25) { rig.m.stepBack() }
        repeat(25) { rig.m.step() }
        assertEquals(at60, rig.snapshot())
    }

    @Test fun `output printed by a step is removed when stepping back over it`() {
        val rig = Rig(Examples.load("01_hello"))
        rig.m.runToEnd()
        assertEquals("Hello, world!\n", rig.out.toString())
        rig.m.stepBack() // exit syscall
        rig.m.stepBack(); rig.m.stepBack() // xor edi, mov rax
        assertEquals("Hello, world!\n", rig.out.toString())
        rig.m.stepBack() // the write syscall
        assertEquals("", rig.out.toString())
        assertEquals(MachineState.PAUSED, rig.m.state)
        assertEquals(1L, rig.m.cpu.regs[Registers.RAX], "rax = 1 (sys_write) again, before the syscall ran")
    }

    @Test fun `input consumed by read is given back and replayed`() {
        val rig = Rig(Examples.load("04_echo_name"))
        rig.m.runToEnd()
        assertEquals(MachineState.WAITING_INPUT, rig.m.state)
        rig.m.provideInput("Ada\n")
        rig.m.runToEnd()
        assertEquals("What is your name? Nice to meet you, Ada\n", rig.out.toString())
        repeat(15) { rig.m.stepBack() } // back past the read
        assertFalse(rig.out.contains("Nice"))
        rig.m.runToEnd() // no new input needed: "Ada" is read again
        assertEquals(MachineState.EXITED, rig.m.state)
        assertEquals("What is your name? Nice to meet you, Ada\n", rig.out.toString())
    }

    @Test fun `a crash can be stepped back out of`() {
        val rig = Rig("_start:\n mov rbx, 7\n xor ecx, ecx\n div rcx\n")
        rig.m.runToEnd()
        assertEquals(MachineState.FAULTED, rig.m.state)
        assertTrue(rig.m.stepBack())
        assertEquals(MachineState.PAUSED, rig.m.state)
        assertEquals("div rcx", rig.m.cpu.currentInstruction()!!.source)
        assertEquals(7L, rig.m.cpu.regs[Registers.RBX])
    }

    @Test fun `history is bounded`() {
        val rig = Rig(Examples.load("02_loop_sum"))
        rig.m.historyLimit = 10
        rig.m.runToEnd()
        assertEquals(10, rig.m.historySize)
        repeat(10) { assertTrue(rig.m.stepBack()) }
        assertFalse(rig.m.stepBack())
    }

    private val copy = "section .data\nsrc db 'abcdefghij'\nsection .bss\ndst resb 10\nsection .text\n" +
        "_start:\n lea rsi, [src]\n lea rdi, [dst]\n mov ecx, 10\n rep movsb\n hlt\n"

    @Test fun `stepping a rep instruction one iteration at a time and back`() {
        val rig = Rig(copy)
        val m = rig.m
        val dst = m.program!!.symbols.getValue("dst")
        while (m.cpu.currentInstruction()!!.source != "rep movsb") m.step()
        val address = m.cpu.rip
        val forward = mutableListOf(rig.snapshot())
        m.step()
        assertEquals(address, m.cpu.rip, "still on the rep instruction")
        assertTrue(m.cpu.repeating)
        assertEquals(9L, m.cpu.regs[Registers.RCX])
        assertEquals('a'.code, m.memory.peek(dst))
        forward += rig.snapshot()
        repeat(9) { m.step(); forward += rig.snapshot() }
        assertEquals(address + 4, m.cpu.rip, "moved on after the last iteration")
        assertFalse(m.cpu.repeating)
        // walk all the way back, checking every intermediate state, then forward again
        for (k in 9 downTo 0) { assertTrue(m.stepBack()); assertEquals(forward[k], rig.snapshot(), "back to iteration $k") }
        for (k in 1..10) { m.step(); assertEquals(forward[k], rig.snapshot(), "forward to iteration $k") }
        repeat(2) { m.stepBack() }
        assertEquals(2L, m.cpu.regs[Registers.RCX])
        assertTrue(m.cpu.repeating)
        assertEquals(listOf(0, 0), listOf(m.memory.peek(dst + 8), m.memory.peek(dst + 9)))
    }

    @Test fun `a fault partway through a repeat can be stepped back out of`() {
        val rig = Rig("section .bss\nbuf resb 16\nsection .text\n_start:\n lea rdi, [buf+4094]\n mov ecx, 5\n rep stosb\n hlt\n")
        rig.m.runToEnd()
        assertEquals(MachineState.FAULTED, rig.m.state)
        assertTrue(rig.m.stepBack())
        assertEquals(MachineState.PAUSED, rig.m.state)
        assertEquals(3L, rig.m.cpu.regs[Registers.RCX])
        assertTrue(rig.m.cpu.repeating)
        assertEquals("rep stosb", rig.m.cpu.currentInstruction()!!.source)
        assertTrue(rig.m.stepBack())
        assertEquals(4L, rig.m.cpu.regs[Registers.RCX])
    }
}

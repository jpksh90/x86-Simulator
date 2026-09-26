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
            m.cpu.regs.toList(), m.cpu.rip, m.cpu.rflags, m.state, m.steps, m.exitCode, out.toString(),
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
}

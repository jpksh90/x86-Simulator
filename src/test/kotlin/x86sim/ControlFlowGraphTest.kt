package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import x86sim.analysis.ControlFlowGraph
import x86sim.analysis.EdgeKind
import x86sim.asm.Assembler

class ControlFlowGraphTest {
    private fun cfg(src: String) = ControlFlowGraph(Assembler.assemble(src))

    @Test fun `loop splits into blocks with a back edge`() {
        val g = cfg(Examples.load("02_loop_sum"))
        assertEquals(listOf("_start", "print_uint"), g.functions.map { it.name })
        val start = g.functions[0]
        // _start: [xor, mov] -> [.again: add, loop] -> [mov, call, mov, xor, syscall(exit)]
        assertEquals(3, start.blocks.size)
        assertEquals("_start.again", start.blocks[1].name)
        val kinds = start.edges.map { Triple(it.from, it.to, it.kind) }.toSet()
        val (b0, b1, b2) = start.blocks.map { it.id }
        assertEquals(setOf(Triple(b0, b1, EdgeKind.ALWAYS), Triple(b1, b1, EdgeKind.TAKEN), Triple(b1, b2, EdgeKind.NOT_TAKEN)), kinds)
        assertEquals("exit", start.blocks[2].exit)
        assertEquals(listOf("print_uint"), start.blocks[2].calls)
    }

    @Test fun `exit syscall ends the entry function instead of falling into the next one`() {
        val g = cfg(Examples.load("03_factorial"))
        val start = g.functions.first { it.name == "_start" }
        assertTrue(start.blocks.none { b -> b.instructions.any { it.source.startsWith("push rbp") } })
        val fact = g.functions.first { it.name == "factorial" }
        assertEquals(listOf("factorial"), fact.blocks.flatMap { it.calls })
        assertTrue(fact.blocks.any { it.exit == "ret" })
    }

    @Test fun `every example builds a graph where all code is reachable`() {
        for ((id, _) in Examples.names) {
            val g = cfg(Examples.load(id))
            assertTrue(g.functions.none { it.name == "(unreachable code)" }, id)
            assertEquals(g.blocks.sumOf { it.instructions.size }, Assembler.assemble(Examples.load(id)).instructions.size)
        }
    }

    @Test fun `dead code is reported as unreachable`() {
        val g = cfg("_start:\n jmp done\n nop\ndone:\n hlt\n")
        assertEquals("(unreachable code)", g.functions.last().name)
        assertEquals("nop", g.functions.last().blocks.single().instructions.single().mnemonic)
    }

    @Test fun `lods clobbers rax for exit-syscall detection`() {
        val g = cfg("_start:\n mov eax, 60\n lodsb\n syscall\n hlt\n")
        assertTrue(g.blocks.none { it.exit == "exit" })
    }

    @Test fun `a rep instruction stays inside its basic block`() {
        val g = cfg("_start:\n mov ecx, 3\n rep movsb\n hlt\n")
        assertEquals(1, g.blocks.size)
    }
}

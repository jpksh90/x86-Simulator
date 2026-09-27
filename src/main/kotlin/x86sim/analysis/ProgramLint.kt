package x86sim.analysis

import x86sim.asm.AsmError
import x86sim.asm.Program
import x86sim.cpu.Instruction

/** Build-time warnings: mistakes that assemble fine but will crash when run. Never blocks a build. */
object ProgramLint {

    fun warnings(program: Program): List<AsmError> {
        val out = sortedMapOf<Int, AsmError>()

        // W1: a reachable block that simply runs off the end of the code.
        val cfg = ControlFlowGraph(program)
        val reachable = cfg.functions.filter { it.name != "(unreachable code)" }.flatMap { it.blocks }
        for (b in reachable) if (b.exit == "end of code")
            out[b.last.line] = AsmError(b.last.line,
                "execution can run past the last instruction: end with ret, jmp or an exit syscall", warning = true)

        // W2: a label with nothing after it. Wins over W1 on the same line.
        val end = program.instructions.maxOf { it.address } + Instruction.INSTRUCTION_SLOT
        for ((name, value) in program.symbols) {
            val line = program.labelLines[name] ?: continue
            if (value == end)
                out[line] = AsmError(line, "label '${FaultExplainer.asWritten(name)}' has no instruction after it", warning = true)
        }
        return out.values.toList()
    }
}

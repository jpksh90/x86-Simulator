package x86sim.analysis

import x86sim.asm.Program
import x86sim.cpu.ImmOp
import x86sim.cpu.Instruction

/** How execution came to reach an address with no instruction. */
enum class FaultKind { ENTRY, RAN_PAST_END, EMPTY_LABEL, BAD_RETURN, BAD_TARGET, MISALIGNED }

/**
 * A learner-facing explanation of a fetch fault: what went wrong ([headline], which always
 * starts with "Segmentation fault:" and shows the RIP), which line caused it and what to do.
 */
data class FaultReport(
    val kind: FaultKind,
    val rip: Long,
    /** 0-based source line of the instruction that transferred control; null for [FaultKind.ENTRY]. */
    val line: Int?,
    val source: String?,
    /** The label at [rip], as written in the source (local labels as `.name`). */
    val label: String?,
    val headline: String,
    val hint: String,
)

/** Explains why RIP points at something that isn't an instruction. Pure; no UI. */
object FaultExplainer {

    /** [from] is the instruction that ran last (the one that sent RIP to [rip]), if any. */
    fun explainFetch(program: Program, rip: Long, from: Instruction?): FaultReport {
        val label = displayLabel(program, rip)
        val at = "RIP=0x%x".format(rip)
        fun report(kind: FaultKind, headline: String, hint: String) =
            FaultReport(kind, rip, from?.line, from?.let(::sourceOf), label, "Segmentation fault: $headline", hint)

        if (from == null) {
            val start = label ?: "_start"
            return report(FaultKind.ENTRY, "the program starts at $at, but there is no instruction after $start",
                "Put the program's first instruction after the $start label.")
        }
        val where = "line ${from.line + 1} (${sourceOf(from)})"
        val fellThrough = rip == from.address + Instruction.INSTRUCTION_SLOT && from.mnemonic != "jmp" && from.mnemonic != "call"
        val verb = if (from.mnemonic == "call") "called" else "jumped to"
        val codeEnd = program.instructions.maxOf { it.address } + Instruction.INSTRUCTION_SLOT
        return when {
            from.mnemonic == "ret" -> report(FaultKind.BAD_RETURN,
                "ret on line ${from.line + 1} returned to $at, which is not a return address",
                "The value on top of the stack wasn't pushed by a call. Check that every push has a matching pop before ret.")
            fellThrough -> report(FaultKind.RAN_PAST_END,
                "ran past the last instruction (line ${from.line + 1}: ${sourceOf(from)}) to $at, where there is no code",
                "End the program with an exit syscall (mov eax, 60 / syscall) or ret" +
                    (label?.let { ", or add an instruction after label '$it'" } ?: "") + ".")
            from.operands.firstOrNull() is ImmOp && label != null -> {
                report(FaultKind.EMPTY_LABEL, "$where $verb label '$label', but there is no instruction after it ($at)",
                    "Put an instruction after '$label', such as ret or an exit syscall.")
            }
            rip >= Program.TEXT_BASE && rip < codeEnd -> {
                val near = program.instructions.filter { it.address <= rip }.maxBy { it.address }
                report(FaultKind.MISALIGNED, "$where $verb $at, inside the code but not at the start of an instruction",
                    "The nearest instruction is line ${near.line + 1} at 0x%x; jump to a label instead of a computed address.".format(near.address))
            }
            else -> report(FaultKind.BAD_TARGET, "$where $verb $at, which is not an instruction",
                "Check the address in the register or memory that the ${if (from.mnemonic == "call") "call" else "jump"} goes through.")
        }
    }

    /** The .text label whose address is [addr], as written in the source; local labels win. */
    internal fun displayLabel(program: Program, addr: Long): String? {
        if (addr < Program.TEXT_BASE || addr >= Program.RODATA_BASE) return null
        val names = program.symbols.filter { it.value == addr && it.key in program.labelLines }.keys
        if (names.isEmpty()) return null
        return asWritten(names.firstOrNull { it.indexOf('.', 1) > 0 } ?: names.first())
    }

    /** A symbol name as the learner wrote it: local labels are stored as `parent.name`. */
    internal fun asWritten(name: String): String {
        val dot = name.indexOf('.', 1)
        return if (dot > 0) name.substring(dot) else name
    }

    internal fun sourceOf(ins: Instruction) = ins.source.substringBefore(';').trim()
}

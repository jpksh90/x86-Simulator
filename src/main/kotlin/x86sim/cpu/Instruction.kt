package x86sim.cpu

sealed interface Operand

data class RegOp(val reg: Reg) : Operand {
    override fun toString() = reg.name
}

data class ImmOp(val value: Long) : Operand {
    override fun toString() = if (value in -9..9) value.toString() else "0x%x".format(value)
}

/** A memory reference `[base + index*scale + disp]`, optionally with an explicit size. */
data class MemOp(
    val base: Reg? = null,
    val index: Reg? = null,
    val scale: Int = 1,
    val disp: Long = 0,
    val size: Int = 0, // 0 = not specified in the source; inferred from the other operand
) : Operand

/**
 * One assembled instruction. The simulator executes instructions in this decoded form
 * rather than as encoded machine-code bytes; each one still gets its own address in
 * the .text section so jumps, calls and return addresses behave like the real thing.
 */
data class Instruction(
    val mnemonic: String,
    val operands: List<Operand>,
    val address: Long,
    val line: Int, // 0-based source line
    val source: String,
    /** Repeat prefix; only ever set on string instructions. */
    val prefix: RepPrefix = RepPrefix.NONE,
) {
    val size: Int get() = INSTRUCTION_SLOT

    companion object {
        const val INSTRUCTION_SLOT = 4
    }
}

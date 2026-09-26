package x86sim.analysis

import x86sim.asm.Assembler
import x86sim.asm.Program
import x86sim.cpu.ImmOp
import x86sim.cpu.Instruction
import x86sim.cpu.RegOp
import x86sim.cpu.Registers

enum class EdgeKind {
    /** Conditional branch, condition true. */
    TAKEN,
    /** Conditional branch, condition false — execution continues with the next block. */
    NOT_TAKEN,
    /** Unconditional: a `jmp`, or simply running off the end of a block into the next one. */
    ALWAYS,
}

data class Edge(val from: Int, val to: Int, val kind: EdgeKind)

/** A straight run of instructions with one way in (the top) and one way out (the bottom). */
class BasicBlock(val id: Int, val instructions: List<Instruction>, val name: String?) {
    val start: Long get() = instructions.first().address
    val last: Instruction get() = instructions.last()
    /** Why control leaves the block without a successor: "ret", "exit", "hlt", "indirect jmp"; null otherwise. */
    var exit: String? = null
        internal set
    /** Functions this block calls (by name), in order. */
    val calls = mutableListOf<String>()
}

class FunctionGraph(val name: String, val entry: Long, val blocks: List<BasicBlock>, val edges: List<Edge>)

/**
 * Splits a program into basic blocks and connects them with control-flow edges.
 *
 * A new block starts at the program entry, at every jump or call target, and after
 * every instruction that transfers control (jumps, `ret`, `hlt`, and `syscall` when
 * `rax` is known to hold exit/exit_group). Calls stay inside their block — they return —
 * and are recorded so each function can be shown as its own graph.
 */
class ControlFlowGraph(val program: Program) {
    val blocks: List<BasicBlock>
    val edges: List<Edge>
    val functions: List<FunctionGraph>
    private val blockByAddress: Map<Long, BasicBlock>

    init {
        val ins = program.instructions
        val index = ins.withIndex().associate { it.value.address to it.index }

        // 1. Leaders: entry, call targets and jump targets.
        val jumpTargets = mutableSetOf<Long>()
        val callTargets = mutableSetOf<Long>()
        for (i in ins) {
            val t = (i.operands.firstOrNull() as? ImmOp)?.value ?: continue
            if (t !in index) continue
            when {
                i.mnemonic == "call" -> callTargets += t
                isJump(i.mnemonic) -> jumpTargets += t
            }
        }
        val leaders = sortedSetOf(program.entry).apply { addAll(jumpTargets); addAll(callTargets) }
        if (ins.isNotEmpty()) leaders += ins.first().address

        // 2. Block ends: after control transfers. Track constant rax to recognise exit syscalls.
        val exitSyscalls = mutableSetOf<Long>()
        var rax: Long? = null
        for ((k, i) in ins.withIndex()) {
            if (i.address in leaders) rax = null
            when {
                i.mnemonic == "syscall" -> {
                    if (rax == 60L || rax == 231L) exitSyscalls += i.address
                    rax = null
                }
                i.mnemonic == "call" || i.mnemonic in IMPLICIT_RAX_WRITERS -> rax = null
                i.mnemonic == "mov" && writesRax(i) -> rax = (i.operands[1] as? ImmOp)?.value
                i.mnemonic == "xor" && writesRax(i) && (i.operands[1] as? RegOp)?.reg?.num == Registers.RAX -> rax = 0
                writesRax(i) -> rax = null
            }
            val ends = isJump(i.mnemonic) || i.mnemonic == "ret" || i.mnemonic == "hlt" || i.address in exitSyscalls
            if (ends && k + 1 < ins.size) leaders += ins[k + 1].address
        }

        // 3. Build blocks.
        val list = mutableListOf<BasicBlock>()
        var cur = mutableListOf<Instruction>()
        fun flush() {
            if (cur.isEmpty()) return
            val name = program.symbols.entries.firstOrNull { it.value == cur.first().address }?.key
            list += BasicBlock(list.size, cur, name)
            cur = mutableListOf()
        }
        for (i in ins) {
            if (i.address in leaders) flush()
            cur += i
        }
        flush()
        blocks = list
        blockByAddress = list.associateBy { it.start }

        // 4. Edges.
        val e = mutableListOf<Edge>()
        for ((k, b) in list.withIndex()) {
            val last = b.last
            val next = list.getOrNull(k + 1)
            for (i in b.instructions) if (i.mnemonic == "call") {
                val t = (i.operands[0] as? ImmOp)?.value
                b.calls += t?.let { program.describeCodeAddress(it) } ?: "(indirect)"
            }
            val target = (last.operands.firstOrNull() as? ImmOp)?.value?.let { blockByAddress[it] }
            when {
                last.mnemonic == "jmp" -> if (target != null) e += Edge(b.id, target.id, EdgeKind.ALWAYS) else b.exit = "indirect jmp"
                isJump(last.mnemonic) -> {
                    if (target != null) e += Edge(b.id, target.id, EdgeKind.TAKEN)
                    if (next != null) e += Edge(b.id, next.id, EdgeKind.NOT_TAKEN)
                }
                last.mnemonic == "ret" -> b.exit = "ret"
                last.mnemonic == "hlt" -> b.exit = "hlt"
                last.address in exitSyscalls -> b.exit = "exit"
                next != null -> e += Edge(b.id, next.id, EdgeKind.ALWAYS)
                else -> b.exit = "end of code"
            }
        }
        edges = e

        // 5. Functions: the entry point and every call target, each with the blocks it reaches.
        val entries = (listOf(program.entry) + callTargets.sorted()).distinct()
        val succ = e.groupBy { it.from }
        val covered = mutableSetOf<Int>()
        val fns = entries.mapNotNull { addr ->
            val start = blockByAddress[addr] ?: return@mapNotNull null
            val seen = linkedSetOf<Int>()
            val stack = ArrayDeque(listOf(start.id))
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (!seen.add(id)) continue
                succ[id]?.forEach { stack += it.to }
            }
            covered += seen
            val bs = seen.map { list[it] }.sortedBy { it.start }
            FunctionGraph(program.describeCodeAddress(addr) ?: "0x%x".format(addr), addr, bs,
                e.filter { it.from in seen && it.to in seen })
        }.toMutableList()
        val orphans = list.filter { it.id !in covered }
        if (orphans.isNotEmpty()) {
            val ids = orphans.map { it.id }.toSet()
            fns += FunctionGraph("(unreachable code)", orphans.first().start, orphans, e.filter { it.from in ids && it.to in ids })
        }
        functions = fns
    }

    fun blockContaining(addr: Long): BasicBlock? =
        blocks.lastOrNull { it.start <= addr }?.takeIf { b -> b.instructions.any { it.address == addr } }

    fun functionContaining(addr: Long): FunctionGraph? {
        val b = blockContaining(addr) ?: return null
        return functions.firstOrNull { f -> f.blocks.any { it.id == b.id } }
    }

    companion object {
        /** Instructions that change rax without naming it as their destination operand. */
        private val IMPLICIT_RAX_WRITERS = setOf("mul", "imul", "div", "idiv", "cbw", "cwde", "cdqe", "xchg", "pop")

        fun isJump(m: String) = m == "jmp" || m in Assembler.LOOPS ||
            (m.startsWith("j") && m.substring(1) in Assembler.CONDITIONS)

        private fun writesRax(i: Instruction): Boolean {
            val d = i.operands.firstOrNull() as? RegOp ?: return false
            return d.reg.num == Registers.RAX && i.mnemonic !in setOf("cmp", "test", "push")
        }
    }
}

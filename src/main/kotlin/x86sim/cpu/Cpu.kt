package x86sim.cpu

import java.math.BigInteger
import x86sim.cpu.Registers.RAX
import x86sim.cpu.Registers.RCX
import x86sim.cpu.Registers.RDX
import x86sim.cpu.Registers.RSP
import x86sim.cpu.Registers.RBP
import x86sim.cpu.Registers.RDI
import x86sim.cpu.Registers.RSI

sealed interface StepResult {
    data object Ok : StepResult
    /** The instruction could not complete yet (e.g. `read` is waiting for keyboard input). */
    data object Blocked : StepResult
    data class Exit(val code: Int) : StepResult
    data class Halt(val reason: String) : StepResult
}

fun interface SyscallHandler {
    fun syscall(cpu: Cpu): StepResult
}

class Cpu(val memory: Memory) {
    val regs = LongArray(16)
    var rip = 0L

    var cf = false; var pf = false; var af = false
    var zf = false; var sf = false; var of = false; var df = false

    /** True right after a step that ran one iteration of a rep-prefixed instruction without finishing it. */
    var repeating = false

    /** Address of the last instruction that completed a step, or -1 before the first one. */
    var lastRip = -1L

    var code: Map<Long, Instruction> = emptyMap()
    var syscallHandler: SyscallHandler = SyscallHandler { StepResult.Halt("syscall not supported") }

    fun reset() {
        regs.fill(0); rip = 0; repeating = false; lastRip = -1
        cf = false; pf = false; af = false; zf = false; sf = false; of = false; df = false
    }

    val rflags: Long
        get() {
            var f = 0x2L // bit 1 is always set
            if (cf) f = f or 0x1; if (pf) f = f or 0x4; if (af) f = f or 0x10
            if (zf) f = f or 0x40; if (sf) f = f or 0x80; if (df) f = f or 0x400
            if (of) f = f or 0x800
            return f
        }

    /** Restores the flags from an RFLAGS value (the inverse of [rflags]). */
    fun setFlags(f: Long) {
        cf = f and 0x1 != 0L; pf = f and 0x4 != 0L; af = f and 0x10 != 0L
        zf = f and 0x40 != 0L; sf = f and 0x80 != 0L; df = f and 0x400 != 0L; of = f and 0x800 != 0L
    }

    fun currentInstruction(): Instruction? = code[rip]

    // ---- registers -------------------------------------------------------------------

    fun get(r: Reg): Long {
        val full = regs[r.num]
        return when {
            r.high -> (full ushr 8) and 0xFF
            else -> Bits.trunc(full, r.size)
        }
    }

    fun set(r: Reg, value: Long) {
        val old = regs[r.num]
        regs[r.num] = when {
            r.high -> (old and 0xFF00L.inv()) or ((value and 0xFF) shl 8)
            r.size == 8 -> value
            r.size == 4 -> value and 0xFFFFFFFFL // 32-bit writes zero the upper half
            else -> (old and Bits.mask(r.size).inv()) or Bits.trunc(value, r.size)
        }
    }

    private fun setPart(num: Int, size: Int, value: Long) =
        set(Reg("", num, size), value)

    private fun getPart(num: Int, size: Int) = get(Reg("", num, size))

    // ---- operands --------------------------------------------------------------------

    fun effectiveAddress(m: MemOp): Long {
        var a = m.disp
        if (m.base != null) a += get(m.base)
        if (m.index != null) a += get(m.index) * m.scale
        return a
    }

    private fun sizeOf(op: Operand): Int = when (op) {
        is RegOp -> op.reg.size
        is MemOp -> op.size
        is ImmOp -> 0
    }

    private fun read(op: Operand, size: Int): Long = when (op) {
        is RegOp -> get(op.reg)
        is ImmOp -> Bits.trunc(op.value, size)
        is MemOp -> memory.read(effectiveAddress(op), size)
    }

    private fun write(op: Operand, size: Int, value: Long) {
        when (op) {
            is RegOp -> set(op.reg, value)
            is MemOp -> memory.write(effectiveAddress(op), size, value)
            is ImmOp -> throw CpuFault("cannot write to an immediate")
        }
    }

    fun push(value: Long) {
        regs[RSP] -= 8
        memory.write(regs[RSP], 8, value)
    }

    fun pop(): Long {
        val v = memory.read(regs[RSP], 8)
        regs[RSP] += 8
        return v
    }

    // ---- flags -----------------------------------------------------------------------

    private fun setSZP(res: Long, size: Int) {
        zf = Bits.trunc(res, size) == 0L
        sf = Bits.msb(res, size)
        pf = Bits.parity(res)
    }

    private fun bitAt(v: Long, bit: Int) = (v ushr bit) and 1L == 1L

    private fun add(a: Long, b: Long, carry: Long, size: Int, setCarry: Boolean = true): Long {
        val res = Bits.trunc(a + b + carry, size)
        val top = size * 8 - 1
        if (setCarry) cf = bitAt((a and b) or ((a or b) and res.inv()), top)
        of = bitAt((a xor res) and (b xor res), top)
        af = (a xor b xor res) and 0x10 != 0L
        setSZP(res, size)
        return res
    }

    private fun sub(a: Long, b: Long, borrow: Long, size: Int, setCarry: Boolean = true): Long {
        val res = Bits.trunc(a - b - borrow, size)
        val top = size * 8 - 1
        if (setCarry) cf = bitAt((a.inv() and b) or ((a.inv() or b) and res), top)
        of = bitAt((a xor b) and (a xor res), top)
        af = (a xor b xor res) and 0x10 != 0L
        setSZP(res, size)
        return res
    }

    private fun logic(res: Long, size: Int): Long {
        val r = Bits.trunc(res, size)
        cf = false; of = false; af = false
        setSZP(r, size)
        return r
    }

    fun condition(cc: String): Boolean? = when (cc) {
        "o" -> of; "no" -> !of
        "b", "c", "nae" -> cf; "ae", "nb", "nc" -> !cf
        "e", "z" -> zf; "ne", "nz" -> !zf
        "be", "na" -> cf || zf; "a", "nbe" -> !cf && !zf
        "s" -> sf; "ns" -> !sf
        "p", "pe" -> pf; "np", "po" -> !pf
        "l", "nge" -> sf != of; "ge", "nl" -> sf == of
        "le", "ng" -> zf || sf != of; "g", "nle" -> !zf && sf == of
        else -> null
    }

    // ---- execution -------------------------------------------------------------------

    fun step(): StepResult {
        val ins = code[rip] ?: throw FetchFault(rip)
        val r = execute(ins)
        if (r != StepResult.Blocked) lastRip = ins.address
        return r
    }

    private fun execute(ins: Instruction): StepResult {
        repeating = false
        val next = rip + ins.size
        val ops = ins.operands
        var target: Long? = null
        val m = ins.mnemonic
        StringOp.of(m)?.let { return stringStep(ins, it, next) }

        fun dst() = ops[0]
        fun src() = ops[1]
        val size = if (ops.isNotEmpty()) sizeOf(ops[0]).let { if (it == 0 && ops.size > 1) sizeOf(ops[1]) else it } else 8

        when (m) {
            "nop" -> {}
            "hlt" -> return StepResult.Halt("hlt instruction")
            "mov" -> write(dst(), size, read(src(), size))
            "movzx" -> write(dst(), size, read(src(), sizeOf(src())))
            "movsx", "movsxd" -> {
                val s = sizeOf(src())
                write(dst(), size, Bits.signExtend(read(src(), s), s))
            }
            "lea" -> write(dst(), size, effectiveAddress(src() as MemOp))
            "xchg" -> {
                val a = read(dst(), size); val b = read(src(), size)
                write(dst(), size, b); write(src(), size, a)
            }
            "add" -> write(dst(), size, add(read(dst(), size), read(src(), size), 0, size))
            "adc" -> write(dst(), size, add(read(dst(), size), read(src(), size), if (cf) 1 else 0, size))
            "sub" -> write(dst(), size, sub(read(dst(), size), read(src(), size), 0, size))
            "sbb" -> write(dst(), size, sub(read(dst(), size), read(src(), size), if (cf) 1 else 0, size))
            "cmp" -> sub(read(dst(), size), read(src(), size), 0, size)
            "inc" -> write(dst(), size, add(read(dst(), size), 1, 0, size, setCarry = false))
            "dec" -> write(dst(), size, sub(read(dst(), size), 1, 0, size, setCarry = false))
            "neg" -> {
                val v = read(dst(), size)
                write(dst(), size, sub(0, v, 0, size))
                cf = v != 0L
            }
            "and" -> write(dst(), size, logic(read(dst(), size) and read(src(), size), size))
            "or" -> write(dst(), size, logic(read(dst(), size) or read(src(), size), size))
            "xor" -> write(dst(), size, logic(read(dst(), size) xor read(src(), size), size))
            "test" -> logic(read(dst(), size) and read(src(), size), size)
            "not" -> write(dst(), size, Bits.trunc(read(dst(), size).inv(), size))
            "shl", "sal", "shr", "sar", "rol", "ror" ->
                write(dst(), size, shift(m, read(dst(), size), if (ops.size > 1) read(src(), 1) else 1, size))
            "mul" -> mul(read(dst(), size), size)
            "imul" -> imul(ops, size)
            "div" -> div(read(dst(), size), size, signed = false)
            "idiv" -> div(read(dst(), size), size, signed = true)
            "cbw" -> setPart(RAX, 2, Bits.signExtend(getPart(RAX, 1), 1))
            "cwde" -> setPart(RAX, 4, Bits.signExtend(getPart(RAX, 2), 2))
            "cdqe" -> regs[RAX] = Bits.signExtend(regs[RAX], 4)
            "cwd" -> setPart(RDX, 2, if (Bits.msb(regs[RAX], 2)) -1 else 0)
            "cdq" -> setPart(RDX, 4, if (Bits.msb(regs[RAX], 4)) -1 else 0)
            "cqo" -> regs[RDX] = if (regs[RAX] < 0) -1 else 0
            "push" -> push(if (dst() is ImmOp) (dst() as ImmOp).value else read(dst(), 8))
            "pop" -> { val v = pop(); write(dst(), 8, v) }
            "call" -> {
                target = jumpTarget(dst())
                push(next)
            }
            "ret" -> {
                target = pop()
                if (ops.isNotEmpty()) regs[RSP] += (ops[0] as ImmOp).value
            }
            "leave" -> { regs[RSP] = regs[RBP]; regs[RBP] = pop() }
            "jmp" -> target = jumpTarget(dst())
            "loop" -> { regs[RCX]--; if (regs[RCX] != 0L) target = jumpTarget(dst()) }
            "jrcxz" -> if (regs[RCX] == 0L) target = jumpTarget(dst())
            "jecxz" -> if (getPart(RCX, 4) == 0L) target = jumpTarget(dst())
            "clc" -> cf = false
            "stc" -> cf = true
            "cmc" -> cf = !cf
            "cld" -> df = false
            "std" -> df = true
            "syscall" -> {
                val r = syscallHandler.syscall(this)
                if (r == StepResult.Blocked) return r // retry the same instruction later
                regs[RCX] = next          // the CPU saves the return address in rcx
                regs[11] = rflags         // ...and the flags in r11
                if (r != StepResult.Ok) { rip = next; return r }
            }
            else -> when {
                m.startsWith("j") -> if (cond(m.substring(1))) target = jumpTarget(dst())
                m.startsWith("set") -> write(dst(), 1, if (cond(m.substring(3))) 1 else 0)
                m.startsWith("cmov") -> {
                    val v = read(src(), size)
                    if (cond(m.substring(4))) write(dst(), size, v)
                    else if (size == 4 && dst() is RegOp) write(dst(), 4, get((dst() as RegOp).reg))
                }
                else -> throw CpuFault("Unsupported instruction '$m'")
            }
        }
        rip = target ?: next
        return StepResult.Ok
    }

    /**
     * One iteration of a string instruction. With a repeat prefix, rip stays on the instruction
     * until the repeat ends, so every iteration is its own step (as when single-stepping real hardware).
     */
    private fun stringStep(ins: Instruction, op: StringOp, next: Long): StepResult {
        val repeat = ins.prefix != RepPrefix.NONE
        if (repeat && regs[RCX] == 0L) { rip = next; return StepResult.Ok }
        val n = op.size
        val d = if (df) -n.toLong() else n.toLong()
        // All memory accesses come before any register update, so a fault leaves the
        // registers as they were after the last completed iteration.
        when (op.family) {
            StringOp.Family.MOVS -> {
                memory.write(regs[RDI], n, memory.read(regs[RSI], n))
                regs[RSI] += d; regs[RDI] += d
            }
            StringOp.Family.STOS -> {
                memory.write(regs[RDI], n, getPart(RAX, n))
                regs[RDI] += d
            }
            StringOp.Family.LODS -> {
                setPart(RAX, n, memory.read(regs[RSI], n))
                regs[RSI] += d
            }
            StringOp.Family.SCAS -> {
                sub(getPart(RAX, n), memory.read(regs[RDI], n), 0, n)
                regs[RDI] += d
            }
            StringOp.Family.CMPS -> {
                val a = memory.read(regs[RSI], n)
                sub(a, memory.read(regs[RDI], n), 0, n)
                regs[RSI] += d; regs[RDI] += d
            }
        }
        if (!repeat) { rip = next; return StepResult.Ok }
        regs[RCX] -= 1
        val done = regs[RCX] == 0L || op.stopsAfter(ins.prefix, zf)
        rip = if (done) next else ins.address
        repeating = !done
        return StepResult.Ok
    }

    private fun cond(cc: String) = condition(cc) ?: throw CpuFault("Unknown condition code '$cc'")

    private fun jumpTarget(op: Operand): Long = when (op) {
        is ImmOp -> op.value
        else -> read(op, 8)
    }

    private fun shift(m: String, a: Long, rawCount: Long, size: Int): Long {
        val bits = size * 8
        val count = (rawCount and if (size == 8) 63L else 31L).toInt()
        if (count == 0) return a
        val res: Long
        when (m) {
            "shl", "sal" -> {
                res = Bits.trunc(a shl count, size)
                cf = if (count <= bits) bitAt(a shl (count - 1), bits - 1) else false
                of = Bits.msb(res, size) != cf
            }
            "shr" -> {
                res = if (count >= 64) 0 else a ushr count
                cf = bitAt(a, count - 1)
                of = Bits.msb(a, size)
            }
            "sar" -> {
                val s = Bits.signExtend(a, size)
                res = Bits.trunc(s shr minOf(count, 63), size)
                cf = bitAt(s, minOf(count - 1, 63))
                of = false
            }
            else -> {
                val c = count % bits
                res = if (m == "rol")
                    Bits.trunc((a shl c) or (a ushr (bits - c)), size)
                else
                    Bits.trunc((a ushr c) or (a shl (bits - c)), size)
                if (m == "rol") {
                    cf = bitAt(res, 0); of = Bits.msb(res, size) != cf
                } else {
                    cf = Bits.msb(res, size); of = cf != bitAt(res, bits - 2)
                }
                return res // rotates don't touch SF/ZF/PF
            }
        }
        setSZP(res, size)
        return res
    }

    /** Writes a double-width result into AX, DX:AX, EDX:EAX or RDX:RAX. */
    private fun writeWide(low: Long, high: Long, size: Int) {
        if (size == 1) setPart(RAX, 2, (low and 0xFF) or ((high and 0xFF) shl 8))
        else { setPart(RAX, size, low); setPart(RDX, size, high) }
    }

    private fun mul(b: Long, size: Int) {
        val a = getPart(RAX, size)
        val low: Long; val high: Long
        if (size == 8) { low = a * b; high = Math.unsignedMultiplyHigh(a, b) }
        else { val p = a * b; low = Bits.trunc(p, size); high = Bits.trunc(p ushr (size * 8), size) }
        writeWide(low, high, size)
        cf = high != 0L; of = cf
    }

    private fun imul(ops: List<Operand>, size: Int) {
        val a: Long; val b: Long
        when (ops.size) {
            1 -> { a = getPart(RAX, size); b = read(ops[0], size) }
            2 -> { a = read(ops[0], size); b = read(ops[1], size) }
            else -> { a = read(ops[1], size); b = read(ops[2], size) }
        }
        val sa = Bits.signExtend(a, size); val sb = Bits.signExtend(b, size)
        val low: Long; val high: Long; val overflow: Boolean
        if (size == 8) {
            low = sa * sb; high = Math.multiplyHigh(sa, sb)
            overflow = high != (low shr 63)
        } else {
            val p = sa * sb
            low = Bits.trunc(p, size); high = Bits.trunc(p shr (size * 8), size)
            overflow = p != Bits.signExtend(low, size)
        }
        if (ops.size == 1) writeWide(low, high, size) else write(ops[0], size, low)
        cf = overflow; of = overflow
    }

    private fun div(divisorRaw: Long, size: Int, signed: Boolean) {
        if (divisorRaw == 0L) throw CpuFault("Divide error (#DE): division by zero")
        val bits = size * 8
        val low = if (size == 1) getPart(RAX, 1) else getPart(RAX, size)
        val high = if (size == 1) (regs[RAX] ushr 8) and 0xFF else getPart(RDX, size)
        fun big(v: Long) = BigInteger.valueOf(v).and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE))
        var dividend = big(high).shiftLeft(bits).or(big(low))
        var divisor = big(divisorRaw)
        if (signed) {
            if (dividend.testBit(bits * 2 - 1)) dividend = dividend.subtract(BigInteger.ONE.shiftLeft(bits * 2))
            if (divisor.testBit(bits - 1)) divisor = divisor.subtract(BigInteger.ONE.shiftLeft(bits))
        }
        val (q, r) = dividend.divideAndRemainder(divisor)
        val fits = if (signed) q.bitLength() < bits else q.signum() >= 0 && q.bitLength() <= bits
        if (!fits) throw CpuFault("Divide error (#DE): quotient does not fit in ${Bits.sizeName(size)}")
        if (size == 1) setPart(RAX, 2, (q.toLong() and 0xFF) or ((r.toLong() and 0xFF) shl 8))
        else { setPart(RAX, size, q.toLong()); setPart(RDX, size, r.toLong()) }
    }
}

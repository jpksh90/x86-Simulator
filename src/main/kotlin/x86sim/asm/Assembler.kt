package x86sim.asm

import x86sim.cpu.Bits
import x86sim.cpu.ImmOp
import x86sim.cpu.Instruction
import x86sim.cpu.MemOp
import x86sim.cpu.Operand
import x86sim.cpu.Reg
import x86sim.cpu.RegOp
import x86sim.cpu.Registers
import x86sim.cpu.RepPrefix
import x86sim.cpu.StringOp

data class AsmError(val line: Int, val message: String, val warning: Boolean = false) {
    override fun toString() = "line ${line + 1}: $message"
}

class AssemblyException(val errors: List<AsmError>) :
    Exception(errors.joinToString("\n"))

class Section(val name: String, val start: Long, val writable: Boolean, val bss: Boolean) {
    val data = java.io.ByteArrayOutputStream()
    var size = 0L // for .bss (no bytes stored) and .text (instruction slots)
    val length: Long get() = if (bss) size else data.size().toLong()
}

class Program(
    val instructions: List<Instruction>,
    val symbols: Map<String, Long>,
    val sections: List<Section>,
    val entry: Long,
    /** Source line (0-based) where each label is defined. */
    val labelLines: Map<String, Int> = emptyMap(),
) {
    val byAddress: Map<Long, Instruction> = instructions.associateBy { it.address }
    val byLine: Map<Int, Instruction> = instructions.associateBy { it.line }

    /** Name of the closest label at or before [addr] in .text, like `loop+8`. */
    fun describeCodeAddress(addr: Long): String? {
        if (addr !in byAddress) return null
        val best = symbols.filter { it.value <= addr && it.value in byAddress }
            .maxByOrNull { it.value } ?: return null
        val off = addr - best.value
        return if (off == 0L) best.key else "${best.key}+$off"
    }

    fun describeDataAddress(addr: Long): String? {
        val best = symbols.filter { it.value <= addr && it.value !in byAddress && it.value >= DATA_BASE }
            .maxByOrNull { it.value } ?: return null
        val off = addr - best.value
        return if (off == 0L) best.key else "${best.key}+$off"
    }

    companion object {
        const val TEXT_BASE = 0x401000L
        const val RODATA_BASE = 0x500000L
        const val DATA_BASE = 0x600000L
        const val BSS_BASE = 0x700000L
    }
}

/**
 * A two-pass assembler for a practical subset of NASM-syntax x86-64 assembly.
 *
 * Pass 1 walks every line, assigns addresses to labels and computes section sizes.
 * Pass 2 evaluates operands (now that every label is known), checks them for the
 * mistakes a real assembler would reject, and emits instructions and data bytes.
 */
class Assembler {

    private class Pending(val line: Int, val section: Section, val address: Long, val op: String, val args: String, val times: Long, val lastLabel: String?, val prefix: RepPrefix = RepPrefix.NONE)

    private val errors = mutableListOf<AsmError>()
    private val labelLines = mutableMapOf<String, Int>()
    private val symbols = LinkedHashMap<String, Long>()

    private val text = Section(".text", Program.TEXT_BASE, writable = false, bss = false)
    private val rodata = Section(".rodata", Program.RODATA_BASE, writable = false, bss = false)
    private val data = Section(".data", Program.DATA_BASE, writable = true, bss = false)
    private val bss = Section(".bss", Program.BSS_BASE, writable = true, bss = true)

    fun assemble(source: String): Program {
        val lines = source.lines()
        val pending = mutableListOf<Pending>()
        var section = text
        var lastLabel: String? = null

        // ---------- pass 1: labels and layout ----------
        for ((ln, rawLine) in lines.withIndex()) {
            try {
                var line = stripComment(rawLine).trim()
                if (line.isEmpty()) continue

                // "label:" prefix (possibly followed by a statement on the same line)
                val colon = labelColon(line)
                if (colon > 0) {
                    val name = qualify(line.substring(0, colon).trim(), lastLabel)
                    val after = splitFirst(line.substring(colon + 1))
                    if (after.first.lowercase() == "equ") {
                        symbols[name] = evalConst(after.second, section.start + section.length, section)
                        continue
                    }
                    defineLabel(name, section, ln)
                    if (!line.substring(0, colon).trim().startsWith(".")) lastLabel = name
                    line = line.substring(colon + 1).trim()
                    if (line.isEmpty()) continue
                }

                var (word, rest) = splitFirst(line)
                var lw = word.lowercase()

                // Repeat prefixes ("rep movsb") and the string-instruction forms we don't support.
                var prefix = RepPrefix.NONE
                val next = splitFirst(rest).first.lowercase()
                if (next !in DATA_DIRECTIVES && next != "equ" && next != "times") when {
                    lw == "a32" -> throw AsmFail("the 32-bit address-size override ('a32') isn't supported; string instructions use rcx, rsi and rdi")
                    lw in SEGMENT_REGS && (next in PREFIXES || next in StringOp.BARE || StringOp.of(next) != null) ->
                        throw AsmFail("segment overrides ('$lw') aren't supported")
                    lw in PORT_IO || (lw in PREFIXES && next in PORT_IO) ->
                        throw AsmFail("port I/O instructions ('insb', 'outsb', …) are privileged and not supported")
                    lw in StringOp.BARE ->
                        throw AsmFail("write the size in the name: '${lw}b', '${lw}w', '${lw}d' or '${lw}q' (NASM doesn't accept operands here)")
                    lw in PREFIXES -> {
                        val (w2, r2) = splitFirst(rest)
                        if (w2.isEmpty()) throw AsmFail("'$word' needs a string instruction after it, e.g. '$lw movsb'")
                        if (StringOp.of(w2) == null)
                            throw AsmFail("'$lw' only works with string instructions (movs, stos, lods, scas, cmps), not '$w2'")
                        prefix = RepPrefix.parse(lw)!!
                        word = w2; rest = r2; lw = w2.lowercase()
                    }
                }

                // NASM also allows a label without a colon before data directives: "msg db 'hi'"
                if (lw !in DIRECTIVES && lw !in MNEMONICS && !isCondMnemonic(lw)) {
                    val (w2, r2) = splitFirst(rest)
                    if (w2.lowercase() in DATA_DIRECTIVES || w2.lowercase() == "equ" || w2.lowercase() == "times") {
                        val name = qualify(word, lastLabel)
                        if (w2.lowercase() == "equ") {
                            symbols[name] = evalConst(r2, section.start + section.length, section)
                            continue
                        }
                        defineLabel(name, section, ln)
                        if (!word.startsWith(".")) lastLabel = name
                        word = w2; rest = r2; lw = w2.lowercase()
                    }
                }

                if (lw in MNEMONICS || isCondMnemonic(lw)) {
                    val w2 = splitFirst(rest).first.lowercase()
                    if (w2 in DATA_DIRECTIVES || w2 == "equ")
                        throw AsmFail("'$word' is an instruction name and can't be used as a label")
                }

                var times = 1L
                if (lw == "times") {
                    val (countExpr, stmt) = splitTimes(rest)
                    times = evalConst(countExpr, section.start + section.length, section)
                    if (times < 0) throw AsmFail("'times' count can't be negative")
                    val (w, r) = splitFirst(stmt)
                    word = w; rest = r; lw = w.lowercase()
                }

                when (lw) {
                    "section", "segment" -> section = when (val s = rest.trim().split(Regex("\\s+"))[0].lowercase()) {
                        ".text", "text", ".code" -> text
                        ".data", "data" -> data
                        ".rodata", "rodata", ".rdata" -> rodata
                        ".bss", "bss" -> bss
                        else -> throw AsmFail("unknown section '$s' (use .text, .data, .rodata or .bss)")
                    }
                    "global", "bits", "default", "cpu" -> {}
                    "extern" -> throw AsmFail("'extern' not supported — use syscalls")
                    "align" -> {
                        val a = evalConst(rest, 0, section)
                        if (a <= 0 || a and (a - 1) != 0L) throw AsmFail("alignment must be a power of two")
                        if (section != text) {
                            val pad = ((a - section.length % a) % a).toInt()
                            if (section.bss) section.size += pad else repeat(pad) { section.data.write(0) }
                        }
                    }
                    in DATA_DIRECTIVES -> {
                        if (section == text) throw AsmFail("data in .text — use section .data")
                        val itemSize = DATA_DIRECTIVES.getValue(lw)
                        if (lw.startsWith("res")) {
                            val n = evalConst(rest, 0, section) * itemSize * times
                            if (!section.bss) repeat(n.toInt()) { section.data.write(0) } else section.size += n
                        } else {
                            if (section.bss) throw AsmFail("initialised data ('$lw') not allowed in .bss; use resb/resq")
                            val address = section.start + section.length
                            val len = dataLength(rest, itemSize) * times
                            repeat(len.toInt()) { section.data.write(0) } // placeholder, filled in pass 2
                            pending += Pending(ln, section, address, lw, rest, times, lastLabel)
                        }
                    }
                    "equ" -> throw AsmFail("'equ' needs a name before it, e.g. 'len equ \$ - msg'")
                    else -> {
                        if (lw !in MNEMONICS && !isCondMnemonic(lw))
                            throw AsmFail("unknown instruction '$word'")
                        if (section != text) throw AsmFail("instruction '$lw' outside section .text")
                        if (times != 1L) throw AsmFail("'times' with instructions isn't supported")
                        val address = text.start + text.size
                        text.size += Instruction.INSTRUCTION_SLOT
                        pending += Pending(ln, text, address, lw, rest, 1, lastLabel, prefix)
                    }
                }
            } catch (e: AsmFail) {
                errors += AsmError(ln, e.message!!)
            } catch (e: ExprException) {
                errors += AsmError(ln, e.message!!)
            }
        }

        // ---------- pass 2: operands and data ----------
        val instructions = mutableListOf<Instruction>()
        for (p in pending) {
            try {
                if (p.section == text) {
                    if (StringOp.of(p.op) != null && p.args.isNotBlank()) throw stringOperands(p.op)
                    val ops = splitOperands(p.args).map { parseOperand(it, p) }
                    instructions += Instruction(p.op, check(p.op, ops), p.address, p.line, lines[p.line].trim(), p.prefix)
                } else {
                    val bytes = encodeData(p)
                    val off = (p.address - p.section.start).toInt()
                    val arr = p.section.data.toByteArray()
                    bytes.copyInto(arr, off)
                    p.section.data.reset(); p.section.data.write(arr)
                }
            } catch (e: AsmFail) {
                errors += AsmError(p.line, e.message!!)
            } catch (e: ExprException) {
                errors += AsmError(p.line, e.message!!)
            }
        }

        if (errors.isEmpty() && instructions.isEmpty())
            errors += AsmError(0, "no instructions in section .text")
        if (errors.isNotEmpty()) throw AssemblyException(errors.sortedBy { it.line })

        val entry = symbols["_start"] ?: symbols["main"] ?: instructions.first().address
        return Program(instructions, symbols, listOf(text, rodata, data, bss), entry, labelLines)
    }

    // ---------------- helpers: lexical ----------------

    private class AsmFail(message: String) : Exception(message)

    private fun stripComment(line: String): String {
        val i = commentStart(line)
        return if (i < 0) line else line.substring(0, i)
    }

    /** Index of the colon ending a leading label, or -1. */
    private fun labelColon(line: String): Int {
        val m = LABEL_RE.find(line) ?: return -1
        return m.range.last
    }

    private fun qualify(name: String, lastLabel: String?): String {
        if (name.startsWith(".") && name != "..") {
            return (lastLabel ?: throw AsmFail("local label '$name' has no preceding global label")) + name
        }
        return name
    }

    private fun defineLabel(name: String, section: Section, ln: Int) {
        if (Registers.isRegister(name)) throw AsmFail("'$name' is a register name and can't be a label")
        if (name.lowercase() in MNEMONICS || name.lowercase() in PREFIXES) throw AsmFail("'$name' is an instruction name and can't be a label")
        if (name in symbols) throw AsmFail("label '$name' is defined more than once")
        labelLines[name] = ln
        symbols[name] = section.start + if (section == text) section.size else section.length
    }

    private fun splitFirst(s: String): Pair<String, String> {
        val t = s.trim()
        val i = t.indexOfFirst { it.isWhitespace() }
        return if (i < 0) t to "" else t.substring(0, i) to t.substring(i).trim()
    }

    /** Splits `N db 0` (after "times") into the count expression and the statement. */
    private fun splitTimes(rest: String): Pair<String, String> {
        val m = Regex("\\b(db|dw|dd|dq|resb|resw|resd|resq)\\b", RegexOption.IGNORE_CASE).find(rest)
            ?: throw AsmFail("'times' must be followed by a count and a data directive")
        return rest.substring(0, m.range.first).trim() to rest.substring(m.range.first)
    }

    /** Splits a comma-separated list, ignoring commas inside quotes, brackets and parentheses. */
    private fun splitOperands(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var depth = 0; var quote: Char? = null
        val cur = StringBuilder()
        for (c in s) {
            if (quote != null) { cur.append(c); if (c == quote) quote = null; continue }
            when (c) {
                '\'', '"', '`' -> { quote = c; cur.append(c) }
                '[', '(' -> { depth++; cur.append(c) }
                ']', ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { out += cur.toString().trim(); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        out += cur.toString().trim()
        if (out.any { it.isEmpty() }) throw AsmFail("empty operand (check your commas)")
        return out
    }

    private fun isString(item: String) = item.length >= 2 && item[0] in "'\"`" && item.last() == item[0]

    private fun dataLength(args: String, itemSize: Int): Long = splitOperands(args).sumOf { item ->
        if (isString(item)) {
            val n = ExprParser.parseStringLiteral(item, 0).first.size.toLong()
            if (itemSize == 1) n else ((n + itemSize - 1) / itemSize) * itemSize
        } else itemSize.toLong()
    }

    private fun resolver(lastLabel: String?): (String) -> Long? = { name ->
        symbols[name] ?: if (name.startsWith(".") && lastLabel != null) symbols[lastLabel + name] else null
    }

    private fun evalConst(expr: String, dollar: Long, section: Section, lastLabel: String? = null): Long {
        if (expr.isBlank()) throw AsmFail("missing value")
        return ExprParser(expr, resolver(lastLabel), dollar, section.start).parse().constant
    }

    private fun encodeData(p: Pending): ByteArray {
        val itemSize = DATA_DIRECTIVES.getValue(p.op)
        val out = java.io.ByteArrayOutputStream()
        repeat(p.times.toInt()) {
            for (item in splitOperands(p.args)) {
                if (isString(item)) {
                    val b = ExprParser.parseStringLiteral(item, 0).first
                    out.write(b)
                    val pad = if (itemSize == 1) 0 else ((itemSize - b.size % itemSize) % itemSize)
                    repeat(pad) { out.write(0) }
                } else {
                    val v = evalConst(item, p.address + out.size(), p.section, p.lastLabel)
                    for (i in 0 until itemSize) out.write((v ushr (8 * i)).toInt() and 0xFF)
                }
            }
        }
        return out.toByteArray()
    }

    // ---------------- helpers: operands ----------------

    private val sizeKeywords = mapOf("byte" to 1, "word" to 2, "dword" to 4, "qword" to 8)

    private fun parseOperand(raw: String, p: Pending): Operand {
        var s = raw.trim()
        var size = 0
        val first = s.split(Regex("[\\s\\[]"), limit = 2)[0].lowercase()
        if (first in sizeKeywords) {
            size = sizeKeywords.getValue(first)
            s = s.substring(first.length).trim()
            if (s.lowercase().startsWith("ptr")) s = s.substring(3).trim()
        }
        if (s.startsWith("[")) {
            if (!s.endsWith("]")) throw AsmFail("missing ']' in '$raw'")
            var inner = s.substring(1, s.length - 1).trim()
            if (inner.lowercase().startsWith("rel ")) inner = inner.substring(4)
            if (inner.lowercase().startsWith("abs ")) inner = inner.substring(4)
            val lin = ExprParser(inner, resolver(p.lastLabel), p.address, text.start, allowRegisters = true).parse()
            return memFromLinear(lin, size, raw)
        }
        if (size != 0) {
            // "dword 5" — a sized immediate; the size is only a hint here.
            return ImmOp(ExprParser(s, resolver(p.lastLabel), p.address, text.start).parse().constant)
        }
        Registers.lookup(s)?.let { return RegOp(it) }
        val lin = ExprParser(s, resolver(p.lastLabel), p.address, text.start).parse()
        return ImmOp(lin.constant)
    }

    private fun memFromLinear(lin: Linear, size: Int, raw: String): MemOp {
        var base: Reg? = null
        var index: Reg? = null
        var scale = 1
        for ((reg, k) in lin.regs) {
            if (reg.size != 8) throw AsmFail("addresses must use 64-bit registers (got '${reg.name}' in '$raw')")
            when {
                k == 1L && base == null -> base = reg
                k in listOf(1L, 2L, 4L, 8L) && index == null -> { index = reg; scale = k.toInt() }
                k in listOf(3L, 5L, 9L) && base == null && index == null -> { base = reg; index = reg; scale = (k - 1).toInt() }
                else -> throw AsmFail("invalid address '$raw': scale must be 1, 2, 4 or 8 and at most two registers")
            }
        }
        if (index?.num == Registers.RSP) {
            if (scale == 1 && base != null && base.num != Registers.RSP) { val t = base; base = index; index = t }
            else throw AsmFail("rsp can't be used as an index register")
        }
        if (lin.constant !in Int.MIN_VALUE..Int.MAX_VALUE && (base != null || index != null))
            throw AsmFail("displacement out of 32-bit range in '$raw'")
        return MemOp(base, index, scale, lin.constant, size)
    }

    // ---------------- helpers: validation ----------------

    private fun sizeOf(op: Operand) = when (op) { is RegOp -> op.reg.size; is MemOp -> op.size; is ImmOp -> 0 }

    private fun withSize(op: Operand, size: Int): Operand = if (op is MemOp && op.size == 0) op.copy(size = size) else op

    private fun need(cond: Boolean, msg: String) { if (!cond) throw AsmFail(msg) }

    private fun fitsIn(v: Long, size: Int): Boolean = when (size) {
        8 -> true
        else -> v in -(1L shl (size * 8 - 1)) until (1L shl (size * 8))
    }

    private fun sizeUnknown(m: String) = AsmFail(
        "operation size not specified — write e.g. '$m qword [rbx], 1'")

    /** Checks operand kinds/sizes and fills in memory-operand sizes implied by the other operand. */
    private fun check(m: String, ops: List<Operand>): List<Operand> {
        fun count(vararg n: Int) = need(ops.size in n,
            "'$m' takes ${n.joinToString(" or ")} operand${if (n.max() == 1) "" else "s"}, got ${ops.size}")
        fun notImmDst() = need(ops[0] !is ImmOp, "the destination of '$m' can't be an immediate value")
        fun noTwoMem() = need(!(ops[0] is MemOp && ops[1] is MemOp),
            "two memory operands not allowed — use a register")

        fun binary(): List<Operand> {
            count(2); notImmDst(); noTwoMem()
            val (a, b) = ops
            val sa = sizeOf(a); val sb = sizeOf(b)
            val size = when {
                sa != 0 && sb != 0 -> {
                    need(sa == sb, "operand size mismatch: '$a' is ${Bits.sizeName(sa)} but '$b' is ${Bits.sizeName(sb)}"); sa
                }
                sa != 0 -> sa
                sb != 0 && b !is ImmOp -> sb
                else -> throw sizeUnknown(m)
            }
            if (b is ImmOp) {
                val ok = if (size == 8 && m != "mov") b.value in Int.MIN_VALUE..Int.MAX_VALUE else fitsIn(b.value, size)
                need(ok, if (size == 8) "immediate too big (max 32-bit) — mov it to a register first" else "immediate ${b.value} doesn't fit in a ${Bits.sizeName(size)}")
            }
            return listOf(withSize(a, size), withSize(b, size))
        }

        fun unaryRm(): List<Operand> {
            count(1); notImmDst()
            if (sizeOf(ops[0]) == 0) throw sizeUnknown(m)
            return ops
        }

        fun target(): List<Operand> {
            count(1)
            val t = ops[0]
            if (t is RegOp) need(t.reg.size == 8, "jump/call targets in registers must be 64-bit")
            return listOf(withSize(t, 8))
        }

        return when {
            StringOp.of(m) != null -> { if (ops.isNotEmpty()) throw stringOperands(m); ops }
            m in ZERO_OPERAND -> { count(0); ops }
            m == "ret" -> { count(0, 1); if (ops.isNotEmpty()) need(ops[0] is ImmOp, "'ret' takes an immediate byte count"); ops }
            m in BINARY -> binary()
            m == "lea" -> {
                count(2)
                need(ops[0] is RegOp && (ops[0] as RegOp).reg.size >= 2, "'lea' needs a register destination (16/32/64-bit)")
                need(ops[1] is MemOp, "'lea' needs a memory operand like [rbx+8] as its source")
                ops
            }
            m == "xchg" -> {
                count(2); need(ops.none { it is ImmOp }, "'xchg' can't use an immediate"); binary()
            }
            m == "movzx" || m == "movsx" || m == "movsxd" -> {
                count(2)
                val d = ops[0]; val s = ops[1]
                need(d is RegOp, "'$m' needs a register destination")
                need(s !is ImmOp, "'$m' needs a register or memory source")
                if (sizeOf(s) == 0) throw AsmFail("'$m' needs an explicit source size, e.g. '$m ${d}, byte [rsi]'")
                if (m == "movsxd") need(sizeOf(s) == 4 && sizeOf(d) == 8, "'movsxd' extends a 32-bit source into a 64-bit register")
                else need(sizeOf(s) in 1..2 && sizeOf(s) < sizeOf(d), "'$m' extends an 8/16-bit source into a larger register")
                ops
            }
            m in UNARY -> unaryRm()
            m in SHIFTS -> {
                count(1, 2); notImmDst()
                if (sizeOf(ops[0]) == 0) throw sizeUnknown(m)
                if (ops.size == 2) need(ops[1] is ImmOp || (ops[1] as? RegOp)?.reg?.name == "cl",
                    "shift count must be an immediate or the 'cl' register")
                ops
            }
            m == "imul" -> {
                count(1, 2, 3)
                when (ops.size) {
                    1 -> unaryRm()
                    2 -> { need(ops[0] is RegOp, "two-operand 'imul' needs a register destination"); binary() }
                    else -> {
                        need(ops[0] is RegOp && ops[1] !is ImmOp && ops[2] is ImmOp, "three-operand form is 'imul reg, r/m, imm'")
                        val size = sizeOf(ops[0])
                        need(sizeOf(ops[1]) in listOf(0, size), "operand size mismatch in 'imul'")
                        listOf(ops[0], withSize(ops[1], size), ops[2])
                    }
                }
            }
            m == "push" -> {
                count(1)
                val o = ops[0]
                if (o is RegOp) need(o.reg.size == 8, "in 64-bit mode 'push' needs a 64-bit register (e.g. rax, not eax)")
                if (o is MemOp) need(o.size in listOf(0, 8), "'push' works on qword memory operands")
                if (o is ImmOp) need(o.value in Int.MIN_VALUE..Int.MAX_VALUE, "'push' immediates are limited to 32 bits (sign-extended)")
                listOf(withSize(o, 8))
            }
            m == "pop" -> {
                count(1); notImmDst()
                val o = ops[0]
                if (o is RegOp) need(o.reg.size == 8, "in 64-bit mode 'pop' needs a 64-bit register (e.g. rax, not eax)")
                if (o is MemOp) need(o.size in listOf(0, 8), "'pop' works on qword memory operands")
                listOf(withSize(o, 8))
            }
            m == "jmp" || m == "call" -> target()
            m in LOOPS || (m.startsWith("j") && m.substring(1) in CONDITIONS) -> {
                count(1); need(ops[0] is ImmOp, "'$m' needs a label as its target"); ops
            }
            m.startsWith("set") && m.substring(3) in CONDITIONS -> {
                count(1); notImmDst()
                need(sizeOf(ops[0]) in listOf(0, 1), "'$m' writes a single byte, e.g. '$m al'")
                listOf(withSize(ops[0], 1))
            }
            m.startsWith("cmov") && m.substring(4) in CONDITIONS -> {
                count(2)
                need(ops[0] is RegOp && sizeOf(ops[0]) >= 2, "'$m' needs a 16/32/64-bit register destination")
                need(ops[1] !is ImmOp, "'$m' can't take an immediate source")
                binary()
            }
            else -> throw AsmFail("unknown instruction '$m'")
        }
    }

    /** String instructions take no operands in NASM; `movsd`/`cmpsd` with operands are the SSE forms. */
    private fun stringOperands(m: String): AsmFail {
        if (m == "movsd" || m == "cmpsd") return AsmFail(
            "'$m' with operands is the SSE floating-point instruction, which isn't supported; the string instruction '$m' takes no operands")
        val op = StringOp.of(m)!!
        val acc = when (op.size) { 1 -> "al"; 2 -> "ax"; 4 -> "eax"; else -> "rax" }
        val implicit = when (op.family) {
            StringOp.Family.MOVS, StringOp.Family.CMPS -> "[rsi] and [rdi]"
            StringOp.Family.STOS, StringOp.Family.SCAS -> "$acc and [rdi]"
            StringOp.Family.LODS -> "[rsi] and $acc"
        }
        return AsmFail("'$m' takes no operands: it always uses $implicit")
    }

    private fun isCondMnemonic(m: String) =
        (m.startsWith("j") && m.substring(1) in CONDITIONS) ||
        (m.startsWith("set") && m.substring(3) in CONDITIONS) ||
        (m.startsWith("cmov") && m.substring(4) in CONDITIONS)

    companion object {
        val CONDITIONS = setOf("o", "no", "b", "c", "nae", "ae", "nb", "nc", "e", "z", "ne", "nz",
            "be", "na", "a", "nbe", "s", "ns", "p", "pe", "np", "po", "l", "nge", "ge", "nl", "le", "ng", "g", "nle")
        val ZERO_OPERAND = setOf("nop", "hlt", "leave", "syscall", "cbw", "cwde", "cdqe", "cwd", "cdq", "cqo",
            "clc", "stc", "cmc", "cld", "std")
        val BINARY = setOf("mov", "add", "adc", "sub", "sbb", "cmp", "and", "or", "xor", "test")
        val UNARY = setOf("inc", "dec", "neg", "not", "mul", "div", "idiv")
        val SHIFTS = setOf("shl", "sal", "shr", "sar", "rol", "ror")
        val LOOPS = setOf("loop", "jrcxz", "jecxz")
        val MNEMONICS = ZERO_OPERAND + BINARY + UNARY + SHIFTS + LOOPS +
            setOf("ret", "lea", "xchg", "movzx", "movsx", "movsxd", "imul", "push", "pop", "jmp", "call") +
            StringOp.ALL_MNEMONICS
        val PREFIXES = RepPrefix.SPELLINGS.keys
        private val PORT_IO = setOf("insb", "insw", "insd", "outsb", "outsw", "outsd", "ins", "outs")
        private val SEGMENT_REGS = setOf("fs", "gs", "cs", "ds", "es", "ss")
        val DATA_DIRECTIVES = mapOf("db" to 1, "dw" to 2, "dd" to 4, "dq" to 8,
            "resb" to 1, "resw" to 2, "resd" to 4, "resq" to 8)
        val DIRECTIVES = setOf("section", "segment", "global", "extern", "bits", "default", "cpu", "align", "times", "equ") +
            DATA_DIRECTIVES.keys

        fun assemble(source: String) = Assembler().assemble(source)

        /** A leading `name:` label, as the assembler recognises it. */
        internal val LABEL_RE = Regex("^\\s*([A-Za-z_.?@\$][\\w.?@\$#]*)\\s*:")

        /** Index of the first `;` outside a '…', "…" or `…` literal, or -1. */
        internal fun commentStart(line: String): Int {
            var quote: Char? = null
            for ((i, c) in line.withIndex()) {
                if (quote != null) { if (c == quote) quote = null }
                else if (c == '\'' || c == '"' || c == '`') quote = c
                else if (c == ';') return i
            }
            return -1
        }
    }
}

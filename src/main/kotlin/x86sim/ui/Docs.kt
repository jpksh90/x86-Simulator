package x86sim.ui

import x86sim.cpu.Bits
import x86sim.cpu.RepPrefix
import x86sim.cpu.StringOp

/** Short, beginner-friendly descriptions of each supported instruction. */
object Docs {
    data class Entry(val syntax: String, val description: String, val flags: String)

    private val cc = "cc = e/z, ne/nz, l, le, g, ge (signed), b, be, a, ae (unsigned), s, ns, o, no, p, np"

    val instructions: Map<String, Entry> = linkedMapOf(
        "mov" to Entry("mov dst, src", "Copy src into dst. Writing a 32-bit register (eax) also zeroes the upper 32 bits of the 64-bit register.", "none"),
        "movzx" to Entry("movzx reg, r/m8|16", "Copy a smaller value into a larger register, filling the new bits with zeros (unsigned).", "none"),
        "movsx" to Entry("movsx reg, r/m8|16", "Copy a smaller value into a larger register, copying its sign bit (signed).", "none"),
        "movsxd" to Entry("movsxd reg64, r/m32", "Sign-extend a 32-bit value into a 64-bit register.", "none"),
        "lea" to Entry("lea reg, [address]", "Load Effective Address: compute the address expression and store the address itself (no memory is read). Often used for arithmetic.", "none"),
        "xchg" to Entry("xchg a, b", "Swap the values of a and b.", "none"),
        "add" to Entry("add dst, src", "dst = dst + src", "CF OF SF ZF AF PF"),
        "adc" to Entry("adc dst, src", "dst = dst + src + CF (add with carry, for multi-word arithmetic).", "CF OF SF ZF AF PF"),
        "sub" to Entry("sub dst, src", "dst = dst - src", "CF OF SF ZF AF PF"),
        "sbb" to Entry("sbb dst, src", "dst = dst - src - CF (subtract with borrow).", "CF OF SF ZF AF PF"),
        "inc" to Entry("inc dst", "dst = dst + 1. Unlike add, CF is left unchanged.", "OF SF ZF AF PF"),
        "dec" to Entry("dec dst", "dst = dst - 1. Unlike sub, CF is left unchanged.", "OF SF ZF AF PF"),
        "neg" to Entry("neg dst", "dst = -dst (two's complement). CF=1 unless dst was 0.", "CF OF SF ZF AF PF"),
        "cmp" to Entry("cmp a, b", "Compute a - b and set the flags, but throw the result away. Usually followed by a conditional jump.", "CF OF SF ZF AF PF"),
        "mul" to Entry("mul src", "Unsigned multiply: rdx:rax = rax * src (or edx:eax, dx:ax, ax for smaller sizes).", "CF OF (set if the high half is non-zero)"),
        "imul" to Entry("imul src | imul dst, src | imul dst, src, imm", "Signed multiply. One operand: rdx:rax = rax * src. Two: dst *= src. Three: dst = src * imm.", "CF OF (set on overflow)"),
        "div" to Entry("div src", "Unsigned divide rdx:rax by src: quotient -> rax, remainder -> rdx. Clear rdx first! Dividing by 0 crashes.", "undefined"),
        "idiv" to Entry("idiv src", "Signed divide rdx:rax by src: quotient -> rax, remainder -> rdx. Use cqo first to sign-extend rax into rdx.", "undefined"),
        "and" to Entry("and dst, src", "Bitwise AND.", "CF=0 OF=0 SF ZF PF"),
        "or" to Entry("or dst, src", "Bitwise OR.", "CF=0 OF=0 SF ZF PF"),
        "xor" to Entry("xor dst, src", "Bitwise exclusive OR. 'xor eax, eax' is the idiomatic way to set a register to 0.", "CF=0 OF=0 SF ZF PF"),
        "not" to Entry("not dst", "Flip every bit.", "none"),
        "test" to Entry("test a, b", "Compute a AND b and set the flags, discarding the result. 'test rax, rax' checks for zero/negative.", "CF=0 OF=0 SF ZF PF"),
        "shl" to Entry("shl dst, count", "Shift left (multiply by 2^count). The last bit shifted out goes to CF.", "CF OF SF ZF PF"),
        "sal" to Entry("sal dst, count", "Same as shl.", "CF OF SF ZF PF"),
        "shr" to Entry("shr dst, count", "Logical shift right (unsigned divide by 2^count); fills with zeros.", "CF OF SF ZF PF"),
        "sar" to Entry("sar dst, count", "Arithmetic shift right (signed divide by 2^count, rounding down); fills with the sign bit.", "CF OF SF ZF PF"),
        "rol" to Entry("rol dst, count", "Rotate bits left; bits leaving the top re-enter at the bottom.", "CF OF"),
        "ror" to Entry("ror dst, count", "Rotate bits right.", "CF OF"),
        "cbw" to Entry("cbw", "Sign-extend al into ax.", "none"),
        "cwde" to Entry("cwde", "Sign-extend ax into eax.", "none"),
        "cdqe" to Entry("cdqe", "Sign-extend eax into rax.", "none"),
        "cwd" to Entry("cwd", "Sign-extend ax into dx:ax.", "none"),
        "cdq" to Entry("cdq", "Sign-extend eax into edx:eax (use before 32-bit idiv).", "none"),
        "cqo" to Entry("cqo", "Sign-extend rax into rdx:rax (use before 64-bit idiv).", "none"),
        "push" to Entry("push src", "rsp = rsp - 8, then store src at [rsp]. The stack grows towards lower addresses.", "none"),
        "pop" to Entry("pop dst", "Load [rsp] into dst, then rsp = rsp + 8.", "none"),
        "call" to Entry("call label", "Push the address of the next instruction (the return address), then jump to label.", "none"),
        "ret" to Entry("ret", "Pop the return address off the stack and jump to it.", "none"),
        "leave" to Entry("leave", "Tear down a stack frame: mov rsp, rbp then pop rbp.", "none"),
        "jmp" to Entry("jmp label", "Jump unconditionally.", "none"),
        "jcc" to Entry("jcc label", "Jump if the condition holds, based on the flags from the last cmp/test/arithmetic. $cc.", "none"),
        "setcc" to Entry("setcc r/m8", "Set a byte to 1 if the condition holds, else 0. $cc.", "none"),
        "cmovcc" to Entry("cmovcc reg, r/m", "Copy src into reg only if the condition holds. $cc.", "none"),
        "loop" to Entry("loop label", "rcx = rcx - 1; jump to label if rcx != 0.", "none"),
        "jrcxz" to Entry("jrcxz label", "Jump if rcx == 0.", "none"),
        "jecxz" to Entry("jecxz label", "Jump if ecx == 0.", "none"),
        "syscall" to Entry("syscall", "Ask the operating system to do something. rax = syscall number; arguments in rdi, rsi, rdx; result in rax. Clobbers rcx and r11.", "none"),
        "nop" to Entry("nop", "Do nothing.", "none"),
        "hlt" to Entry("hlt", "Stop the processor (ends the simulation).", "none"),
        "clc" to Entry("clc", "Clear the carry flag.", "CF=0"),
        "stc" to Entry("stc", "Set the carry flag.", "CF=1"),
        "cmc" to Entry("cmc", "Complement (flip) the carry flag.", "CF"),
        "cld" to Entry("cld", "Clear the direction flag.", "DF=0"),
        "std" to Entry("std", "Set the direction flag.", "DF=1"),
    )

    private const val direction = " Pointers move forward when DF=0 and backward when DF=1 (cld/std)."

    /** String instructions and their repeat prefixes, listed separately in the Reference. */
    val strings: Map<String, Entry> = linkedMapOf(
        "movs" to Entry("movsb/w/d/q", "Copy one byte/word/dword/qword from [rsi] to [rdi], then advance rsi and rdi by that size.$direction", "none"),
        "stos" to Entry("stosb/w/d/q", "Store al/ax/eax/rax at [rdi], then advance rdi. 'rep stosb' fills memory (like memset).$direction", "none"),
        "lods" to Entry("lodsb/w/d/q", "Load [rsi] into al/ax/eax/rax, then advance rsi. Writing eax zeroes the upper half of rax.$direction", "none"),
        "scas" to Entry("scasb/w/d/q", "Compare al/ax/eax/rax with [rdi] (like cmp), then advance rdi. 'repne scasb' searches for a byte.$direction", "CF OF SF ZF AF PF (like cmp)"),
        "cmps" to Entry("cmpsb/w/d/q", "Compare [rsi] with [rdi] (like cmp [rsi], [rdi]), then advance both. 'repe cmpsb' compares strings.$direction", "CF OF SF ZF AF PF (like cmp)"),
        "rep" to Entry("rep movsb", "Repeat the string instruction rcx times (rcx counts down to 0; nothing happens if rcx is 0). On scas/cmps, rep is the same as repe.", "none"),
        "repe" to Entry("repe cmpsb", "Repeat while rcx ≠ 0 and the last comparison was equal (ZF=1). Also written repz.", "none"),
        "repz" to Entry("repz cmpsb", "Same as repe: repeat while rcx ≠ 0 and ZF=1.", "none"),
        "repne" to Entry("repne scasb", "Repeat while rcx ≠ 0 and the last comparison was not equal (ZF=0). Also written repnz.", "none"),
        "repnz" to Entry("repnz scasb", "Same as repne: repeat while rcx ≠ 0 and ZF=0.", "none"),
    )

    private val conditionNames = mapOf(
        "e" to "equal (ZF=1)", "z" to "zero (ZF=1)", "ne" to "not equal (ZF=0)", "nz" to "not zero (ZF=0)",
        "l" to "less, signed (SF≠OF)", "nge" to "less, signed (SF≠OF)", "le" to "less or equal, signed (ZF=1 or SF≠OF)",
        "ng" to "less or equal, signed", "g" to "greater, signed (ZF=0 and SF=OF)", "nle" to "greater, signed",
        "ge" to "greater or equal, signed (SF=OF)", "nl" to "greater or equal, signed",
        "b" to "below, unsigned (CF=1)", "c" to "carry (CF=1)", "nae" to "below, unsigned (CF=1)",
        "be" to "below or equal, unsigned (CF=1 or ZF=1)", "na" to "below or equal, unsigned",
        "a" to "above, unsigned (CF=0 and ZF=0)", "nbe" to "above, unsigned",
        "ae" to "above or equal, unsigned (CF=0)", "nb" to "above or equal, unsigned", "nc" to "no carry (CF=0)",
        "s" to "sign / negative (SF=1)", "ns" to "not negative (SF=0)", "o" to "overflow (OF=1)", "no" to "no overflow (OF=0)",
        "p" to "parity even (PF=1)", "pe" to "parity even (PF=1)", "np" to "parity odd (PF=0)", "po" to "parity odd (PF=0)",
    )

    /** What the next iteration of a string instruction will do, for the status bar ("copy byte [rsi] → [rdi] · 5 left"). */
    fun stringIteration(op: StringOp, prefix: RepPrefix, rcx: Long, df: Boolean): String {
        val repeat = prefix != RepPrefix.NONE
        if (repeat && rcx == 0L) return "rcx = 0: the repeat is skipped"
        val s = Bits.sizeName(op.size)
        val acc = when (op.size) { 1 -> "al"; 2 -> "ax"; 4 -> "eax"; else -> "rax" }
        val what = when (op.family) {
            StringOp.Family.MOVS -> "copy $s [rsi] → [rdi]"
            StringOp.Family.STOS -> "store $acc → $s [rdi]"
            StringOp.Family.LODS -> "load $s [rsi] → $acc"
            StringOp.Family.SCAS -> "compare $acc with $s [rdi]"
            StringOp.Family.CMPS -> "compare $s [rsi] with $s [rdi]"
        }
        val pointers = listOfNotNull("rsi".takeIf { op.usesRsi }, "rdi".takeIf { op.usesRdi }).joinToString("/")
        val then = when {
            repeat && op.setsFlags && prefix == RepPrefix.REPNE -> " · stops when equal (ZF=1) or rcx = 0"
            repeat && op.setsFlags -> " · stops when different (ZF=0) or rcx = 0"
            else -> ", then $pointers ${if (df) "-=" else "+="} ${op.size}"
        }
        val left = if (repeat && java.lang.Long.compareUnsigned(rcx, 1L shl 32) < 0) " · $rcx left" else ""
        return what + then + left
    }

    fun lookup(mnemonic: String): Entry? {
        val m = mnemonic.lowercase()
        instructions[m]?.let { return it }
        strings[m]?.let { return it }
        StringOp.of(m)?.let { op ->
            val base = strings.getValue(m.dropLast(1))
            val size = Bits.sizeName(op.size)
            return Entry(m, "${size.replaceFirstChar { it.uppercase() }} version: " + base.description, base.flags)
        }
        for ((prefix, generic) in listOf("cmov" to "cmovcc", "set" to "setcc", "j" to "jcc")) {
            if (m.startsWith(prefix)) {
                val cond = conditionNames[m.removePrefix(prefix)] ?: continue
                val base = instructions.getValue(generic)
                val what = when (generic) {
                    "jcc" -> "Jump if $cond."
                    "setcc" -> "Set byte to 1 if $cond, else 0."
                    else -> "Move only if $cond."
                }
                return Entry(base.syntax.replace("cc", m.removePrefix(prefix)), what, base.flags)
            }
        }
        return null
    }

    val flags = listOf(
        "CF" to "Carry flag: the last unsigned operation carried out of (or borrowed into) the top bit.",
        "PF" to "Parity flag: the lowest byte of the result has an even number of 1 bits.",
        "AF" to "Auxiliary carry: carry out of bit 3 (used for BCD arithmetic).",
        "ZF" to "Zero flag: the result was zero.",
        "SF" to "Sign flag: the top bit of the result (1 = negative if treated as signed).",
        "DF" to "Direction flag: string instructions go backwards when set.",
        "OF" to "Overflow flag: the last signed operation produced a result that doesn't fit.",
    )

    val registers = mapOf(
        "rax" to "Accumulator. Return values; syscall number.",
        "rbx" to "General purpose, callee-saved (preserved across calls).",
        "rcx" to "Counter (loop). 4th function argument. Clobbered by syscall.",
        "rdx" to "Data. 3rd argument; high half of mul/div.",
        "rsi" to "Source index. 2nd argument.",
        "rdi" to "Destination index. 1st argument.",
        "rbp" to "Base pointer: the current function's stack frame. Callee-saved.",
        "rsp" to "Stack pointer: address of the top of the stack.",
        "r8" to "5th function argument.", "r9" to "6th function argument.",
        "r10" to "Scratch register (4th syscall argument).", "r11" to "Scratch register. Clobbered by syscall.",
        "r12" to "Callee-saved.", "r13" to "Callee-saved.", "r14" to "Callee-saved.", "r15" to "Callee-saved.",
        "rip" to "Instruction pointer: address of the next instruction to run.",
        "rflags" to "All the status flags packed into one register.",
    )

    val syscalls = listOf(
        Triple("0", "read", "rdi=fd (0=stdin), rsi=buffer, rdx=max bytes → rax=bytes read"),
        Triple("1", "write", "rdi=fd (1=stdout, 2=stderr), rsi=buffer, rdx=length → rax=bytes written"),
        Triple("39", "getpid", "→ rax=process id"),
        Triple("60", "exit", "rdi=exit code"),
        Triple("201", "time", "→ rax=seconds since 1970"),
        Triple("231", "exit_group", "rdi=exit code"),
    )

    fun referenceHtml(): String = buildString {
        val dim = Theme.hex(Theme.dim)
        append("<html><body style='padding:10px'>")
        append("<h2>Supported instructions</h2><table cellpadding=3>")
        for ((_, e) in instructions) {
            append("<tr><td valign=top><code><b>${esc(e.syntax)}</b></code></td><td>${esc(e.description)}")
            if (e.flags != "none") append(" <font color='$dim'>${e.flags}</font>")
            append("</td></tr>")
        }
        append("</table><h2>String instructions</h2><table cellpadding=3>")
        for ((_, e) in strings) {
            append("<tr><td valign=top><code><b>${esc(e.syntax)}</b></code></td><td>${esc(e.description)}")
            if (e.flags != "none") append(" <font color='$dim'>${e.flags}</font>")
            append("</td></tr>")
        }
        append("</table><h2>Linux system calls</h2><p>Put the number in <code>rax</code>, arguments in ")
        append("<code>rdi, rsi, rdx</code>, then execute <code>syscall</code>.</p><table cellpadding=3>")
        for ((n, name, args) in syscalls) append("<tr><td><b>$n</b></td><td><code>$name</code></td><td>${esc(args)}</td></tr>")
        append("</table><h2>Memory layout</h2><table cellpadding=3>")
        append("<tr><td><code>0x401000</code></td><td>.text — your instructions (4 bytes apart)</td></tr>")
        append("<tr><td><code>0x500000</code></td><td>.rodata — read-only data</td></tr>")
        append("<tr><td><code>0x600000</code></td><td>.data — initialised data (db, dw, dd, dq)</td></tr>")
        append("<tr><td><code>0x700000</code></td><td>.bss — zero-filled data (resb, resq, ...)</td></tr>")
        append("<tr><td><code>0x7ffffffef000–0x7ffffffff000</code></td><td>stack (64 KiB, grows down)</td></tr>")
        append("</table><h2>Assembler syntax (NASM)</h2><ul>")
        append("<li>Labels: <code>name:</code>; local labels start with a dot (<code>.loop:</code>) and belong to the previous label.</li>")
        append("<li>Memory: <code>[base + index*scale + disp]</code>, e.g. <code>[rbx + rcx*8 + 16]</code>. Add <code>byte/word/dword/qword</code> when the size is ambiguous.</li>")
        append("<li>Data: <code>db dw dd dq</code>, <code>resb resw resd resq</code>, <code>times N db 0</code>, <code>len equ $ - msg</code>.</li>")
        append("<li>Numbers: <code>42</code>, <code>0x2a</code>, <code>2ah</code>, <code>0b101010</code>, <code>'A'</code>. Strings in backquotes understand escapes: <code>`hi\\n`</code>.</li>")
        append("<li>Execution starts at <code>_start</code> (or <code>main</code>). Returning from it with <code>ret</code> ends the program with exit code <code>eax</code>.</li>")
        append("</ul></body></html>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

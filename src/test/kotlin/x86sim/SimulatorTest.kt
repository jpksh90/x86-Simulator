package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import x86sim.asm.Assembler
import x86sim.asm.AssemblyException
import x86sim.cpu.RepPrefix
import x86sim.cpu.Registers

class SimulatorTest {
    private class Run(val m: Machine, val out: String) {
        fun reg(name: String) = m.cpu.get(Registers.lookup(name)!!)
    }

    private fun run(src: String, input: String? = null): Run {
        val out = StringBuilder()
        val m = Machine()
        m.onOutput = { out.append(it) }
        m.load(Assembler.assemble(src))
        while (true) {
            m.runToEnd(1_000_000)
            if (m.state == MachineState.WAITING_INPUT && input != null) { m.provideInput(input); continue }
            break
        }
        return Run(m, out.toString())
    }

    /** Wraps instructions in a program that stops with `hlt` so registers can be inspected. */
    private fun exec(body: String, data: String = "") =
        run("section .data\n$data\nsection .text\n_start:\n$body\n    hlt\n").also {
            assertEquals(MachineState.HALTED, it.m.state, it.m.message)
        }

    // ---------------- examples ----------------

    @Test fun `examples produce the expected output`() {
        assertEquals("Hello, world!\n", run(Examples.load("01_hello")).out)
        assertEquals("5050\n", run(Examples.load("02_loop_sum")).out)
        assertEquals("3628800\n", run(Examples.load("03_factorial")).out)
        assertEquals("What is your name? Nice to meet you, Ada\n", run(Examples.load("04_echo_name"), "Ada\n").out)
        val fib = generateSequence(0L to 1L) { (a, b) -> b to a + b }.take(20).map { it.first }
        assertEquals(fib.joinToString("") { "$it\n" }, run(Examples.load("05_fibonacci")).out)
        assertEquals(listOf(3, 11, 12, 22, 25, 47, 64, 90).joinToString("") { "$it\n" }, run(Examples.load("06_bubble_sort")).out)
        assertEquals("60\n", run(Examples.load("08_locals")).out)
        assertEquals("copy: Hello, strings!\nzeroed: yes\nlength: 14\ncompare: first difference at index 2\n",
            run(Examples.load("09_strings")).out)
        for ((id, _) in Examples.names) {
            val r = run(Examples.load(id), "x\n")
            assertEquals(MachineState.EXITED, r.m.state, "$id: ${r.m.message}")
            assertEquals(0, r.m.exitCode)
        }
    }

    @Test fun `flags tour matches real hardware`() {
        val r = exec("""
            mov al, 200
            add al, 100
            setc r8b
            mov al, 100
            add al, 50
            seto r9b
            sets r10b
            mov eax, 5
            cmp eax, 7
            setl bl
            seta cl
            setb dl
        """)
        assertEquals(1, r.reg("r8b")); assertEquals(1, r.reg("r9b")); assertEquals(1, r.reg("r10b"))
        assertEquals(1, r.reg("bl")); assertEquals(0, r.reg("cl")); assertEquals(1, r.reg("dl"))
    }

    // ---------------- registers ----------------

    @Test fun `partial register writes follow x86-64 rules`() {
        val r = exec("""
            mov rax, -1
            mov eax, 5          ; 32-bit write zero-extends
            mov rbx, -1
            mov bx, 7           ; 16-bit write preserves the rest
            mov rcx, -1
            mov cl, 0
            mov rdx, 0
            mov dh, 0xAB
        """)
        assertEquals(5L, r.reg("rax"))
        assertEquals(-65536L + 7, r.reg("rbx"))
        assertEquals(-256L, r.reg("rcx"))
        assertEquals(0xAB00L, r.reg("rdx"))
    }

    // ---------------- arithmetic ----------------

    @Test fun `add and sub set carry and overflow`() {
        val r = exec("""
            mov rax, 0x7fffffffffffffff
            add rax, 1
            seto bl
            setc bh
            mov rcx, 0
            sub rcx, 1
            setc dl
            seto dh
        """)
        assertEquals(Long.MIN_VALUE, r.reg("rax"))
        assertEquals(1, r.reg("bl")); assertEquals(0, r.reg("bh"))
        assertEquals(-1L, r.reg("rcx"))
        assertEquals(1, r.reg("dl")); assertEquals(0, r.reg("dh"))
    }

    @Test fun `adc propagates carries across words`() {
        val r = exec("""
            mov rax, -1         ; low word
            mov rdx, 0          ; high word
            add rax, 1
            adc rdx, 0
        """)
        assertEquals(0L, r.reg("rax")); assertEquals(1L, r.reg("rdx"))
    }

    @Test fun `mul and div use rdx rax`() {
        val r = exec("""
            mov rax, 0x100000000
            mov rbx, 0x100000000
            mul rbx             ; 2^64 -> rdx=1 rax=0
            mov r8, rax
            mov r9, rdx
            mov rax, 100
            xor edx, edx
            mov rcx, 7
            div rcx
        """)
        assertEquals(0L, r.reg("r8")); assertEquals(1L, r.reg("r9"))
        assertEquals(14L, r.reg("rax")); assertEquals(2L, r.reg("rdx"))
    }

    @Test fun `signed multiply and divide`() {
        val r = exec("""
            mov rax, -7
            cqo
            mov rcx, 2
            idiv rcx            ; -7 / 2 = -3 rem -1 (truncates toward zero)
            mov r8, rax
            mov r9, rdx
            mov rax, -3
            imul rax, rax, 5
            mov r10, rax
            mov eax, -2
            mov ebx, 3
            imul ebx
        """)
        assertEquals(-3L, r.reg("r8")); assertEquals(-1L, r.reg("r9"))
        assertEquals(-15L, r.reg("r10"))
        assertEquals((-6L) and 0xFFFFFFFFL, r.reg("rax"))
        assertEquals(0xFFFFFFFFL, r.reg("rdx"))
    }

    @Test fun `shifts and rotates`() {
        val r = exec("""
            mov eax, 0b1011
            shr eax, 1
            setc bl
            mov rdi, -16
            sar rdi, 2
            mov dl, 0x81
            rol dl, 1
            setc bh
            mov rsi, 1
            mov cl, 63
            shl rsi, cl
        """)
        assertEquals(0b101L, r.reg("rax")); assertEquals(1, r.reg("bl"))
        assertEquals(-4L, r.reg("rdi"))
        assertEquals(0x03L, r.reg("dl")); assertEquals(1, r.reg("bh"))
        assertEquals(Long.MIN_VALUE, r.reg("rsi"))
    }

    @Test fun `movzx movsx lea and memory addressing`() {
        val r = exec("""
            mov rbx, arr
            mov rcx, 2
            mov rax, [rbx + rcx*8]
            lea rdx, [rbx + rcx*8 + 8]
            movzx esi, byte [bytes]
            movsx rdi, byte [bytes]
            movsxd r8, dword [negv]
        """, data = "arr dq 10, 20, 30, 40\nbytes db 0xF0\nnegv dd -5")
        assertEquals(30L, r.reg("rax"))
        assertEquals(0x600000L + 24, r.reg("rdx"))
        assertEquals(0xF0L, r.reg("rsi")); assertEquals(-16L, r.reg("rdi")); assertEquals(-5L, r.reg("r8"))
    }

    @Test fun `stack push pop call ret`() {
        val r = exec("""
            mov rbx, rsp
            push 1
            push 2
            pop rax
            pop rcx
            call f
            sub rbx, rsp       ; must be balanced again
            jmp done
        f:
            mov rdx, 99
            ret
        done:
        """)
        assertEquals(2L, r.reg("rax")); assertEquals(1L, r.reg("rcx")); assertEquals(99L, r.reg("rdx"))
        assertEquals(0L, r.reg("rbx"))
    }

    @Test fun `ret from entry point exits with eax`() {
        val r = run("section .text\nmain:\n    mov eax, 7\n    ret\n")
        assertEquals(MachineState.EXITED, r.m.state)
        assertEquals(7, r.m.exitCode)
    }

    @Test fun `data directives, equ, times and strings`() {
        val r = run("""
            section .data
            msg db `A\tB\n`
            len equ $ - msg
            pad times 3 db '-'
            w dw 0x1234
            section .text
            _start:
                mov rax, 1
                mov rdi, 1
                mov rsi, msg
                mov rdx, len + 3
                syscall
                movzx ebx, word [w]
                mov rax, 60
                mov rdi, rbx
                syscall
        """.trimIndent())
        assertEquals("A\tB\n---", r.out)
        assertEquals(0x1234, r.m.exitCode)
    }

    // ---------------- faults ----------------

    @Test fun `faults are reported`() {
        assertTrue(run("_start:\n xor ecx, ecx\n div rcx\n").m.message.contains("Divide error"))
        assertTrue(run("_start:\n mov rax, [0]\n").m.message.contains("Segmentation fault"))
        val ro = run("section .rodata\nk dq 1\nsection .text\n_start:\n mov qword [k], 2\n")
        assertEquals(MachineState.FAULTED, ro.m.state)
        assertTrue(ro.m.message.contains("read-only"))
        assertTrue(run("_start:\n push 5\n ret\n").m.message.contains("which is not a return address"))
    }

    // ---------------- assembler errors ----------------

    private fun errors(src: String) = assertFailsWith<AssemblyException> { Assembler.assemble(src) }.errors

    @Test fun `assembler rejects invalid code with helpful errors`() {
        fun first(src: String) = errors("section .data\nx dq 0\nsection .text\n_start:\n$src\n").first()
        assertTrue(first("mov [x], 5").message.contains("size not specified"))
        assertTrue(first("mov [x], [x]").message.contains("two memory operands"))
        assertTrue(first("mov rax, ebx").message.contains("size mismatch"))
        assertTrue(first("jmp nowhere").message.contains("undefined symbol 'nowhere'"))
        assertTrue(first("mvo rax, 1").message.contains("unknown instruction"))
        assertTrue(first("push eax").message.contains("64-bit register"))
        assertTrue(first("add rax, 0x100000000").message.contains("32-bit"))
        assertEquals(4, first("mov 5, rax").line)
        assertTrue(errors("section .data\nneg dd 1\n_start:\n nop\n").first().message.contains("instruction name"))
        val many = errors("_start:\n foo\n mov rax\n bar\n")
        assertEquals(listOf(1, 2, 3), many.map { it.line })
    }

    // ---------------- string instructions: assembler ----------------

    @Test fun `string instructions and rep prefixes assemble`() {
        fun one(line: String) = Assembler.assemble("f:\n$line\n hlt\n").instructions.first()
        val cases = listOf(
            "movsb" to ("movsb" to RepPrefix.NONE),
            "rep movsq" to ("movsq" to RepPrefix.REP),
            "REPNE SCASB" to ("scasb" to RepPrefix.REPNE),
            ".cmp: repe cmpsb" to ("cmpsb" to RepPrefix.REPE),
            "repz cmpsw" to ("cmpsw" to RepPrefix.REPE),
            "rep scasb" to ("scasb" to RepPrefix.REP),
            "repne stosb" to ("stosb" to RepPrefix.REPNE),
            "rep lodsb ; load" to ("lodsb" to RepPrefix.REP),
        )
        for ((line, expected) in cases) {
            val i = one(line)
            assertEquals(expected, i.mnemonic to i.prefix, line)
            assertTrue(i.operands.isEmpty(), line)
        }
        for ((id, _) in Examples.names.filter { it.first != "09_strings" }) // programs written before string instructions
            assertTrue(Assembler.assemble(Examples.load(id)).instructions.all { it.prefix == RepPrefix.NONE }, id)
    }

    @Test fun `misused string instructions and prefixes give helpful errors`() {
        fun check(src: String, line: Int, vararg phrases: String) {
            val e = errors(src).single()
            assertEquals(line, e.line, src)
            for (p in phrases) assertTrue(e.message.contains(p), "'$src': expected '$p' in '${e.message}'")
        }
        val s = "_start:\n"
        check("$s rep\n", 1, "'rep' needs a string instruction after it", "rep movsb")
        check("$s rep add rax, 1\n", 1, "only works with string instructions", "'add'")
        check("$s repne jmp foo\n", 1, "only works with string instructions", "'jmp'")
        check("$s rep rep movsb\n", 1, "only works with string instructions", "'rep'")
        for (bare in listOf("movs byte [rdi], [rsi]", "stos", "lods", "scas", "cmps"))
            check("$s $bare\n", 1, "write the size in the name")
        check("$s movs byte [rdi], [rsi]\n", 1, "'movsb', 'movsw', 'movsd' or 'movsq'")
        for (bad in listOf("stosb al", "lodsq rax", "movsb [rdi], [rsi]")) check("$s $bad\n", 1, "takes no operands")
        for (sse in listOf("movsd xmm0, [rsi]", "cmpsd xmm1, xmm2, 0"))
            check("$s $sse\n", 1, "SSE floating-point", "isn't supported", "takes no operands")
        for (io in listOf("insb", "insw", "insd", "outsb", "outsw", "outsd", "rep outsb"))
            check("$s $io\n", 1, "port I/O", "privileged", "not supported")
        for (a in listOf("a32 rep movsb", "a32 movsb"))
            check("$s $a\n", 1, "address-size override", "isn't supported", "rcx, rsi and rdi")
        for (seg in listOf("fs movsb", "gs rep stosb")) check("$s $seg\n", 1, "segment overrides", "aren't supported")
        check("$s nop\nrep: nop\n", 2, "is an instruction name and can't be a label")
        check("$s nop\nmovsb: nop\n", 2, "is an instruction name and can't be a label")
        check("section .data\n rep movsb\nsection .text\n$s nop\n", 1, "outside section .text")
        // a data label that happens to be a segment register name still works
        Assembler.assemble("section .data\ncs db 1\nsection .text\n$s nop\n")
    }
}

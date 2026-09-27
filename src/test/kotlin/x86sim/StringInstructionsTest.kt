package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x86sim.asm.Assembler
import x86sim.cpu.RepPrefix
import x86sim.cpu.Registers
import x86sim.cpu.StringOp
import x86sim.cpu.StringOp.Family
import x86sim.ui.Docs

class StringInstructionsTest {
    // ---------------- helpers ----------------

    private fun load(src: String): Machine = Machine().apply {
        onOutput = {}
        load(Assembler.assemble(src))
    }

    private fun program(body: String, data: String = "", bss: String = "") =
        "section .data\n$data\nsection .bss\n$bss\nsection .text\n_start:\n$body\n    hlt\n"

    /** Runs [body] followed by `hlt`; doesn't assert the final state (some tests expect a fault). */
    private fun exec(body: String, data: String = "", bss: String = ""): Machine =
        load(program(body, data, bss)).apply { runToEnd(5_000_000) }

    private fun Machine.reg(name: String): Long = cpu.get(Registers.lookup(name)!!)
    private fun Machine.sym(name: String): Long = program!!.symbols.getValue(name)
    private fun Machine.bytesAt(addr: Long, n: Int): List<Int> = (0 until n).map { memory.peek(addr + it)!! }
    private fun Machine.text(addr: Long, n: Int) = String(bytesAt(addr, n).map { it.toByte() }.toByteArray())
    private fun Machine.source() = cpu.currentInstruction()!!.source.trim()

    private fun Machine.stepUntilLine(text: String) {
        repeat(10_000) {
            if (source().startsWith(text)) return
            step()
        }
        error("never reached '$text'")
    }

    private fun Machine.assertHalted() = assertEquals(MachineState.HALTED, state, message)

    // ---------------- model ----------------

    @Test fun `StringOp recognises exactly the twenty sized mnemonics`() {
        val sizes = mapOf('b' to 1, 'w' to 2, 'd' to 4, 'q' to 8)
        for (f in Family.entries) for ((c, n) in sizes) {
            val op = StringOp.of(f.name.lowercase() + c)
            assertEquals(StringOp(f, n), op)
        }
        assertEquals(StringOp(Family.MOVS, 8), StringOp.of("MOVSQ"))
        for (w in listOf("movs", "movsx", "movsxd", "rep", "insb", "mov", "stosx", "")) assertNull(StringOp.of(w), w)
        assertEquals(20, StringOp.ALL_MNEMONICS.size)
        assertEquals(setOf("movs", "stos", "lods", "scas", "cmps"), StringOp.BARE)
    }

    @Test fun `RepPrefix parses every spelling`() {
        assertEquals(RepPrefix.REP, RepPrefix.parse("rep"))
        assertEquals(RepPrefix.REPE, RepPrefix.parse("repe"))
        assertEquals(RepPrefix.REPE, RepPrefix.parse("REPZ"))
        assertEquals(RepPrefix.REPNE, RepPrefix.parse("repne"))
        assertEquals(RepPrefix.REPNE, RepPrefix.parse("repnz"))
        assertNull(RepPrefix.parse("movsb"))
    }

    @Test fun `stop rule depends on the family and prefix`() {
        for (f in listOf(Family.SCAS, Family.CMPS)) {
            val op = StringOp(f, 1)
            assertTrue(op.stopsAfter(RepPrefix.REP, zf = false)); assertFalse(op.stopsAfter(RepPrefix.REP, zf = true))
            assertTrue(op.stopsAfter(RepPrefix.REPE, zf = false)); assertFalse(op.stopsAfter(RepPrefix.REPE, zf = true))
            assertTrue(op.stopsAfter(RepPrefix.REPNE, zf = true)); assertFalse(op.stopsAfter(RepPrefix.REPNE, zf = false))
            assertFalse(op.stopsAfter(RepPrefix.NONE, zf = true)); assertFalse(op.stopsAfter(RepPrefix.NONE, zf = false))
        }
        for (f in listOf(Family.MOVS, Family.STOS, Family.LODS)) for (p in RepPrefix.entries) for (zf in listOf(true, false))
            assertFalse(StringOp(f, 1).stopsAfter(p, zf))
    }

    @Test fun `derived operand facts match the data model`() {
        fun facts(f: Family) = StringOp(f, 1).let { Triple(it.usesRsi, it.usesRdi, it.setsFlags) }
        assertEquals(Triple(true, true, false), facts(Family.MOVS))
        assertEquals(Triple(false, true, false), facts(Family.STOS))
        assertEquals(Triple(true, false, false), facts(Family.LODS))
        assertEquals(Triple(false, true, true), facts(Family.SCAS))
        assertEquals(Triple(true, true, true), facts(Family.CMPS))
    }

    // ---------------- US1: movs / stos ----------------

    @Test fun `rep movsb copies a string`() {
        val m = exec(" lea rsi, [msg]\n lea rdi, [buf]\n mov ecx, 13\n cld\n rep movsb",
            data = "msg db 'Hello, world!'", bss = "buf resb 16")
        m.assertHalted()
        assertEquals("Hello, world!", m.text(m.sym("buf"), 13))
        assertEquals(0L, m.reg("rcx"))
        assertEquals(m.sym("msg") + 13, m.reg("rsi"))
        assertEquals(m.sym("buf") + 13, m.reg("rdi"))
    }

    @Test fun `rep stosq fills qwords with rax`() {
        val m = exec(" lea rdi, [arr]\n mov rax, 0x1122334455667788\n mov ecx, 4\n rep stosq", bss = "arr resq 4")
        m.assertHalted()
        for (k in 0 until 4) assertEquals(0x1122334455667788L, m.memory.read(m.sym("arr") + 8 * k, 8))
        assertEquals(m.sym("arr") + 32, m.reg("rdi"))
        assertEquals(0L, m.reg("rcx"))
    }

    @Test fun `a repeat with rcx = 0 does nothing`() {
        for (ins in listOf("rep movsb", "rep stosb", "repe cmpsb", "repne scasb", "rep lodsq")) {
            val m = load(program(" lea rsi, [msg]\n lea rdi, [buf]\n mov eax, 0x41\n stc\n cmp eax, eax\n xor ecx, ecx\n $ins\n nop",
                data = "msg db 'abcd'", bss = "buf resb 4"))
            m.stepUntilLine(ins)
            val address = m.cpu.rip
            fun state() = listOf(m.cpu.regs.toList(), m.cpu.rflags, m.bytesAt(m.sym("buf"), 4))
            val before = state()
            m.step()
            assertEquals(before, state(), ins)
            assertEquals(address + 4, m.cpu.rip, "$ins moves on to the next instruction")
        }
    }

    @Test fun `with DF set a copy runs backwards`() {
        val m = exec(" lea rsi, [msg+4]\n lea rdi, [buf+4]\n mov ecx, 5\n std\n rep movsb",
            data = "msg db 'abcde'", bss = "buf resb 8")
        m.assertHalted()
        assertEquals("abcde", m.text(m.sym("buf"), 5))
        assertEquals(m.sym("msg") - 1, m.reg("rsi"))
        assertEquals(m.sym("buf") - 1, m.reg("rdi"))
    }

    @Test fun `unprefixed movs and stos move one element and leave rcx alone`() {
        for ((suffix, n) in listOf('b' to 1, 'w' to 2, 'd' to 4, 'q' to 8)) {
            val m = exec(" lea rsi, [src]\n lea rdi, [dst]\n mov ecx, 0x55\n movs$suffix\n" +
                " lea rdi, [dst2]\n mov rax, -1\n stos$suffix",
                data = "src db 1,2,3,4,5,6,7,8,9", bss = "dst resb 16\ndst2 resb 16")
            m.assertHalted()
            assertEquals((1..n).toList() + List(9 - n) { 0 }, m.bytesAt(m.sym("dst"), 9), "movs$suffix")
            assertEquals(List(n) { 0xFF } + List(9 - n) { 0 }, m.bytesAt(m.sym("dst2"), 9), "stos$suffix")
            assertEquals(m.sym("src") + n, m.reg("rsi"))
            assertEquals(m.sym("dst2") + n, m.reg("rdi"))
            assertEquals(0x55L, m.reg("rcx"))
        }
    }

    @Test fun `every string instruction moves its pointers by its size in both directions`() {
        for (mn in StringOp.ALL_MNEMONICS) for (df in listOf("cld", "std")) {
            val op = StringOp.of(mn)!!
            val m = exec(" lea rsi, [a+16]\n lea rdi, [b+16]\n xor eax, eax\n $df\n $mn",
                data = "a times 40 db 1", bss = "b resb 40")
            m.assertHalted()
            val d = if (df == "std") -op.size.toLong() else op.size.toLong()
            assertEquals(m.sym("a") + 16 + if (op.usesRsi) d else 0, m.reg("rsi"), "$mn $df rsi")
            assertEquals(m.sym("b") + 16 + if (op.usesRdi) d else 0, m.reg("rdi"), "$mn $df rdi")
        }
    }

    @Test fun `an overlapping forward copy spreads the first byte`() {
        val m = exec(" lea rsi, [s]\n lea rdi, [s+1]\n mov ecx, 7\n rep movsb", data = "s db 'A.......'")
        m.assertHalted()
        assertEquals("AAAAAAAA", m.text(m.sym("s"), 8))
    }

    @Test fun `movs and stos leave the flags alone`() {
        val m = load(program(" mov al, 0x7f\n add al, 1\n lea rsi, [s]\n lea rdi, [d]\n mov ecx, 3\n rep movsb\n mov ecx, 2\n rep stosb",
            data = "s db 'xyz'", bss = "d resb 8"))
        m.stepUntilLine("lea rsi")
        val flags = m.cpu.rflags
        assertTrue(m.cpu.of && m.cpu.sf && m.cpu.af)
        m.runToEnd()
        m.assertHalted()
        assertEquals(flags, m.cpu.rflags)
    }

    @Test fun `a fault partway through a repeat keeps the completed iterations`() {
        val m = exec(" lea rdi, [buf+4094]\n mov ecx, 5\n rep stosb", bss = "buf resb 16")
        assertEquals(MachineState.FAULTED, m.state)
        assertTrue(m.message.contains("Segmentation fault"), m.message)
        assertEquals(3L, m.reg("rcx"))
        assertEquals(m.sym("buf") + 4096, m.reg("rdi"))
        assertEquals("rep stosb", m.source())
    }

    @Test fun `each iteration is one step`() {
        for (count in listOf(5, 0)) {
            val m = load(program(" lea rsi, [s]\n lea rdi, [d]\n mov ecx, $count\n rep movsb\n nop", data = "s db 'abcde'", bss = "d resb 8"))
            m.stepUntilLine("rep movsb")
            val s0 = m.steps
            m.stepUntilLine("nop")
            assertEquals(maxOf(count, 1).toLong(), m.steps - s0, "rcx = $count")
        }
    }

    @Test fun `the counter is the full 64-bit rcx`() {
        val m = exec(" lea rdi, [s]\n xor eax, eax\n mov rcx, 0x100000000\n repne scasb", data = "s db 'a', 0")
        m.assertHalted()
        assertEquals(0xFFFFFFFEL, m.reg("rcx"))
        assertEquals(m.sym("s") + 2, m.reg("rdi"))
    }

    // ---------------- US2: stepping ----------------

    @Test fun `headless and history runs agree`() {
        val src = program(" lea rsi, [s]\n lea rdi, [d]\n mov ecx, 5\n rep movsb\n mov rax, -2\n lea rdi, [q]\n mov ecx, 3\n rep stosq",
            data = "s db 'abcde'", bss = "d resb 8\nq resq 3")
        fun final(history: Boolean): List<Any> {
            val m = Machine().apply { onOutput = {}; keepHistory = history; load(Assembler.assemble(src)); runToEnd() }
            m.assertHalted()
            return listOf(m.cpu.regs.toList(), m.cpu.rflags, m.cpu.rip, m.steps, m.bytesAt(m.sym("d"), 32))
        }
        assertEquals(final(true), final(false))
    }

    @Test fun `status bar describes the next iteration`() {
        fun d(mn: String, p: RepPrefix, rcx: Long, df: Boolean = false) = Docs.stringIteration(StringOp.of(mn)!!, p, rcx, df)
        assertEquals("copy byte [rsi] → [rdi], then rsi/rdi += 1 · 5 left", d("movsb", RepPrefix.REP, 5))
        assertEquals("store rax → qword [rdi], then rdi += 8 · 4 left", d("stosq", RepPrefix.REP, 4))
        assertEquals("compare al with byte [rdi] · stops when equal (ZF=1) or rcx = 0", d("scasb", RepPrefix.REPNE, -1))
        assertEquals("compare byte [rsi] with byte [rdi] · stops when different (ZF=0) or rcx = 0 · 3 left", d("cmpsb", RepPrefix.REPE, 3))
        for (p in listOf(RepPrefix.REP, RepPrefix.REPE, RepPrefix.REPNE))
            assertEquals("rcx = 0: the repeat is skipped", d("movsw", p, 0))
        assertEquals("load byte [rsi] → al, then rsi -= 1", d("lodsb", RepPrefix.NONE, 0, df = true))
    }

    // ---------------- US3: lods / scas / cmps ----------------

    private val FLAGS = 0x8D5L // CF PF AF ZF SF OF

    @Test fun `repne scasb computes strlen`() {
        val m = exec(" xor eax, eax\n mov rcx, -1\n lea rdi, [s]\n repne scasb", data = "s db 'abc', 0")
        m.assertHalted()
        assertTrue(m.cpu.zf)
        assertEquals(m.sym("s") + 4, m.reg("rdi"))
        assertEquals(-5L, m.reg("rcx"))
        assertEquals(3L, (-5L).inv() - 1, "not rcx; dec rcx gives the length")
    }

    @Test fun `repe cmpsb stops at the first difference with cmp flags`() {
        val m = exec(" lea rsi, [a]\n lea rdi, [b]\n mov ecx, 4\n repe cmpsb", data = "a db 'abcd'\nb db 'abXd'")
        m.assertHalted()
        assertEquals(1L, m.reg("rcx"))
        assertFalse(m.cpu.zf)
        assertEquals(m.sym("a") + 3, m.reg("rsi"))
        assertEquals(m.sym("b") + 3, m.reg("rdi"))
        val ref = exec(" mov al, 'c'\n cmp al, 'X'")
        assertEquals(ref.cpu.rflags and FLAGS, m.cpu.rflags and FLAGS)
    }

    @Test fun `repe cmpsb on equal strings runs out the counter`() {
        val m = exec(" lea rsi, [a]\n lea rdi, [b]\n mov ecx, 4\n repe cmpsb", data = "a db 'same'\nb db 'same'")
        m.assertHalted()
        assertEquals(0L, m.reg("rcx"))
        assertTrue(m.cpu.zf)
    }

    @Test fun `rep on scas behaves as repe and repne on stos as rep`() {
        for (p in listOf("rep", "repe")) {
            val m = exec(" mov al, 'a'\n lea rdi, [s]\n mov ecx, 3\n $p scasb", data = "s db 'aab'")
            m.assertHalted()
            assertEquals(0L, m.reg("rcx"), p); assertFalse(m.cpu.zf, p)
            assertEquals(m.sym("s") + 3, m.reg("rdi"), p)
        }
        val m = exec(" mov al, 7\n lea rdi, [d]\n mov ecx, 5\n repne stosb", bss = "d resb 8")
        m.assertHalted()
        assertEquals(listOf(7, 7, 7, 7, 7, 0), m.bytesAt(m.sym("d"), 6))
    }

    @Test fun `lods follows the partial register rules`() {
        fun lods(ins: String) = exec(" mov rax, -1\n lea rsi, [v]\n $ins", data = "v dq 0x1122334455667741").also { it.assertHalted() }
        assertEquals(-0xBFL, lods("lodsb").reg("rax"))                 // 0xFFFFFFFFFFFFFF41
        assertEquals(0xFFFFFFFFFFFF7741uL.toLong(), lods("lodsw").reg("rax"))
        assertEquals(0x55667741L, lods("lodsd").reg("rax"))            // upper half zeroed
        assertEquals(0x1122334455667741L, lods("lodsq").reg("rax"))
        assertEquals(lods("lodsq").sym("v") + 8, lods("lodsq").reg("rsi"))
    }

    @Test fun `rep lodsb leaves the last byte in al`() {
        val m = exec(" lea rsi, [s]\n mov ecx, 3\n rep lodsb", data = "s db 'xyz'")
        m.assertHalted()
        assertEquals('z'.code.toLong(), m.reg("al"))
        assertEquals(m.sym("s") + 3, m.reg("rsi"))
    }

    @Test fun `unprefixed scas and cmps compare once and leave rcx alone`() {
        val m = exec(" mov rax, 5\n lea rdi, [q]\n mov ecx, 9\n scasq", data = "q dq 5")
        m.assertHalted()
        assertTrue(m.cpu.zf); assertEquals(9L, m.reg("rcx")); assertEquals(m.sym("q") + 8, m.reg("rdi"))
        val c = exec(" lea rsi, [a]\n lea rdi, [b]\n mov ecx, 9\n cmpsw", data = "a dw 1\nb dw 2")
        c.assertHalted()
        assertTrue(c.cpu.cf && !c.cpu.zf); assertEquals(9L, c.reg("rcx"))
    }

    @Test fun `scas and cmps set flags exactly like cmp`() {
        val rnd = java.util.Random(42)
        for ((suffix, n) in listOf('b' to 1, 'w' to 2, 'd' to 4, 'q' to 8)) {
            val mask = x86sim.cpu.Bits.mask(n)
            val grid = listOf(0L, 1L, mask ushr 1, (mask ushr 1) + 1, mask, 0x5A5A5A5A5A5A5A5AL and mask, rnd.nextLong() and mask)
            val acc = when (n) { 1 -> "al"; 2 -> "ax"; 4 -> "eax"; else -> "rax" }
            val sz = x86sim.cpu.Bits.sizeName(n)
            val dir = when (n) { 1 -> "db"; 2 -> "dw"; 4 -> "dd"; else -> "dq" }
            for (a in grid) for (b in grid) {
                val data = "u $dir $a\nv $dir $b"
                val scas = exec(" mov rax, $a\n lea rdi, [v]\n scas$suffix", data)
                val cmps = exec(" lea rsi, [u]\n lea rdi, [v]\n cmps$suffix", data)
                val ref = exec(" mov rax, $a\n cmp $acc, $sz [v]", data)
                for (m in listOf(scas, cmps, ref)) m.assertHalted()
                assertEquals(ref.cpu.rflags and FLAGS, scas.cpu.rflags and FLAGS, "scas$suffix $a vs $b")
                assertEquals(ref.cpu.rflags and FLAGS, cmps.cpu.rflags and FLAGS, "cmps$suffix $a vs $b")
            }
        }
    }

    // ---------------- US4: help ----------------

    @Test fun `every string mnemonic and prefix is documented`() {
        for (w in StringOp.ALL_MNEMONICS + StringOp.BARE + RepPrefix.SPELLINGS.keys) {
            val e = Docs.lookup(w)
            kotlin.test.assertNotNull(e, w)
            if (RepPrefix.parse(w) == null) {
                assertTrue(e.description.contains("rsi") || e.description.contains("rdi"), w)
                assertTrue(e.description.contains("DF"), w)
            } else assertTrue(e.description.contains("rcx"), w)
        }
        assertTrue(Docs.lookup("movsw")!!.description.contains("word"))
        assertTrue(Docs.lookup("scasb")!!.flags.contains("ZF"))
        assertEquals("none", Docs.lookup("movsb")!!.flags)
        assertTrue(Docs.lookup("rep")!!.description.contains("repe"))
        assertTrue(Docs.referenceHtml().contains("String instructions"))
    }

    // ---------------- performance ----------------

    @Test fun `a million-iteration rep stosb finishes quickly`() {
        val m = load(program(" lea rdi, [buf]\n mov al, 0x5a\n mov ecx, 1000000\n rep stosb", bss = "buf resb 1000000"))
        assertTrue(m.keepHistory)
        val ms = kotlin.system.measureTimeMillis { m.runToEnd() }
        m.assertHalted()
        assertTrue(ms < 2000, "took $ms ms")
        assertEquals(50_000, m.historySize)
        assertEquals(0x5a, m.memory.peek(m.sym("buf") + 999_999))
        println("1M-iteration rep stosb: $ms ms")
    }
}

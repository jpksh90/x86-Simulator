package x86sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x86sim.asm.Assembler
import x86sim.asm.SourceLayout
import x86sim.asm.SourceLayout.LineKind

/** Assembly source layout rules (specs/006-asm-editor-indentation/data-model.md). */
class SourceLayoutTest {
    private val examples = Examples.names.map { it.first }

    // ---------------- split / kind / visualWidth ----------------

    @Test fun `split separates indent, code, gap and comment`() {
        val p = SourceLayout.split("    mov rax, 1    ; hi")
        assertEquals(SourceLayout.LineParts("    ", "mov rax, 1", "    ", "; hi"), p)
        val bare = SourceLayout.split("  nop   ")
        assertEquals(SourceLayout.LineParts("  ", "nop", "", null), bare)
    }

    @Test fun `semicolons inside literals are not comments`() {
        assertNull(SourceLayout.split("    msg db \"a;b\", 10").comment)
        assertNull(SourceLayout.split("    mov al, ';'").comment)
        assertEquals("; real", SourceLayout.split("    mov al, ';' ; real").comment)
    }

    @Test fun `split keeps every character of a commented line`() {
        for (line in listOf("    mov rax, 1    ; hi", "\t; only", "x:;y", "  db '\"', 1 ;c ")) {
            val p = SourceLayout.split(line)
            assertEquals(line, p.indent + p.code + p.gap + (p.comment ?: ""))
        }
    }

    @Test fun `line kinds`() {
        fun k(s: String) = SourceLayout.kind(s)
        assertEquals(LineKind.Blank, k(""))
        assertEquals(LineKind.Blank, k("   "))
        assertEquals(LineKind.Comment, k("  ; x"))
        for (s in listOf("_start:", ".again:", ".l: dec rcx", "    msg: db 1")) assertEquals(LineKind.Label, k(s), s)
        for (s in listOf("section .text", "SEGMENT .data", "    global _start", "extern x", "default rel", "bits 64"))
            assertEquals(LineKind.Directive, k(s), s)
        for (s in listOf("mov rcx, 100", "msg db \"x:y\", 10", "len equ $ - msg", "times 4 db 0", "align 8",
            "rep movsb", "_start", "foo bar", "mov al, ':'"))
            assertEquals(LineKind.Code, k(s), s)
    }

    @Test fun `tabs advance to the next multiple of four`() {
        assertEquals(5, SourceLayout.visualWidth("\tx"))
        assertEquals(5, SourceLayout.visualWidth("  \tx"))
        assertEquals(9, SourceLayout.visualWidth("\t\tx"))
        assertEquals(3, SourceLayout.visualWidth("abc"))
    }

    // ---------------- US1: indentAfter / placed ----------------

    @Test fun `indent after a line`() {
        for (s in listOf("_start:", ".l: dec rcx", "section .text", "    global _start"))
            assertEquals(4, SourceLayout.indentAfter(s), s)
        assertEquals(4, SourceLayout.indentAfter("    mov rcx, 100"))
        assertEquals(8, SourceLayout.indentAfter("        nop"))
        assertEquals(0, SourceLayout.indentAfter("mov rax, 1"))
        assertEquals(6, SourceLayout.indentAfter("      "))
        assertEquals(4, SourceLayout.indentAfter("    ; note"))
    }

    @Test fun `placed moves labels and directives to column zero`() {
        assertEquals(".again:", SourceLayout.placed("    .again:", 4))
        assertEquals("section .bss", SourceLayout.placed("\tsection .bss", 4))
        assertEquals("    mov rax, 1", SourceLayout.placed("mov rax, 1", 4))
        assertEquals("        nop", SourceLayout.placed("  nop", 8))
        assertEquals("   ; c", SourceLayout.placed("   ; c", 4))
        assertEquals("  ", SourceLayout.placed("  ", 4))
    }

    // ---------------- US3: comment column while typing ----------------

    @Test fun `comment column follows the run`() {
        val lines = listOf(
            "_start:",
            "    xor eax, eax        ; a",
            "    mov rcx, 100        ; b",
            "    mov rdx, 1                ; c",
            "    inc rax",
        )
        assertEquals(24, SourceLayout.commentColumnFor(lines, 4))
    }

    @Test fun `comment column ties go to the smaller column`() {
        val lines = listOf("    a                       ; x", "    b                   ; y", "    c")
        assertEquals(24, SourceLayout.commentColumnFor(lines, 2))
    }

    @Test fun `comment column defaults and clears long code`() {
        assertEquals(28, SourceLayout.commentColumnFor(listOf("    inc rax"), 0))
        val long = "    " + "x".repeat(26) // 30 wide
        assertEquals(31, SourceLayout.commentColumnFor(listOf("    a                   ; y", long), 1))
    }

    @Test fun `blank and comment lines end a run`() {
        val lines = listOf(
            "    a               ; x",
            "",
            "    b                           ; y",
            "    ; full-line",
            "    c",
        )
        assertEquals(28, SourceLayout.commentColumnFor(lines, 4))
    }

    // ---------------- US4: format ----------------

    @Test fun `examples are already formatted`() {
        for (id in examples) {
            val src = Examples.load(id)
            assertEquals(src, SourceLayout.format(src), id)
        }
    }

    @Test fun `format restores stripped examples`() {
        for (id in examples) {
            val src = Examples.load(id)
            val formatted = SourceLayout.format(strip(src)).split("\n")
            for ((i, line) in src.split("\n").withIndex()) {
                val kind = SourceLayout.kind(line)
                if (kind == LineKind.Blank || kind == LineKind.Comment) continue
                assertEquals(SourceLayout.split(line).indent.length, SourceLayout.split(formatted[i]).indent.length, "$id:${i + 1}")
            }
        }
    }

    @Test fun `format is idempotent`() {
        val once = SourceLayout.format(messy)
        assertEquals(once, SourceLayout.format(once))
    }

    @Test fun `format places lines and keeps text`() {
        val out = SourceLayout.format(messy).split("\n")
        assertEquals(
            listOf(
                "; header",
                "section .data",
                "    msg db \"a: b; c\", 10",
                "",
                "section .text",
                "global _start",
                "; set up",
                "_start:",
                "    mov rax, 1              ; one",
                "    mov rdi, 2              ; two",
                "",
                "; helper",
                "print:",
                "    ret",
                "",
            ),
            out,
        )
    }

    @Test fun `full-line comment at the end takes column zero`() {
        assertEquals("    nop\n; bye", SourceLayout.format("    nop\n    ; bye"))
    }

    @Test fun `format converts tabs and trailing whitespace`() {
        // The tab put the comment at column 16, which clears the code, so it stays there.
        assertEquals("    mov rax, 1  ; x", SourceLayout.format("\tmov rax, 1\t; x   "))
        assertEquals("    nop", SourceLayout.format("    nop   "))
    }

    @Test fun `format comment column per run`() {
        // A shared column that clears the code is kept.
        assertEquals(
            "    a                             ; x\n    b                             ; y",
            SourceLayout.format("    a                             ; x\n    b                             ; y"),
        )
        // Mixed columns go to the default.
        assertEquals(
            "    a                       ; x\n    b                       ; y",
            SourceLayout.format("    a                       ; x\n    b                          ; y"),
        )
        // Long code pushes the column out.
        val long = "x".repeat(36) // 40 wide once indented
        assertEquals(
            "    $long ; x\n    b                                    ; y",
            SourceLayout.format("    $long ; x\n    b ; y"),
        )
    }

    @Test fun `format keeps line endings`() {
        assertEquals("_start:\r\n    nop\r\n", SourceLayout.format("  _start:\r\nnop\r\n"))
    }

    @Test fun `format never changes code or comment text`() {
        for (id in examples) {
            val before = strip(Examples.load(id)).split("\n").map(SourceLayout::split)
            val after = SourceLayout.format(strip(Examples.load(id))).split("\n").map(SourceLayout::split)
            assertEquals(before.map { it.code }, after.map { it.code }, id)
            assertEquals(before.map { it.comment?.trimEnd() }, after.map { it.comment }, id)
        }
    }

    @Test fun `formatting preserves the assembled program and its output`() {
        for (id in examples) {
            val src = Examples.load(id)
            for (variant in listOf(src, strip(src))) {
                val formatted = SourceLayout.format(variant)
                val a = Assembler.assemble(variant)
                val b = Assembler.assemble(formatted)
                assertEquals(a.instructions.map { it.copy(source = "") }, b.instructions.map { it.copy(source = "") }, id)
                assertEquals(a.symbols, b.symbols, id)
                assertEquals(a.sections.map { it.name to it.data.toByteArray().toList() },
                    b.sections.map { it.name to it.data.toByteArray().toList() }, id)
                assertEquals(run(variant), run(formatted), id)
            }
        }
    }

    @Test fun `format handles a ten thousand line program quickly`() {
        val body = Examples.load("06_bubble_sort")
        val big = body.repeat(10_000 / body.count { it == '\n' } + 1)
        val strippedBig = strip(big)
        val start = System.nanoTime()
        SourceLayout.format(strippedBig)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1000, "format took $ms ms")
    }

    // ---------------- helpers ----------------

    private val messy = listOf(
        "      ; header",
        "  section .data",
        "msg db \"a: b; c\", 10   ",
        "   ",
        "section .text",
        "    global _start",
        "; set up",
        "    _start:",
        "mov rax, 1 ; one",
        "\tmov rdi, 2\t\t; two",
        "",
        "; helper",
        "  print:",
        "        ret",
        "",
    ).joinToString("\n")

    private fun strip(src: String) = src.split("\n").joinToString("\n") { it.trimStart() }

    private data class Outcome(val output: String, val state: MachineState, val exitCode: Int?)

    private fun run(src: String): Outcome {
        val out = StringBuilder()
        val m = Machine()
        m.onOutput = { out.append(it) }
        m.load(Assembler.assemble(src))
        while (true) {
            m.runToEnd(1_000_000)
            if (m.state == MachineState.WAITING_INPUT) { m.provideInput("Ada\n"); continue }
            break
        }
        return Outcome(out.toString(), m.state, m.exitCode)
    }
}

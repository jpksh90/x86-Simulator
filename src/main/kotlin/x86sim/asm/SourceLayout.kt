package x86sim.asm

/**
 * Column layout of NASM source, the way the bundled examples are written: labels and
 * section-level directives in column 0, instructions and data one level in, and trailing comments
 * lined up in a column. Pure string functions; the editor maps keys onto them.
 */
object SourceLayout {
    const val INDENT = 4
    const val DEFAULT_COMMENT_COLUMN = 28
    val LEVEL_ZERO_DIRECTIVES = setOf("section", "segment", "global", "extern", "default", "bits")

    /** A line split for layout; `indent + code + gap + comment` is the line minus trailing blanks. */
    data class LineParts(val indent: String, val code: String, val gap: String, val comment: String?)

    enum class LineKind { Blank, Comment, Label, Directive, Code }

    private val codeKinds = setOf(LineKind.Label, LineKind.Directive, LineKind.Code)

    fun split(line: String): LineParts {
        val c = Assembler.commentStart(line)
        val head = if (c < 0) line else line.substring(0, c)
        val comment = if (c < 0) null else line.substring(c)
        val indentLen = head.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) head.length else it }
        val rest = head.substring(indentLen)
        val code = rest.trimEnd()
        return LineParts(head.substring(0, indentLen), code, if (comment == null) "" else rest.substring(code.length), comment)
    }

    fun kind(line: String): LineKind = kind(split(line))

    private fun kind(p: LineParts): LineKind = when {
        p.code.isEmpty() -> if (p.comment == null) LineKind.Blank else LineKind.Comment
        Assembler.LABEL_RE.containsMatchIn(p.code) -> LineKind.Label
        p.code.takeWhile { !it.isWhitespace() }.lowercase() in LEVEL_ZERO_DIRECTIVES -> LineKind.Directive
        else -> LineKind.Code
    }

    /** Column reached after [s], with a tab advancing to the next multiple of [INDENT]. */
    fun visualWidth(s: String): Int {
        var col = 0
        for (ch in s) col = if (ch == '\t') (col / INDENT + 1) * INDENT else col + 1
        return col
    }

    /** Indent for the line that Enter starts after [line]. */
    fun indentAfter(line: String): Int {
        val p = split(line)
        return when (kind(p)) {
            LineKind.Label, LineKind.Directive -> INDENT
            LineKind.Blank -> visualWidth(line)
            else -> visualWidth(p.indent)
        }
    }

    /** [line] with its indent set by its kind: labels and directives at 0, code at [codeIndent]. */
    fun placed(line: String, codeIndent: Int): String {
        val p = split(line)
        return when (kind(p)) {
            LineKind.Label, LineKind.Directive -> line.substring(p.indent.length)
            LineKind.Code -> " ".repeat(codeIndent) + line.substring(p.indent.length)
            else -> line
        }
    }

    /** Column for a `;` typed after the code on `lines[index]`, taken from the rest of its run. */
    fun commentColumnFor(lines: List<String>, index: Int): Int {
        fun inRun(i: Int) = kind(lines[i]) in codeKinds
        var first = index
        while (first > 0 && inRun(first - 1)) first--
        var last = index
        while (last < lines.lastIndex && inRun(last + 1)) last++
        val columns = (first..last).filter { it != index }.mapNotNull { i ->
            split(lines[i]).takeIf { it.comment != null && it.code.isNotEmpty() }?.let { visualWidth(it.indent + it.code + it.gap) }
        }
        val common = columns.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })?.key
        val p = split(lines[index])
        return maxOf(common ?: DEFAULT_COMMENT_COLUMN, visualWidth(p.indent + p.code) + 1)
    }

    /**
     * The whole program laid out: every line placed by its kind, full-line comments at the indent
     * of the code they introduce, trailing comments sharing one column per run of code lines.
     * Only whitespace changes.
     */
    fun format(text: String): String {
        val raw = text.split("\n")
        val lines = raw.map { it.removeSuffix("\r") }
        val parts = lines.map(::split)
        val kinds = parts.map(::kind)

        val indents = IntArray(lines.size)
        var next = 0 // indent of the next code line, scanning upwards
        for (i in lines.indices.reversed()) {
            indents[i] = when (kinds[i]) {
                LineKind.Label, LineKind.Directive, LineKind.Blank -> 0
                LineKind.Code -> INDENT
                LineKind.Comment -> next
            }
            if (kinds[i] in codeKinds) next = indents[i]
        }

        val out = Array(lines.size) { "" }
        var i = 0
        while (i < lines.size) {
            if (kinds[i] !in codeKinds) {
                if (kinds[i] == LineKind.Comment) out[i] = " ".repeat(indents[i]) + parts[i].comment!!.trimEnd()
                i++
                continue
            }
            var end = i
            while (end < lines.size && kinds[end] in codeKinds) end++
            val heads = (i until end).associateWith { " ".repeat(indents[it]) + parts[it].code }
            val commented = (i until end).filter { parts[it].comment != null }
            val codeEnd = commented.maxOfOrNull { visualWidth(heads.getValue(it)) } ?: 0
            // Where each comment lands if it keeps its gap, so comments move with re-indented code.
            val existing = commented.map { visualWidth(heads.getValue(it) + parts[it].gap) }.distinct()
            val column = existing.singleOrNull()?.takeIf { it > codeEnd } ?: maxOf(DEFAULT_COMMENT_COLUMN, codeEnd + 1)
            for (k in i until end) {
                val head = heads.getValue(k)
                val comment = parts[k].comment
                out[k] = if (comment == null) head
                    else head + " ".repeat(maxOf(1, column - visualWidth(head))) + comment.trimEnd()
            }
            i = end
        }
        return out.indices.joinToString("\n") { if (raw[it].endsWith("\r")) out[it] + "\r" else out[it] }
    }
}

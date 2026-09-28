package x86sim.disasm

import com.github.icedland.iced.x86.Code
import com.github.icedland.iced.x86.FlowControl
import com.github.icedland.iced.x86.Instruction
import com.github.icedland.iced.x86.OpKind
import com.github.icedland.iced.x86.dec.ByteArrayCodeReader
import com.github.icedland.iced.x86.dec.Decoder
import com.github.icedland.iced.x86.fmt.StringOutput
import com.github.icedland.iced.x86.fmt.SymbolResolver
import com.github.icedland.iced.x86.fmt.SymbolResult
import com.github.icedland.iced.x86.fmt.nasm.NasmFormatter
import x86sim.AppInfo

/** Turns a supported binary into a NASM-syntax [Listing]. Decoding is done by the iced-x86 library. */
object Disassembler {
    /** Detects and disassembles [bytes]; callers should [detect] first, since a rejected file throws. */
    fun listing(bytes: ByteArray, fileName: String, isCancelled: () -> Boolean): Listing =
        when (val d = detect(bytes, fileName)) {
            is Detection.Supported -> listing(d.image, fileName, isCancelled)
            is Detection.Rejected -> throw IllegalArgumentException(d.message)
        }

    /** One decoded instruction, or (when [instr] is null) one byte that isn't a valid instruction. */
    private class Item(val ip: Long, val length: Int, val instr: Instruction?)

    fun listing(image: BinaryImage, fileName: String, isCancelled: () -> Boolean): Listing {
        // Pass 1: decode every code section, noting instruction starts and direct branch/call targets.
        var count = 0
        val decoded = image.codeSections.map { sec ->
            val items = ArrayList<Item>()
            val reader = ByteArrayCodeReader(sec.bytes)
            val decoder = Decoder(64, reader, sec.address)
            while (reader.getPosition() < sec.bytes.size) {
                if (count++ % 1024 == 0 && isCancelled()) throw DisassemblyCancelled()
                val pos = reader.getPosition()
                val ins = decoder.decode()
                if (ins.getCode() == Code.INVALID) {
                    // Resynchronise one byte later: iced's length for bad bytes can swallow a real instruction.
                    items += Item(sec.address + pos, 1, null)
                    reader.setPosition(pos + 1)
                    decoder.setIP(sec.address + pos + 1)
                } else items += Item(ins.getIP(), ins.getLength(), ins)
            }
            sec to items
        }
        val starts = HashSet<Long>()
        val targets = sortedSetOf<Long>()
        for ((_, items) in decoded) for (it in items) {
            starts += it.ip
            val ins = it.instr ?: continue
            val fc = ins.getFlowControl()
            val direct = ins.getOp0Kind() in NEAR_BRANCH_KINDS
            if (direct && (fc == FlowControl.UNCONDITIONAL_BRANCH || fc == FlowControl.CONDITIONAL_BRANCH || fc == FlowControl.CALL))
                targets += ins.getNearBranchTarget()
        }

        // Labels: file symbols, then imports, then the entry point, then generated loc_ names.
        fun labelable(a: Long) = a in starts || image.dataSections.any { a in it }
        val labels = HashMap<Long, String>()
        val used = HashSet<String>()
        fun name(a: Long, raw: String) {
            if (a in labels || !labelable(a)) return
            val base = sanitise(raw)
            var n = base
            var k = 2
            while (!used.add(n)) n = "${base}_${k++}"
            labels[a] = n
        }
        image.symbols.toSortedMap().forEach { (a, n) -> name(a, n) }
        image.imports.toSortedMap().forEach { (a, n) -> name(a, n) }
        image.entry?.let { name(it, "_start") }
        targets.forEach { name(it, "loc_${it.toString(16)}") }

        // Pass 2: write the listing.
        val out = ListingBuilder()
        out.add("; Disassembly of $fileName (${image.summary})", 0)
        out.add("; Entry point: " + (image.entry?.let { "0x${it.toString(16)}" } ?: "none"), 0)
        image.fatSliceNote?.let { out.add("; $it", 0) }
        for (s in image.codeSections) out.add("; Code: ${range(s)}", 0)
        for (s in image.dataSections) out.add("; Data: ${range(s)}", 0)
        out.add("; This is a read-only listing made from a compiled program. It may not assemble or run in ${AppInfo.NAME}.", 0)
        out.add("; Decoded with iced-x86 (MIT license).", 0)
        out.add("", 0)

        val formatter = NasmFormatter(SymbolResolver { _, _, _, address, _ -> labels[address]?.let { SymbolResult(address, it) } })
        formatter.getOptions().apply {
            setUppercaseMnemonics(false); setUppercaseRegisters(false); setUppercaseKeywords(false)
            setUppercasePrefixes(false); setUppercaseHex(false)
            setSpaceAfterOperandSeparator(true)
            setHexPrefix("0x"); setHexSuffix("")
            setAddLeadingZeroToHexNumbers(false); setBranchLeadingZeros(false)
            setShowBranchSize(false)
            setFirstOperandCharIndex(8)
        }
        val text = StringOutput()
        var shown = 0
        var invalid = 0
        for ((sec, items) in decoded) {
            out.section(sec.name, sec.address)
            for ((i, it) in items.withIndex()) {
                if (out.truncated) break
                if (i % 1024 == 0 && isCancelled()) throw DisassemblyCancelled()
                labels[it.ip]?.let { l -> out.label(l, it.ip) }
                val off = (it.ip - sec.address).toInt()
                val bytes = (off until off + it.length).joinToString(" ") { b -> "%02x".format(sec.bytes[b]) }
                val ins = it.instr
                if (ins == null) {
                    out.commented("db      0x$bytes", "${it.ip.toString(16)}: $bytes  (not a valid instruction)", it.ip)
                    if (!out.truncated) invalid++
                } else {
                    formatter.format(ins, text)
                    out.commented(text.toStringAndReset(), "${it.ip.toString(16)}: $bytes", it.ip)
                    if (!out.truncated) shown++
                }
            }
        }
        for (sec in image.dataSections) {
            if (out.truncated) break
            out.section(sec.name, sec.address)
            val limit = minOf(sec.bytes.size, Listing.MAX_DATA_BYTES)
            var i = 0
            while (i < limit && !out.truncated) {
                val a = sec.address + i
                labels[a]?.let { out.label(it, a) }
                var j = i + 1
                while (j < limit && j - i < 16 && (sec.address + j) !in labels) j++
                val row = (i until j).joinToString(", ") { b -> "0x%02x".format(sec.bytes[b]) }
                out.commented("db      $row", a.toString(16), a)
                i = j
            }
            if (sec.bytes.size > limit) out.add("        ; … ${sec.bytes.size - limit} more bytes not shown", sec.address + limit)
        }
        return Listing(out.toString(), image.summary, shown, invalid, out.truncated)
    }

    private val NEAR_BRANCH_KINDS = setOf(OpKind.NEAR_BRANCH16, OpKind.NEAR_BRANCH32, OpKind.NEAR_BRANCH64)

    private fun range(s: Section) = "${s.name} 0x${s.address.toString(16)}-0x${s.end.toString(16)} (${s.bytes.size} bytes)"

    /** Makes a symbol usable as a NASM label: odd characters become `_`, and it can't start with a digit or `.`. */
    internal fun sanitise(raw: String): String {
        val s = raw.replace(Regex("[^A-Za-z0-9_.$@?#~]"), "_").ifEmpty { "_" }
        return if (s[0].isDigit() || s[0] == '.') "_$s" else s
    }
}

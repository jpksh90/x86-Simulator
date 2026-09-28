package x86sim.disasm

/** Reads a thin Mach-O 64 x86-64 image starting at [base] in the file (header already checked by [detect]). */
object MachOReader {
    private const val LC_SYMTAB = 0x2L
    private const val LC_THREAD = 0x4L
    private const val LC_UNIXTHREAD = 0x5L
    private const val LC_DYSYMTAB = 0xBL
    private const val LC_SEGMENT_64 = 0x19L
    private const val LC_MAIN = 0x80000028L
    private const val S_ATTR_SOME_INSTRUCTIONS = 0x400L
    private const val S_ATTR_PURE_INSTRUCTIONS = 0x80000000L
    private const val S_SYMBOL_STUBS = 8
    private val ZEROFILL = setOf(0x1, 0xC, 0x12)

    private class Stubs(val addr: Long, val size: Long, val firstIndirect: Long, val stubSize: Long)

    fun read(r: ByteReader, base: Long = 0, note: String? = null): BinaryImage {
        val filetype = r.u32le(base + 12)
        val ncmds = r.u32le(base + 16)
        if (ncmds > 100_000) throw DamagedException("it claims $ncmds load commands")

        val code = mutableListOf<Section>()
        val data = mutableListOf<Section>()
        val stubs = mutableListOf<Stubs>()
        val imports = mutableMapOf<Long, String>()
        var textVm = 0L
        var mainOff: Long? = null
        var threadRip: Long? = null
        var symtab: LongArray? = null // symoff, nsyms, stroff
        var indirect: Long? = null

        var off = base + 32
        repeat(ncmds.toInt()) {
            val what = "the load commands"
            val cmd = r.u32le(off, what)
            val size = r.u32le(off + 4, what)
            if (size < 8) throw DamagedException("a load command has an invalid size")
            when (cmd) {
                LC_SEGMENT_64 -> {
                    val segName = r.cString(off + 8, 16)
                    if (segName == "__TEXT") textVm = r.u64le(off + 24, what)
                    for (i in 0 until r.u32le(off + 64, what)) {
                        val s = off + 72 + 80 * i
                        val name = "${r.cString(s + 16, 16)},${r.cString(s, 16)}"
                        val addr = r.u64le(s + 32, what); val len = r.u64le(s + 40, what)
                        val fileOff = r.u32le(s + 48, what); val flags = r.u32le(s + 64, what)
                        if ((flags and 0xff).toInt() in ZEROFILL || len == 0L) continue
                        val sec = Section(name, addr, r.slice(base + fileOff, len, "section $name"))
                        if (flags and (S_ATTR_PURE_INSTRUCTIONS or S_ATTR_SOME_INSTRUCTIONS) != 0L) code += sec else data += sec
                        if ((flags and 0xff).toInt() == S_SYMBOL_STUBS)
                            stubs += Stubs(addr, len, r.u32le(s + 68, what), r.u32le(s + 72, what))
                    }
                }
                LC_MAIN -> mainOff = r.u64le(off + 8, what)
                LC_UNIXTHREAD, LC_THREAD -> if (r.u32le(off + 8, what) == 4L) threadRip = r.u64le(off + 16 + 16 * 8, what)
                LC_SYMTAB -> symtab = longArrayOf(r.u32le(off + 8, what), r.u32le(off + 12, what), r.u32le(off + 16, what))
                LC_DYSYMTAB -> indirect = r.u32le(off + 56, what)
            }
            off += size
        }

        val symbols = mutableMapOf<Long, String>()
        val names = mutableMapOf<Long, String>() // symbol index -> name, for stubs
        symtab?.let { (symoff, nsyms, stroff) ->
            runCatching {
                for (i in 0 until nsyms) {
                    val e = base + symoff + 16 * i
                    val name = r.cString(base + stroff + r.u32le(e), 1024)
                    names[i] = name
                    val type = r.u8(e + 4)
                    val value = r.u64le(e + 8)
                    val isStab = type and 0xE0 != 0
                    val inSection = type and 0x0E == 0x0E
                    if (!isStab && inSection && value != 0L && name.isNotEmpty()) symbols.putIfAbsent(value, name)
                }
            }
        }
        indirect?.let { indOff ->
            runCatching {
                for (s in stubs) {
                    if (s.stubSize == 0L) continue
                    for (j in 0 until s.size / s.stubSize) {
                        val idx = r.u32le(base + indOff + 4 * (s.firstIndirect + j))
                        if (idx and 0xC0000000L != 0L) continue // local or absolute: no name
                        names[idx]?.takeIf { it.isNotEmpty() }?.let { imports[s.addr + j * s.stubSize] = "$it@stub" }
                    }
                }
            }
        }

        val kind = when (filetype) {
            1L -> "object file"
            2L -> "executable"
            4L -> "core dump"
            6L -> "dynamic library"
            7L -> "dynamic linker"
            8L -> "bundle"
            else -> "file"
        }
        val entry = mainOff?.let { textVm + it } ?: threadRip
        return BinaryImage(
            BinaryFormat.MACHO64, kind, note, entry,
            code.sortedBy { it.address }, data.sortedBy { it.address }, symbols, imports,
        ).also { it.validate() }
    }

}

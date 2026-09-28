package x86sim.disasm

/** Reads an ELF64 little-endian x86-64 file (header already checked by [detect]). */
object ElfReader {
    private class Sh(val name: String, val type: Long, val flags: Long, val addr: Long, val offset: Long, val size: Long, val link: Long)

    private const val SHT_PROGBITS = 1L
    private const val SHT_SYMTAB = 2L
    private const val SHT_RELA = 4L
    private const val SHT_NOBITS = 8L
    private const val SHT_DYNSYM = 11L
    private const val SHF_ALLOC = 0x2L
    private const val SHF_EXECINSTR = 0x4L

    fun read(r: ByteReader): BinaryImage {
        val type = r.u16le(16)
        val entry = r.u64le(24)
        val phoff = r.u64le(32)
        val shoff = r.u64le(40)
        val phnum = r.u16le(56)
        val shentsize = r.u16le(58)
        val shnum = r.u16le(60)
        val shstrndx = r.u16le(62)

        val hasInterp = (0 until phnum).any { r.u32le(phoff + 56L * it, "the program header table") == 3L }
        val kind = when (type) {
            1 -> "object file (sections placed one after another from address 0)"
            2 -> "executable"
            3 -> if (hasInterp) "position-independent executable" else "shared library"
            4 -> "core dump"
            else -> "file"
        }

        val code = mutableListOf<Section>()
        val data = mutableListOf<Section>()
        val symbols = mutableMapOf<Long, String>()
        val imports = mutableMapOf<Long, String>()

        if (shoff != 0L && shnum != 0) {
            if (shentsize != 64) throw DamagedException("its section headers have an unexpected size")
            val raw = (0 until shnum).map { i ->
                val o = shoff + 64L * i
                val what = "the section header table"
                listOf(r.u32le(o, what), r.u32le(o + 4, what), r.u64le(o + 8, what), r.u64le(o + 16, what),
                    r.u64le(o + 24, what), r.u64le(o + 32, what), r.u32le(o + 40, what))
            }
            val strOff = raw.getOrNull(shstrndx)?.get(4) ?: throw DamagedException("its section name table is missing")
            val shs = raw.map { Sh(r.cString(strOff + it[0], 256, "a section name"), it[1], it[2], it[3], it[4], it[5], it[6]) }

            // In an object file every section starts at 0; lay them out one after another so
            // addresses (and the labels keyed by them) don't collide.
            val base = LongArray(shs.size) { shs[it].addr }
            if (type == 1) {
                var next = 0L
                for ((i, s) in shs.withIndex()) if (s.flags and SHF_ALLOC != 0L && s.size > 0) {
                    base[i] = (next + 15) / 16 * 16
                    next = base[i] + s.size
                }
            }
            for ((i, s) in shs.withIndex()) {
                if (s.type == SHT_NOBITS || s.size == 0L) continue
                if (s.flags and SHF_EXECINSTR != 0L)
                    code += Section(s.name, base[i], r.slice(s.offset, s.size, "section ${s.name}"))
                else if (s.flags and SHF_ALLOC != 0L && s.type == SHT_PROGBITS)
                    data += Section(s.name, base[i], r.slice(s.offset, s.size, "section ${s.name}"))
            }

            fun symName(tab: Sh, index: Long): Pair<String, Long>? {
                val o = tab.offset + 24 * index
                val strtab = shs.getOrNull(tab.link.toInt()) ?: return null
                val name = r.cString(strtab.offset + r.u32le(o), 1024)
                return name to o
            }

            val symtab = shs.firstOrNull { it.type == SHT_SYMTAB } ?: shs.firstOrNull { it.type == SHT_DYNSYM }
            if (symtab != null) runCatching {
                for (i in 1 until symtab.size / 24) {
                    val (name, o) = symName(symtab, i) ?: break
                    val symType = r.u8(o + 4) and 0xf
                    val shndx = r.u16le(o + 6)
                    val value = r.u64le(o + 8)
                    if (name.isEmpty() || symType > 2 || shndx == 0 || shndx >= shs.size) continue
                    if (type == 1) symbols.putIfAbsent(base[shndx] + value, name)
                    else if (value != 0L) symbols.putIfAbsent(value, name)
                }
            }

            // Library calls go through PLT stubs; name each stub after the symbol its relocation binds.
            runCatching {
                val rela = shs.first { it.type == SHT_RELA && it.name == ".rela.plt" }
                val dynsym = shs[rela.link.toInt()]
                val pltSec = shs.firstOrNull { it.name == ".plt.sec" }
                val plt = pltSec ?: shs.first { it.name == ".plt" }
                val first = if (pltSec != null) plt.addr else plt.addr + 16 // .plt starts with a header stub
                for (i in 0 until rela.size / 24) {
                    val sym = r.u64le(rela.offset + 24 * i + 8) ushr 32
                    val (name, _) = symName(dynsym, sym) ?: continue
                    if (name.isNotEmpty()) imports[first + 16 * i] = "$name@plt"
                }
            }
        } else {
            // No section headers (stripped): decode the executable PT_LOAD segments instead.
            for (i in 0 until phnum) {
                val o = phoff + 56L * i
                val pType = r.u32le(o); val pFlags = r.u32le(o + 4)
                if (pType == 1L && pFlags and 1L != 0L) {
                    val size = r.u64le(o + 32)
                    if (size > 0) code += Section("LOAD", r.u64le(o + 16), r.slice(r.u64le(o + 8), size, "a program segment"))
                }
            }
        }

        return BinaryImage(
            BinaryFormat.ELF64, kind, null, if (type == 1) null else entry,
            code.sortedBy { it.address }, data.sortedBy { it.address }, symbols, imports,
        ).also { it.validate() }
    }
}

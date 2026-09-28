package x86sim.disasm

/** Reads a PE32+ x86-64 file (MZ/PE headers and machine already checked by [detect]). */
object PeReader {
    private const val IMAGE_SCN_CNT_CODE = 0x20L
    private const val IMAGE_SCN_CNT_INITIALIZED_DATA = 0x40L
    private const val IMAGE_SCN_MEM_EXECUTE = 0x20000000L
    private const val IMAGE_FILE_DLL = 0x2000

    private class Sh(val name: String, val va: Long, val vsize: Long, val rawPtr: Long, val rawSize: Long, val chars: Long)

    fun read(r: ByteReader): BinaryImage {
        val coff = r.u32le(0x3C) + 4
        val nsec = r.u16le(coff + 2)
        val optSize = r.u16le(coff + 16)
        val chars = r.u16le(coff + 18)
        val opt = coff + 20
        val entryRva = r.u32le(opt + 16)
        val imageBase = r.u64le(opt + 24)
        val nDirs = r.u32le(opt + 108)
        fun dir(i: Int): Pair<Long, Long> =
            if (i < nDirs) r.u32le(opt + 112 + 8L * i) to r.u32le(opt + 116 + 8L * i) else 0L to 0L

        val shs = (0 until nsec).map { i ->
            val sh = opt + optSize + 40L * i
            val what = "the section table"
            Sh(r.cString(sh, 8, what), r.u32le(sh + 12, what), r.u32le(sh + 8, what), r.u32le(sh + 20, what),
                r.u32le(sh + 16, what), r.u32le(sh + 36, what))
        }

        val code = mutableListOf<Section>()
        val data = mutableListOf<Section>()
        for (s in shs) {
            val len = if (s.vsize == 0L) s.rawSize else minOf(s.vsize, s.rawSize)
            if (len == 0L) continue
            val sec = Section(s.name, imageBase + s.va, r.slice(s.rawPtr, len, "section ${s.name}"))
            if (s.chars and (IMAGE_SCN_CNT_CODE or IMAGE_SCN_MEM_EXECUTE) != 0L) code += sec
            else if (s.chars and IMAGE_SCN_CNT_INITIALIZED_DATA != 0L) data += sec
        }

        fun off(rva: Long): Long {
            val s = shs.firstOrNull { rva >= it.va && rva < it.va + maxOf(it.vsize, it.rawSize) }
                ?: throw DamagedException("address 0x${rva.toString(16)} is not in any section")
            return s.rawPtr + (rva - s.va)
        }

        val symbols = mutableMapOf<Long, String>()
        val (expRva, expSize) = dir(0)
        if (expRva != 0L) runCatching {
            val e = off(expRva)
            val nFuncs = r.u32le(e + 20); val nNames = r.u32le(e + 24)
            val funcs = off(r.u32le(e + 28)); val names = off(r.u32le(e + 32)); val ords = off(r.u32le(e + 36))
            for (i in 0 until nNames) {
                val ord = r.u16le(ords + 2 * i)
                if (ord >= nFuncs) continue
                val fRva = r.u32le(funcs + 4L * ord)
                if (fRva >= expRva && fRva < expRva + expSize) continue // forwarded to another DLL
                symbols.putIfAbsent(imageBase + fRva, r.cString(off(r.u32le(names + 4 * i)), 1024))
            }
        }

        val imports = mutableMapOf<Long, String>()
        val (impRva, _) = dir(1)
        if (impRva != 0L) runCatching {
            var d = off(impRva)
            while (true) {
                val ilt = r.u32le(d); val nameRva = r.u32le(d + 12); val iat = r.u32le(d + 16)
                if (nameRva == 0L && iat == 0L) break
                val dll = r.cString(off(nameRva), 256)
                val lookup = off(if (ilt != 0L) ilt else iat)
                var i = 0L
                while (true) {
                    val v = r.u64le(lookup + 8 * i)
                    if (v == 0L) break
                    val fn = if (v < 0) "#${v and 0xffff}" else r.cString(off(v and 0x7fffffff) + 2, 1024)
                    imports[imageBase + iat + 8 * i] = "__imp_$dll!$fn"
                    i++
                }
                d += 20
            }
        }

        return BinaryImage(
            BinaryFormat.PE32PLUS, if (chars and IMAGE_FILE_DLL != 0) "DLL" else "executable", null,
            if (entryRva == 0L) null else imageBase + entryRva,
            code.sortedBy { it.address }, data.sortedBy { it.address }, symbols, imports,
        ).also { it.validate() }
    }
}

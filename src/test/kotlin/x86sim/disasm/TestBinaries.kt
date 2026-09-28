package x86sim.disasm

/** Builds minimal but valid ELF64, Mach-O and PE32+ images (and broken variants) for the tests. */
object TestBinaries {
    fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /**
     * 401000 mov eax,1 / 401005 mov edi,1 / 40100a call 401012 / 40100f ud2 /
     * 401011 06 (invalid in 64-bit mode) / 401012 syscall / 401014 ret
     */
    val HELLO_CODE = hex("b8 01 00 00 00 bf 01 00 00 00 e8 03 00 00 00 0f 0b 06 0f 05 c3")

    /** Growable little-endian buffer with absolute-offset writes. */
    class Buf(initial: Int = 0) {
        var bytes = ByteArray(initial); private set
        val size get() = bytes.size
        private fun ensure(n: Int) { if (n > bytes.size) bytes = bytes.copyOf(n) }
        fun u8(off: Int, v: Int) { ensure(off + 1); bytes[off] = v.toByte() }
        fun u16(off: Int, v: Int) { for (i in 0..1) u8(off + i, v ushr (8 * i)) }
        fun u32(off: Int, v: Long) { for (i in 0..3) u8(off + i, (v ushr (8 * i)).toInt()) }
        fun u64(off: Int, v: Long) { for (i in 0..7) u8(off + i, (v ushr (8 * i)).toInt()) }
        fun u32be(off: Int, v: Long) { for (i in 0..3) u8(off + i, (v ushr (8 * (3 - i))).toInt()) }
        fun put(off: Int, b: ByteArray) { ensure(off + b.size); b.copyInto(bytes, off) }
        fun str(off: Int, s: String, width: Int = s.length + 1) { put(off, s.toByteArray(Charsets.ISO_8859_1).copyOf(width)) }
        fun align(a: Int): Int = (size + a - 1) / a * a
    }

    /** A string table: index of each added name. */
    private class StrTab {
        val buf = Buf(1)
        fun add(s: String): Int { val at = buf.size; buf.str(at, s); return at }
    }

    // ---------------------------------------------------------------- ELF

    private class ElfSec(
        val name: String, val type: Int, val flags: Long, val addr: Long, val data: ByteArray,
        var link: Int = 0, var info: Int = 0, val entsize: Long = 0, val nobitsSize: Long = 0,
    )

    /**
     * An ELF64 file with `.text` at [codeAddr]; optional `.data`, `.bss`, `.symtab` and a PLT with
     * `.dynsym`/`.rela.plt` naming [pltImports].
     */
    fun elf64(
        code: ByteArray = HELLO_CODE,
        entry: Long = 0x401000,
        codeAddr: Long = 0x401000,
        machine: Int = 0x3E,
        type: Int = 2,
        withSymbols: Map<String, Long> = emptyMap(),
        withSectionHeaders: Boolean = true,
        data: ByteArray? = null,
        dataAddr: Long = 0x402000,
        withBss: Boolean = false,
        pltImports: List<String> = emptyList(),
        bigEndian: Boolean = false,
        /** Section header index for each symbol (default 1 = `.text`). */
        symShndx: Map<String, Int> = emptyMap(),
    ): ByteArray {
        val secs = mutableListOf(ElfSec(".text", 1, 0x6, codeAddr, code))
        if (data != null) secs += ElfSec(".data", 1, 0x3, dataAddr, data)
        if (withBss) secs += ElfSec(".bss", 8, 0x3, dataAddr + 0x1000, ByteArray(0), nobitsSize = 64)
        if (pltImports.isNotEmpty()) {
            val pltAddr = codeAddr + 0x800
            val plt = ByteArray(16 * (pltImports.size + 1)) { 0x90.toByte() } // stub bodies don't matter
            val dynstr = StrTab()
            val dynsym = Buf(24)
            pltImports.forEachIndexed { i, n ->
                val o = 24 * (i + 1)
                dynsym.u32(o, dynstr.add(n).toLong()); dynsym.u8(o + 4, 0x12)
                dynsym.u64(o + 8, 0); dynsym.u64(o + 16, 0)
            }
            val rela = Buf()
            pltImports.forEachIndexed { i, _ ->
                rela.u64(24 * i, dataAddr + 0x100 + 8L * i); rela.u64(24 * i + 8, ((i + 1).toLong() shl 32) or 7)
                rela.u64(24 * i + 16, 0)
            }
            secs += ElfSec(".plt", 1, 0x6, pltAddr, plt)
            val dynsymIdx = secs.size + 1 // +1 for the null section
            secs += ElfSec(".dynsym", 11, 0x2, 0, dynsym.bytes, link = dynsymIdx + 1, info = 1, entsize = 24)
            secs += ElfSec(".dynstr", 3, 0x2, 0, dynstr.buf.bytes)
            secs += ElfSec(".rela.plt", 4, 0x42, 0, rela.bytes, link = dynsymIdx, entsize = 24)
        }
        if (withSymbols.isNotEmpty()) {
            val strtab = StrTab()
            val symtab = Buf(24)
            withSymbols.entries.forEachIndexed { i, (n, v) ->
                val o = 24 * (i + 1)
                symtab.u32(o, strtab.add(n).toLong()); symtab.u8(o + 4, 0x12); symtab.u16(o + 6, symShndx[n] ?: 1)
                symtab.u64(o + 8, v); symtab.u64(o + 16, 0)
            }
            val symIdx = secs.size + 1
            secs += ElfSec(".symtab", 2, 0, 0, symtab.bytes, link = symIdx + 1, info = 1, entsize = 24)
            secs += ElfSec(".strtab", 3, 0, 0, strtab.buf.bytes)
        }
        val shstr = StrTab()
        val nameIdx = secs.map { shstr.add(it.name) }
        val shstrName = shstr.add(".shstrtab")

        val f = Buf(64 + 56)
        val offsets = secs.map { s -> val at = f.align(16); f.put(at, s.data); at }
        val shstrOff = f.align(16); f.put(shstrOff, shstr.buf.bytes)
        val shoff = f.align(8)

        f.put(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, if (bigEndian) 2 else 1, 1))
        f.u16(16, type); f.u16(18, machine); f.u32(20, 1); f.u64(24, entry)
        f.u64(32, 64); f.u64(40, if (withSectionHeaders) shoff.toLong() else 0)
        f.u16(52, 64); f.u16(54, 56); f.u16(56, 1); f.u16(58, 64)
        f.u16(60, if (withSectionHeaders) secs.size + 2 else 0); f.u16(62, if (withSectionHeaders) secs.size + 1 else 0)
        // one PT_LOAD R+X covering .text
        f.u32(64, 1); f.u32(68, 5); f.u64(72, offsets[0].toLong()); f.u64(80, codeAddr); f.u64(88, codeAddr)
        f.u64(96, code.size.toLong()); f.u64(104, code.size.toLong()); f.u64(112, 0x1000)

        if (withSectionHeaders) {
            var o = shoff + 64 // index 0 is the null section
            secs.forEachIndexed { i, s ->
                f.u32(o, nameIdx[i].toLong()); f.u32(o + 4, s.type.toLong()); f.u64(o + 8, s.flags)
                f.u64(o + 16, s.addr); f.u64(o + 24, offsets[i].toLong())
                f.u64(o + 32, if (s.type == 8) s.nobitsSize else s.data.size.toLong())
                f.u32(o + 40, s.link.toLong()); f.u32(o + 44, s.info.toLong()); f.u64(o + 48, 16); f.u64(o + 56, s.entsize)
                o += 64
            }
            f.u32(o, shstrName.toLong()); f.u32(o + 4, 3); f.u64(o + 24, shstrOff.toLong())
            f.u64(o + 32, shstr.buf.size.toLong()); f.u64(o + 48, 1)
            f.u8(o + 63, 0) // make sure the table reaches its full length
        }
        return f.bytes
    }

    /** Just a valid 32-bit ELF header. */
    fun elf32(machine: Int = 3): ByteArray {
        val f = Buf(52)
        f.put(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
        f.u16(16, 2); f.u16(18, machine); f.u32(20, 1)
        return f.bytes
    }

    // ---------------------------------------------------------------- Mach-O

    const val MACHO_BASE = 0x100000000L
    const val MACHO_CODE_OFF = 0x400

    /**
     * A thin Mach-O 64 with `__TEXT,__text` at file offset 0x400 (address 0x100000400), entry via
     * LC_MAIN (or LC_UNIXTHREAD), optional `__DATA,__data` and LC_SYMTAB.
     */
    fun machO64(
        code: ByteArray = HELLO_CODE,
        cputype: Int = 0x01000007,
        filetype: Int = 2,
        unixThread: Boolean = false,
        symbols: Map<String, Long> = emptyMap(),
        stabs: Map<String, Long> = emptyMap(),
        data: ByteArray? = null,
    ): ByteArray {
        val f = Buf(32)
        val cmds = Buf()
        var ncmds = 0
        fun seg(name: String, vmaddr: Long, fileoff: Long, sects: List<Triple<String, Long, Pair<Int, Int>>>, sectFlags: Long) {
            // sects: (sectname, addr, (offset, size))
            val at = cmds.size
            cmds.u32(at, 0x19); cmds.u32(at + 4, 72L + 80 * sects.size); cmds.str(at + 8, name, 16)
            val size = sects.maxOf { it.third.first + it.third.second } - fileoff
            cmds.u64(at + 24, vmaddr); cmds.u64(at + 32, 0x4000); cmds.u64(at + 40, fileoff); cmds.u64(at + 48, size)
            cmds.u32(at + 56, 5); cmds.u32(at + 60, 5); cmds.u32(at + 64, sects.size.toLong())
            sects.forEachIndexed { i, (sn, addr, os) ->
                val s = at + 72 + 80 * i
                cmds.str(s, sn, 16); cmds.str(s + 16, name, 16); cmds.u64(s + 32, addr); cmds.u64(s + 40, os.second.toLong())
                cmds.u32(s + 48, os.first.toLong()); cmds.u32(s + 64, sectFlags); cmds.u32(s + 76, 0)
            }
            cmds.u8(at + 71 + 80 * sects.size, 0) // pad to cmdsize even with no sections
            ncmds++
        }
        val dataOff = MACHO_CODE_OFF + 0x400
        seg("__TEXT", MACHO_BASE, 0, listOf(Triple("__text", MACHO_BASE + MACHO_CODE_OFF, MACHO_CODE_OFF to code.size)), 0x80000400)
        if (data != null) seg("__DATA", MACHO_BASE + 0x4000, dataOff.toLong(), listOf(Triple("__data", MACHO_BASE + 0x4000, dataOff to data.size)), 0)
        if (unixThread) {
            val at = cmds.size
            cmds.u32(at, 0x5); cmds.u32(at + 4, 184); cmds.u32(at + 8, 4); cmds.u32(at + 12, 42)
            cmds.u64(at + 16 + 16 * 8, MACHO_BASE + MACHO_CODE_OFF)
            cmds.u8(at + 183, 0)
        } else {
            val at = cmds.size
            cmds.u32(at, 0x80000028); cmds.u32(at + 4, 24); cmds.u64(at + 8, MACHO_CODE_OFF.toLong()); cmds.u64(at + 16, 0)
        }
        ncmds++
        val allSyms = symbols.map { Triple(it.key, it.value, 0x0f) } + stabs.map { Triple(it.key, it.value, 0x24) }
        val symOff = dataOff + 0x400
        if (allSyms.isNotEmpty()) {
            val strtab = StrTab()
            allSyms.forEachIndexed { i, (n, v, t) ->
                val o = symOff + 16 * i
                f.u32(o, strtab.add(n).toLong()); f.u8(o + 4, t); f.u8(o + 5, 1); f.u64(o + 8, v)
            }
            val strOff = symOff + 16 * allSyms.size
            f.put(strOff, strtab.buf.bytes)
            val at = cmds.size
            cmds.u32(at, 0x2); cmds.u32(at + 4, 24); cmds.u32(at + 8, symOff.toLong()); cmds.u32(at + 12, allSyms.size.toLong())
            cmds.u32(at + 16, strOff.toLong()); cmds.u32(at + 20, strtab.buf.size.toLong())
            ncmds++
        }
        f.u32(0, 0xFEEDFACFL); f.u32(4, cputype.toLong()); f.u32(8, 3); f.u32(12, filetype.toLong())
        f.u32(16, ncmds.toLong()); f.u32(20, cmds.size.toLong())
        f.put(32, cmds.bytes)
        f.put(MACHO_CODE_OFF, code)
        if (data != null) f.put(dataOff, data)
        return f.bytes
    }

    /** A 32-bit Mach-O header. */
    fun machO32(cputype: Int = 7): ByteArray {
        val f = Buf(28)
        f.u32(0, 0xFEEDFACEL); f.u32(4, cputype.toLong()); f.u32(12, 2)
        return f.bytes
    }

    /** A universal binary holding [slices] (cputype to image), big-endian header. */
    fun fat(vararg slices: Pair<Int, ByteArray>): ByteArray {
        val f = Buf(8 + 20 * slices.size)
        f.u32be(0, 0xCAFEBABEL); f.u32be(4, slices.size.toLong())
        var off = 0x1000
        slices.forEachIndexed { i, (cpu, img) ->
            val a = 8 + 20 * i
            f.u32be(a, cpu.toLong()); f.u32be(a + 4, 3); f.u32be(a + 8, off.toLong()); f.u32be(a + 12, img.size.toLong()); f.u32be(a + 16, 12)
            f.put(off, img)
            off = (off + img.size + 0xfff) / 0x1000 * 0x1000
        }
        return f.bytes
    }

    // ---------------------------------------------------------------- PE

    const val PE_IMAGE_BASE = 0x140000000L

    /**
     * A PE32+ file: `.text` at RVA 0x1000 (file 0x400), `.rdata` at RVA 0x2000 (file 0x600) holding
     * the export and import tables, optional `.data` at RVA 0x3000.
     */
    fun pe64(
        code: ByteArray = HELLO_CODE,
        imageBase: Long = PE_IMAGE_BASE,
        entryRva: Long = 0x1000,
        machine: Int = 0x8664,
        optMagic: Int = 0x20B,
        dll: Boolean = false,
        exports: Map<String, Long> = emptyMap(),
        imports: Map<String, List<String>> = emptyMap(),
        data: ByteArray? = null,
    ): ByteArray {
        val rdataRva = 0x2000
        val rd = Buf(16)
        fun rva(off: Int) = (rdataRva + off).toLong()
        var exportDir = 0 to 0
        if (exports.isNotEmpty()) {
            val names = exports.keys.toList()
            val dirAt = rd.align(8); rd.u8(dirAt + 39, 0)
            val funcs = rd.align(8); names.forEachIndexed { i, n -> rd.u32(funcs + 4 * i, exports.getValue(n)) }
            val nameRvas = rd.align(8); rd.u8(nameRvas + 4 * names.size - 1, 0)
            val ords = rd.align(8); names.indices.forEach { rd.u16(ords + 2 * it, it) }
            names.forEachIndexed { i, n -> val at = rd.align(2); rd.str(at, n); rd.u32(nameRvas + 4 * i, rva(at)) }
            val dllName = rd.align(2); rd.str(dllName, "test.dll")
            rd.u32(dirAt + 12, rva(dllName)); rd.u32(dirAt + 16, 1); rd.u32(dirAt + 20, names.size.toLong())
            rd.u32(dirAt + 24, names.size.toLong()); rd.u32(dirAt + 28, rva(funcs)); rd.u32(dirAt + 32, rva(nameRvas))
            rd.u32(dirAt + 36, rva(ords))
            exportDir = dirAt to 40
        }
        var importDir = 0 to 0
        if (imports.isNotEmpty()) {
            val descAt = rd.align(8)
            rd.u8(descAt + 20 * (imports.size + 1) - 1, 0)
            imports.entries.forEachIndexed { d, (dll, funcs) ->
                val ilt = rd.align(8); rd.u8(ilt + 8 * (funcs.size + 1) - 1, 0)
                val iat = rd.align(8); rd.u8(iat + 8 * (funcs.size + 1) - 1, 0)
                funcs.forEachIndexed { i, fn ->
                    val v = if (fn.startsWith("#")) (1L shl 63) or fn.drop(1).toLong()
                        else { val hn = rd.align(2); rd.u16(hn, 0); rd.str(hn + 2, fn); rva(hn) }
                    rd.u64(ilt + 8 * i, v); rd.u64(iat + 8 * i, v)
                }
                val nm = rd.align(2); rd.str(nm, dll)
                val o = descAt + 20 * d
                rd.u32(o, rva(ilt)); rd.u32(o + 12, rva(nm)); rd.u32(o + 16, rva(iat))
            }
            importDir = descAt to 20 * (imports.size + 1)
        }

        data class Sec(val name: String, val rva: Int, val raw: Int, val bytes: ByteArray, val chars: Long)
        val secs = mutableListOf(Sec(".text", 0x1000, 0x400, code, 0x60000020))
        secs += Sec(".rdata", rdataRva, 0x600, rd.bytes, 0x40000040)
        if (data != null) secs += Sec(".data", 0x3000, 0x600 + ((rd.size + 0x1ff) / 0x200) * 0x200, data, 0xC0000040)

        val f = Buf(0x400)
        f.u8(0, 'M'.code); f.u8(1, 'Z'.code); f.u32(0x3C, 0x80)
        val pe = 0x80
        f.put(pe, byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0))
        val coff = pe + 4
        val optSize = if (optMagic == 0x20B) 240 else 224
        f.u16(coff, machine); f.u16(coff + 2, secs.size); f.u16(coff + 16, optSize)
        f.u16(coff + 18, 0x22 or (if (dll) 0x2000 else 0))
        val opt = coff + 20
        f.u16(opt, optMagic)
        if (optMagic == 0x20B) {
            f.u32(opt + 16, entryRva); f.u32(opt + 20, 0x1000); f.u64(opt + 24, imageBase)
            f.u32(opt + 32, 0x1000); f.u32(opt + 36, 0x200); f.u32(opt + 108, 16)
            val dd = opt + 112
            if (exportDir.second > 0) { f.u32(dd, rva(exportDir.first)); f.u32(dd + 4, exportDir.second.toLong()) }
            if (importDir.second > 0) { f.u32(dd + 8, rva(importDir.first)); f.u32(dd + 12, importDir.second.toLong()) }
        } else {
            f.u32(opt + 16, entryRva); f.u32(opt + 28, imageBase)
        }
        var sh = opt + optSize
        for (s in secs) {
            f.str(sh, s.name, 8); f.u32(sh + 8, s.bytes.size.toLong()); f.u32(sh + 12, s.rva.toLong())
            f.u32(sh + 16, ((s.bytes.size + 0x1ff) / 0x200 * 0x200).toLong()); f.u32(sh + 20, s.raw.toLong())
            f.u32(sh + 36, s.chars)
            sh += 40
        }
        for (s in secs) { f.put(s.raw, s.bytes); f.u8(s.raw + (s.bytes.size + 0x1ff) / 0x200 * 0x200 - 1, 0) }
        return f.bytes
    }

    /** The address a PE import's IAT slot ends up at, for the layout [pe64] builds (first DLL only). */
    fun peIatSlot(bytes: ByteArray, index: Int): Long {
        val r = ByteReader(bytes)
        val opt = 0x80 + 24
        val base = r.u64le(opt + 24L)
        val impRva = r.u32le(opt + 112L + 8)
        val impOff = impRva - 0x2000 + 0x600
        return base + r.u32le(impOff + 16) + 8L * index
    }

    /**
     * A synthetic PE32+ "hello" (no Windows toolchain is needed to build it):
     * `sub rsp, 0x28 / xor ecx, ecx / call [rel ExitProcess IAT slot] / int3`.
     */
    fun helloPe(): ByteArray {
        val imports = mapOf("KERNEL32.dll" to listOf("ExitProcess"))
        val slot = peIatSlot(pe64(imports = imports), 0)
        val next = PE_IMAGE_BASE + 0x1000 + 12
        val d = slot - next
        val code = hex("48 83 ec 28 31 c9 ff 15") + ByteArray(4) { (d ushr (8 * it)).toByte() } + hex("cc")
        return pe64(code = code, imports = imports)
    }

    fun truncate(b: ByteArray, n: Int) = b.copyOf(n)

    fun patch32(b: ByteArray, off: Int, v: Long): ByteArray = b.copyOf().also {
        for (i in 0..3) it[off + i] = (v ushr (8 * i)).toByte()
    }

    fun patch64(b: ByteArray, off: Int, v: Long): ByteArray = b.copyOf().also {
        for (i in 0..7) it[off + i] = (v ushr (8 * i)).toByte()
    }
}

package x86sim.disasm

/** A file is shaped like a known format but something in it is out of range. The message says what. */
class DamagedException(message: String) : Exception(message)

/** Bounds-checked reads from a file's bytes; anything out of range becomes a [DamagedException]. */
class ByteReader(val bytes: ByteArray) {
    val size: Int get() = bytes.size

    private fun check(off: Long, width: Long, what: String): Int {
        if (off < 0 || width < 0 || off > bytes.size - width)
            throw DamagedException("$what runs past the end of the file")
        return off.toInt()
    }

    fun u8(off: Long, what: String = "a header"): Int = bytes[check(off, 1, what)].toInt() and 0xff

    fun u16le(off: Long, what: String = "a header"): Int {
        val o = check(off, 2, what)
        return (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
    }

    fun u32le(off: Long, what: String = "a header"): Long {
        val o = check(off, 4, what)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[o + i].toLong() and 0xff)
        return v
    }

    fun u64le(off: Long, what: String = "a header"): Long {
        val o = check(off, 8, what)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[o + i].toLong() and 0xff)
        return v
    }

    fun u32be(off: Long, what: String = "a header"): Long {
        val o = check(off, 4, what)
        var v = 0L
        for (i in 0..3) v = (v shl 8) or (bytes[o + i].toLong() and 0xff)
        return v
    }

    fun slice(off: Long, len: Long, what: String): ByteArray {
        val o = check(off, len, what)
        return bytes.copyOfRange(o, o + len.toInt())
    }

    /** A NUL-terminated string of at most [maxLen] bytes (stops early at the end of the file). */
    fun cString(off: Long, maxLen: Int = 4096, what: String = "a name"): String {
        val o = check(off, 0, what)
        var end = o
        while (end < bytes.size && end - o < maxLen && bytes[end] != 0.toByte()) end++
        return String(bytes, o, end - o, Charsets.ISO_8859_1)
    }
}

package x86sim.cpu

class CpuFault(message: String) : RuntimeException(message)

/** What one memory write overwrote, so it can be undone (see [Memory.undoLog]). */
class MemUndo(
    val region: Region,
    val offset: Int,
    val oldBytes: ByteArray,
    val oldTouched: Int,      // bit i = byte offset+i had been written before
    val oldWriters: IntArray, // writer tags of the 8-byte words the write touched
)

class Region(val name: String, val start: Long, val size: Int, val writable: Boolean) {
    val bytes = ByteArray(size)
    /** Bytes the program has written since it was loaded (the rest would be leftover garbage on real hardware). */
    val touched = java.util.BitSet(size)
    /** For each 8-byte word: the tag (source line) of the instruction that last wrote to it, or -1. */
    val writer = IntArray(size / 8 + 1) { -1 }
    val end: Long get() = start + size
    operator fun contains(addr: Long) = addr >= start && addr < end
}

/**
 * A sparse, region-based address space. Accessing an address outside every mapped
 * region (or writing to a read-only region) raises a segmentation fault, which is what
 * a real process would experience.
 */
class Memory {
    private val regions = mutableListOf<Region>()
    val mapped: List<Region> get() = regions

    /** When set, [writeLog] records (address, size) of every write; the UI uses it to highlight changes. */
    var logWrites = false
    /** Recorded in [Region.writer] for every write; the machine sets it to the current source line. */
    var writerTag = -1
    /** When non-null, every write first appends what it is about to overwrite (for stepping back). */
    var undoLog: MutableList<MemUndo>? = null
    val writeLog = mutableListOf<Pair<Long, Int>>()

    fun map(region: Region): Region { regions += region; return region }
    fun clear() { regions.clear(); writeLog.clear() }
    fun clearWriteLog() = writeLog.clear()

    fun regionAt(addr: Long): Region? = regions.firstOrNull { addr in it }
    fun isMapped(addr: Long) = regionAt(addr) != null

    private fun regionFor(addr: Long, size: Int, write: Boolean): Region {
        val r = regionAt(addr)
        if (r == null || addr + size > r.end)
            throw CpuFault("Segmentation fault: ${if (write) "write to" else "read from"} unmapped address 0x%x".format(addr))
        if (write && !r.writable)
            throw CpuFault("Segmentation fault: write to read-only section ${r.name} at 0x%x".format(addr))
        return r
    }

    fun read(addr: Long, size: Int): Long {
        val r = regionFor(addr, size, write = false)
        val off = (addr - r.start).toInt()
        var v = 0L
        for (i in size - 1 downTo 0) v = (v shl 8) or (r.bytes[off + i].toLong() and 0xFF)
        return v
    }

    fun write(addr: Long, size: Int, value: Long) {
        val r = regionFor(addr, size, write = true)
        val off = (addr - r.start).toInt()
        undoLog?.let { log ->
            var touched = 0
            for (i in 0 until size) if (r.touched[off + i]) touched = touched or (1 shl i)
            log += MemUndo(r, off, r.bytes.copyOfRange(off, off + size), touched,
                IntArray((off + size - 1) / 8 - off / 8 + 1) { r.writer[off / 8 + it] })
        }
        for (i in 0 until size) r.bytes[off + i] = (value ushr (8 * i)).toByte()
        r.touched.set(off, off + size)
        for (w in off / 8..(off + size - 1) / 8) r.writer[w] = writerTag
        if (logWrites) writeLog += addr to size
    }

    /** Reverts writes recorded in an undo log, newest first. */
    fun undo(log: List<MemUndo>) {
        for (u in log.asReversed()) {
            val r = u.region
            u.oldBytes.copyInto(r.bytes, u.offset)
            for (i in u.oldBytes.indices) r.touched[u.offset + i] = (u.oldTouched shr i) and 1 == 1
            u.oldWriters.forEachIndexed { k, w -> r.writer[u.offset / 8 + k] = w }
            if (logWrites) writeLog += (r.start + u.offset) to u.oldBytes.size
        }
    }

    /** Reads a byte without faulting, for display purposes; null when unmapped. */
    fun peek(addr: Long): Int? {
        val r = regionAt(addr) ?: return null
        return r.bytes[(addr - r.start).toInt()].toInt() and 0xFF
    }

    fun isTouched(addr: Long): Boolean {
        val r = regionAt(addr) ?: return false
        return r.touched[(addr - r.start).toInt()]
    }

    /** Tag of the instruction that last wrote the 8-byte word containing [addr], or -1. */
    fun writerOf(addr: Long): Int {
        val r = regionAt(addr) ?: return -1
        return r.writer[((addr - r.start) / 8).toInt()]
    }

    fun peekQword(addr: Long): Long? {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (peek(addr + i)?.toLong() ?: return null)
        return v
    }

    fun readBytes(addr: Long, count: Int): ByteArray = ByteArray(count) { read(addr + it, 1).toByte() }
    fun writeBytes(addr: Long, data: ByteArray) = data.forEachIndexed { i, b -> write(addr + i, 1, b.toLong()) }
}

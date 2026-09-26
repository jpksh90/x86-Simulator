package x86sim.cpu

/**
 * A register operand: which of the 16 general-purpose registers ([num], in hardware
 * encoding order rax, rcx, rdx, rbx, rsp, rbp, rsi, rdi, r8..r15), how many bytes of
 * it are accessed ([size]) and, for ah/ch/dh/bh, that bits 8..15 are meant ([high]).
 */
data class Reg(val name: String, val num: Int, val size: Int, val high: Boolean = false) {
    override fun toString() = name
}

object Registers {
    const val RAX = 0; const val RCX = 1; const val RDX = 2; const val RBX = 3
    const val RSP = 4; const val RBP = 5; const val RSI = 6; const val RDI = 7

    val names64 = listOf("rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15")

    /** Order used when displaying registers (the conventional textbook order). */
    val displayOrder = listOf(RAX, RBX, RCX, RDX, RSI, RDI, RBP, RSP, 8, 9, 10, 11, 12, 13, 14, 15)

    private val byName: Map<String, Reg> = buildMap {
        val n32 = listOf("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi")
        val n16 = listOf("ax", "cx", "dx", "bx", "sp", "bp", "si", "di")
        val n8 = listOf("al", "cl", "dl", "bl", "spl", "bpl", "sil", "dil")
        for (i in 0 until 16) put(names64[i], Reg(names64[i], i, 8))
        for (i in 0 until 8) {
            put(n32[i], Reg(n32[i], i, 4))
            put(n16[i], Reg(n16[i], i, 2))
            put(n8[i], Reg(n8[i], i, 1))
        }
        for (i in 8 until 16) {
            put("r${i}d", Reg("r${i}d", i, 4))
            put("r${i}w", Reg("r${i}w", i, 2))
            put("r${i}b", Reg("r${i}b", i, 1))
        }
        listOf("ah", "ch", "dh", "bh").forEachIndexed { i, n -> put(n, Reg(n, i, 1, high = true)) }
    }

    fun lookup(name: String): Reg? = byName[name.lowercase()]
    fun isRegister(name: String) = name.lowercase() in byName
}

/** Helpers for working with values of a given operand size (1, 2, 4 or 8 bytes). */
object Bits {
    fun mask(size: Int): Long = if (size == 8) -1L else (1L shl (size * 8)) - 1
    fun trunc(v: Long, size: Int): Long = v and mask(size)
    fun signExtend(v: Long, size: Int): Long = when (size) {
        1 -> v.toByte().toLong()
        2 -> v.toShort().toLong()
        4 -> v.toInt().toLong()
        else -> v
    }
    fun msb(v: Long, size: Int): Boolean = (v ushr (size * 8 - 1)) and 1L == 1L
    fun parity(v: Long): Boolean = Integer.bitCount((v and 0xFF).toInt()) % 2 == 0
    fun sizeName(size: Int) = when (size) { 1 -> "byte"; 2 -> "word"; 4 -> "dword"; else -> "qword" }
}

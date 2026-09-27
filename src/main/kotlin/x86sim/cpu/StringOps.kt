package x86sim.cpu

/**
 * A repeat prefix on a string instruction. `rep` and `repe` share the F3 encoding, so `rep`
 * on scas/cmps behaves as `repe`.
 */
enum class RepPrefix {
    NONE, REP, REPE, REPNE;

    companion object {
        val SPELLINGS = mapOf("rep" to REP, "repe" to REPE, "repz" to REPE, "repne" to REPNE, "repnz" to REPNE)
        fun parse(word: String): RepPrefix? = SPELLINGS[word.lowercase()]
    }
}

/**
 * A string instruction (`movsb`, `repne scasb`'s `scasb`, ...): what it does and how big each
 * element is. Its operands are implicit: rsi (source), rdi (destination) and the accumulator.
 */
data class StringOp(val family: Family, val size: Int) {
    enum class Family { MOVS, STOS, LODS, SCAS, CMPS }

    val usesRsi get() = family == Family.MOVS || family == Family.LODS || family == Family.CMPS
    val usesRdi get() = family != Family.LODS
    val setsFlags get() = family == Family.SCAS || family == Family.CMPS

    /** Whether a repeat ends because of ZF after an iteration (only scas/cmps compare). */
    fun stopsAfter(prefix: RepPrefix, zf: Boolean): Boolean = setsFlags && when (prefix) {
        RepPrefix.REP, RepPrefix.REPE -> !zf
        RepPrefix.REPNE -> zf
        RepPrefix.NONE -> false
    }

    companion object {
        private val SIZES = mapOf('b' to 1, 'w' to 2, 'd' to 4, 'q' to 8)

        /** The family names without a size, which NASM doesn't accept. */
        val BARE = setOf("movs", "stos", "lods", "scas", "cmps")
        val ALL_MNEMONICS: Set<String> = BARE.flatMap { f -> SIZES.keys.map { f + it } }.toSet()

        fun of(mnemonic: String): StringOp? {
            val m = mnemonic.lowercase()
            if (m.length != 5 || m.substring(0, 4) !in BARE) return null
            val size = SIZES[m[4]] ?: return null
            return StringOp(Family.valueOf(m.substring(0, 4).uppercase()), size)
        }
    }
}

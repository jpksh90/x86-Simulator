package x86sim.asm

import x86sim.cpu.Reg
import x86sim.cpu.Registers

class ExprException(message: String) : Exception(message)

/** Result of evaluating an expression: a constant plus a linear combination of registers. */
data class Linear(val constant: Long, val regs: List<Pair<Reg, Long>> = emptyList()) {
    val isConstant get() = regs.isEmpty()

    operator fun plus(o: Linear) = Linear(constant + o.constant, merge(regs + o.regs))
    operator fun unaryMinus() = Linear(-constant, regs.map { it.first to -it.second })
    fun times(k: Long) = Linear(constant * k, regs.map { it.first to it.second * k })

    private fun merge(list: List<Pair<Reg, Long>>): List<Pair<Reg, Long>> {
        val out = LinkedHashMap<Reg, Long>()
        for ((r, k) in list) out[r] = (out[r] ?: 0) + k
        return out.filter { it.value != 0L }.map { it.key to it.value }
    }
}

/**
 * A small recursive-descent parser for NASM-style expressions:
 * numbers (`42`, `0x2A`, `2Ah`, `0b101010`, `'A'`), symbols, `$`, `$$`,
 * `+ - * / % << >> & | ^ ~` and parentheses. When [allowRegisters] is set,
 * register names are permitted so that `rbx + rcx*8 - 16` can be analysed.
 */
class ExprParser(
    private val text: String,
    private val resolve: (String) -> Long?,
    private val dollar: Long = 0,
    private val sectionStart: Long = 0,
    private val allowRegisters: Boolean = false,
) {
    private var pos = 0

    fun parse(): Linear {
        val v = or()
        skipWs()
        if (pos < text.length) throw ExprException("unexpected '${text.substring(pos)}' in expression '$text'")
        return v
    }

    private fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }

    private fun peek(s: String): Boolean {
        skipWs()
        return text.startsWith(s, pos)
    }

    private fun eat(s: String): Boolean = peek(s).also { if (it) pos += s.length }

    private fun constOnly(l: Linear, op: String): Long {
        if (!l.isConstant) throw ExprException("operator '$op' can't be applied to a register")
        return l.constant
    }

    private fun or(): Linear {
        var l = xor()
        while (peek("|")) { pos++; l = Linear(constOnly(l, "|") or constOnly(xor(), "|")) }
        return l
    }

    private fun xor(): Linear {
        var l = and()
        while (peek("^")) { pos++; l = Linear(constOnly(l, "^") xor constOnly(and(), "^")) }
        return l
    }

    private fun and(): Linear {
        var l = shift()
        while (peek("&")) { pos++; l = Linear(constOnly(l, "&") and constOnly(shift(), "&")) }
        return l
    }

    private fun shift(): Linear {
        var l = additive()
        while (true) {
            l = when {
                eat("<<") -> Linear(constOnly(l, "<<") shl constOnly(additive(), "<<").toInt())
                eat(">>") -> Linear(constOnly(l, ">>") ushr constOnly(additive(), ">>").toInt())
                else -> return l
            }
        }
    }

    private fun additive(): Linear {
        var l = term()
        while (true) {
            l = when {
                eat("+") -> l + term()
                eat("-") -> l + (-term())
                else -> return l
            }
        }
    }

    private fun term(): Linear {
        var l = unary()
        while (true) {
            l = when {
                peek("*") -> { pos++; val r = unary()
                    when {
                        r.isConstant -> l.times(r.constant)
                        l.isConstant -> r.times(l.constant)
                        else -> throw ExprException("can't multiply two registers")
                    } }
                peek("/") && !peek("//") -> { pos++; val d = constOnly(unary(), "/")
                    if (d == 0L) throw ExprException("division by zero in expression")
                    Linear(constOnly(l, "/") / d) }
                peek("%") -> { pos++; val d = constOnly(unary(), "%")
                    if (d == 0L) throw ExprException("division by zero in expression")
                    Linear(constOnly(l, "%") % d) }
                else -> return l
            }
        }
    }

    private fun unary(): Linear = when {
        eat("-") -> -unary()
        eat("+") -> unary()
        eat("~") -> Linear(constOnly(unary(), "~").inv())
        else -> primary()
    }

    private fun primary(): Linear {
        skipWs()
        if (pos >= text.length) throw ExprException("expression ended unexpectedly: '$text'")
        val c = text[pos]
        if (c == '(') {
            pos++
            val v = or()
            if (!eat(")")) throw ExprException("missing ')' in '$text'")
            return v
        }
        if (c == '\'' || c == '"' || c == '`') return Linear(charConst())
        if (text.startsWith("$$", pos)) { pos += 2; return Linear(sectionStart) }
        if (c == '$' && (pos + 1 >= text.length || !isIdentChar(text[pos + 1]))) { pos++; return Linear(dollar) }
        if (c.isDigit()) return Linear(number())
        if (isIdentStart(c)) {
            val start = pos
            while (pos < text.length && isIdentChar(text[pos])) pos++
            val name = text.substring(start, pos)
            Registers.lookup(name)?.let { reg ->
                if (!allowRegisters) throw ExprException("register '$name' not allowed here")
                return Linear(0, listOf(reg to 1L))
            }
            val v = resolve(name) ?: throw ExprException("undefined symbol '$name'")
            return Linear(v)
        }
        throw ExprException("unexpected character '$c' in '$text'")
    }

    private fun charConst(): Long {
        val bytes = parseStringLiteral(text, pos).also { pos = it.second }.first
        if (bytes.size > 8) throw ExprException("character constant too long")
        var v = 0L
        for (i in bytes.indices.reversed()) v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        return v
    }

    private fun number(): Long {
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
        val raw = text.substring(start, pos).replace("_", "").lowercase()
        return parseNumber(raw) ?: throw ExprException("invalid number '$raw'")
    }

    companion object {
        fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '.' || c == '?' || c == '@' || c == '$'
        fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '?' || c == '@' || c == '$' || c == '#'

        fun parseNumber(s: String): Long? = try {
            when {
                s.startsWith("0x") -> java.lang.Long.parseUnsignedLong(s.substring(2), 16)
                s.startsWith("0b") && s.length > 2 && s.substring(2).all { it == '0' || it == '1' } ->
                    java.lang.Long.parseUnsignedLong(s.substring(2), 2)
                s.startsWith("0o") -> java.lang.Long.parseUnsignedLong(s.substring(2), 8)
                s.endsWith("h") -> java.lang.Long.parseUnsignedLong(s.dropLast(1), 16)
                s.endsWith("b") && s.dropLast(1).all { it == '0' || it == '1' } ->
                    java.lang.Long.parseUnsignedLong(s.dropLast(1), 2)
                s.endsWith("q") || s.endsWith("o") -> java.lang.Long.parseUnsignedLong(s.dropLast(1), 8)
                s.endsWith("d") -> java.lang.Long.parseUnsignedLong(s.dropLast(1), 10)
                else -> java.lang.Long.parseUnsignedLong(s, 10)
            }
        } catch (_: NumberFormatException) { null }

        /**
         * Parses a quoted string starting at [start]. As in NASM, `'...'` and `"..."`
         * are taken literally while backquoted strings understand C escapes (`\n`, `\t`,
         * `\0`, `\xHH`, ...). Returns the bytes and the index just past the closing quote.
         */
        fun parseStringLiteral(text: String, start: Int): Pair<ByteArray, Int> {
            val q = text[start]
            val out = java.io.ByteArrayOutputStream()
            var i = start + 1
            while (true) {
                if (i >= text.length) throw ExprException("unterminated string")
                val c = text[i]
                if (c == q) { i++; break }
                if (q == '`' && c == '\\' && i + 1 < text.length) {
                    i++
                    when (val e = text[i]) {
                        'n' -> out.write(10); 't' -> out.write(9); 'r' -> out.write(13)
                        '0' -> out.write(0); 'a' -> out.write(7); 'b' -> out.write(8)
                        'e' -> out.write(27); '\\' -> out.write('\\'.code)
                        'x' -> {
                            val hex = text.substring(i + 1).takeWhile { it.isLetterOrDigit() }.take(2)
                            out.write(hex.toInt(16)); i += hex.length
                        }
                        else -> out.write(e.code)
                    }
                    i++
                    continue
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toByteArray() to i
        }
    }
}

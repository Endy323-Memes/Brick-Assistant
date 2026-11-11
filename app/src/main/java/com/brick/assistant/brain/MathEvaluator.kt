package com.brick.assistant.brain

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** Understands spoken arithmetic such as "what is 12 times 8" or "square root of 144 plus 3". */
object MathEvaluator {
    class Result(val spoken: String, val value: Double) {
        val formatted: String get() = format(value)
    }

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val prefix = Regex(
        "^(?:hey\\s+)?(?:please\\s+)?(?:can you\\s+)?(?:what\\s+is|what's|whats|what\\s+does|calculate|compute|how\\s+much\\s+is|solve|evaluate|tell\\s+me)\\s+",
        IC,
    )

    private val replacements = listOf(
        "to the power of" to "^", "raised to the" to "^", "raised to" to "^",
        "multiplied by" to "*", "times" to "*", "divided by" to "/", "over" to "/",
        "plus" to "+", "minus" to "-", "negative" to "-",
        "squared" to "^2", "cubed" to "^3",
        "square root of" to " sqrt ", "square root" to " sqrt ", "root of" to " sqrt ",
        "percent of" to "/100*", "modulo" to "#", "mod" to "#", "percent" to "/100",
    )

    /** Returns null if the text is not a math expression. */
    fun tryParse(text: String): Result? {
        val human = text.trim().replace(prefix, "")
            .trimEnd('?', '.', '!', ' ', '=')
            .replace(Regex("\\s+(?:equal|equals|is)$", IC), "")
            .trim()
        if (human.isEmpty() || human.none { it.isDigit() }) return null

        var e = " " + human.lowercase() + " "
        e = e.replace("×", "*").replace("÷", "/").replace("−", "-").replace("% of", "/100*").replace("%", "/100")
        e = e.replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
        e = e.replace(Regex("\\bthe\\b"), " ").replace(Regex("\\s+"), " ")
        for ((word, sym) in replacements) {
            e = e.replace(Regex("\\b${Regex.escape(word)}\\b"), sym)
        }
        e = e.replace(Regex("\\b(?:of|is|by)\\b"), " ")
        e = e.replace(Regex("\\bpi\\b"), " pi ").replace(Regex("\\s+"), " ").trim()

        // Only allow math characters.
        val stripped = e.replace("sqrt", "").replace("pi", "")
        if (stripped.any { !(it.isDigit() || it in ".+-*/^()# ") }) return null
        if (e.none { it in "+-*/^#" } && !e.contains("sqrt")) return null

        return try {
            val p = Parser(e)
            val v = p.parseAll()
            Result(speakable(human), v)
        } catch (ex: Exception) {
            null
        }
    }

    private fun speakable(human: String): String = human
        .replace("+", " plus ").replace("*", " times ").replace("/", " divided by ")
        .replace("^", " to the power of ").replace("=", " ")
        .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), " times ")
        .replace(Regex("(?<=\\d)\\s*-\\s*(?=\\d)"), " minus ")
        .replace(Regex("\\s+"), " ").trim()

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "undefined"
        val s = if (abs(v - round(v)) < 1e-9 && abs(v) < 1e15) {
            round(v).toLong().toString()
        } else {
            BigDecimal(v).round(MathContext(8)).stripTrailingZeros().toPlainString()
        }
        return if (s.startsWith("-")) "negative " + s.substring(1) else s
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): Double {
            val v = expr()
            skip()
            if (i != s.length) throw IllegalArgumentException("Unexpected '${s[i]}'")
            return v
        }

        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skip(); return s.getOrNull(i) }

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= unary() }
                    '/' -> { i++; v /= unary() }
                    '#' -> { i++; v %= unary() }
                    '(' -> v *= unary() // implicit multiplication: 2(3+4)
                    else -> return v
                }
            }
        }

        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> power()
        }

        fun power(): Double {
            val base = primary()
            return if (peek() == '^') { i++; base.pow(unary()) } else base
        }

        fun primary(): Double {
            skip()
            if (s.startsWith("sqrt", i)) { i += 4; return sqrt(unary()) }
            if (s.startsWith("pi", i)) { i += 2; return PI }
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("Missing )")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("Number expected")
            return s.substring(start, i).toDouble()
        }
    }
}

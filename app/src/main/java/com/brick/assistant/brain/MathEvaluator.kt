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
    /** [expression]: the equation in symbols; [steps]: each operation in the order it was worked out. */
    class Result(val spoken: String, val value: Double, val expression: String = "", val steps: List<String> = emptyList()) {
        val formatted: String get() = format(value)
    }

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val prefix = Regex(
        "^(?:hey\\s+)?(?:please\\s+)?(?:can you\\s+)?(?:what\\s+is|what's|whats|what\\s+does|calculate|compute|how\\s+much\\s+is|solve|evaluate|tell\\s+me)\\s+",
        IC,
    )

    private val replacements = listOf(
        // "the" is dropped before these run, so "to the power of" arrives as "to power of".
        "to the power of" to "^", "to power of" to "^", "raised to the" to "^", "raised to" to "^",
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
        e = e.replace(Patterns.get("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
        e = e.replace(Patterns.get("\\bthe\\b"), " ").replace(Patterns.get("\\s+"), " ")
        for ((word, sym) in replacements) {
            e = e.replace(Regex("\\b${Regex.escape(word)}\\b"), sym)
        }
        e = e.replace(Patterns.get("\\b(?:of|is|by)\\b"), " ")
        e = e.replace(Patterns.get("\\bpi\\b"), " pi ").replace(Patterns.get("\\s+"), " ").trim()

        // Only allow math characters.
        val stripped = e.replace("sqrt", "").replace("pi", "")
        if (stripped.any { !(it.isDigit() || it in ".+-*/^()# ") }) return null
        if (e.none { it in "+-*/^#" } && !e.contains("sqrt")) return null

        return try {
            val p = Parser(e)
            val v = p.parseAll()
            Result(speakable(human), v, pretty(e), p.steps)
        } catch (ex: Exception) {
            null
        }
    }

    private fun speakable(human: String): String = human
        .replace("+", " plus ").replace("*", " times ").replace("/", " divided by ")
        .replace("^", " to the power of ").replace("=", " ")
        .replace(Patterns.get("(?<=\\d)\\s*x\\s*(?=\\d)"), " times ")
        .replace(Patterns.get("(?<=\\d)\\s*-\\s*(?=\\d)"), " minus ")
        .replace(Patterns.get("\\s+"), " ").trim()

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "undefined"
        val s = if (abs(v - round(v)) < 1e-9 && abs(v) < 1e15) {
            round(v).toLong().toString()
        } else {
            BigDecimal(v).round(MathContext(8)).stripTrailingZeros().toPlainString()
        }
        return if (s.startsWith("-")) "negative " + s.substring(1) else s
    }

    /** A number as digits ("-3", "0.33333333"), for the worked steps. */
    fun plain(v: Double): String = format(v).replace("negative ", "-")

    /** The equation in symbols: "12 x 8 + 3", "sqrt 144". */
    private fun pretty(e: String): String = e.replace("*", " x ").replace("/", " / ").replace("+", " + ")
        .replace(Patterns.get("(?<=[\\d)])\\s*-\\s*"), " - ").replace("#", " mod ").replace("^", "^")
        .replace("sqrt", "sqrt ").replace(Patterns.get("\\s+"), " ").trim()

    private class Parser(val s: String) {
        var i = 0
        val steps = ArrayList<String>()

        fun step(a: Double, op: String, b: Double, r: Double): Double {
            steps += "${plain(a)} $op ${plain(b)} = ${plain(r)}"
            return r
        }

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
                    '+' -> { i++; val b = term(); v = step(v, "+", b, v + b) }
                    '-' -> { i++; val b = term(); v = step(v, "-", b, v - b) }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                when (peek()) {
                    '*' -> { i++; val b = unary(); v = step(v, "x", b, v * b) }
                    '/' -> { i++; val b = unary(); v = step(v, "/", b, v / b) }
                    '#' -> { i++; val b = unary(); v = step(v, "mod", b, v % b) }
                    '(' -> { val b = unary(); v = step(v, "x", b, v * b) } // implicit multiplication: 2(3+4)
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
            return if (peek() == '^') { i++; val b = unary(); step(base, "^", b, base.pow(b)) } else base
        }

        fun primary(): Double {
            skip()
            if (s.startsWith("sqrt", i)) { i += 4; val a = unary(); val r = sqrt(a); steps += "sqrt(${plain(a)}) = ${plain(r)}"; return r }
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

package com.brick.assistant.brain

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * Understands typed and spoken math: "9000 * 200 - 5 + 300^15", "what is 12 times 8", "square root of 144 plus 3",
 * "10!", "sin 30", "log of 1000", "cube root of 27", "half of 90", "15% off 80", "average of 3, 5 and 10",
 * "round 3.14159 to 2 decimal places", "9,000 x 200", "1.5e6 / 3".
 *
 * Whole numbers are exact however big they get (300^15 keeps every digit, up to [MAX_DIGITS]); decimals carry
 * 40 significant digits, so 0.1 + 0.2 is 0.3. Trig works in degrees unless "radians" is said.
 */
object MathEvaluator {
    /**
     * [value]: the answer as a Double (NaN when undefined, infinite when too big); [exact]: every digit of it;
     * [expression]: the equation in symbols; [steps]: each operation in the order it was worked out.
     */
    class Result(
        val spoken: String, val value: Double, val expression: String = "", val steps: List<String> = emptyList(),
        val exact: BigDecimal? = null, val tooBig: Boolean = false,
    ) {
        /** The answer as Brick says it: "56", "0.33333333", "1.4348907 times 10 to the power of 37". */
        val formatted: String get() = exact?.let { say(it) } ?: if (tooBig) "too big" else format(value)
        /** The answer for the card, every digit of it, grouped: "14,348,907,000,...,001,799,995". */
        val shown: String get() = exact?.let { show(it) } ?: if (tooBig) "More than ${group(MAX_DIGITS.toString())} digits" else plain(value)
        /** "a 38-digit number", for answers too long to say. */
        val digits: Int? get() = exact?.takeIf { isWhole(it) }?.let { it.toBigInteger().abs().toString().length }
    }

    /** Past this many digits a number is too big to work with. */
    const val MAX_DIGITS = 10_000
    private val MC = MathContext(40, RoundingMode.HALF_EVEN)
    private val us = Locale.US

    private class Undefined : RuntimeException()
    private class TooBig : RuntimeException()

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val prefix = Regex(
        "^(?:hey\\s+)?(?:brick[,\\s]+)?(?:please\\s+)?(?:(?:can|could) you\\s+)?(?:please\\s+)?(?:what\\s+is|what's|whats|what\\s+does|what\\s+do|calculate|compute|how\\s+much\\s+is|solve|evaluate|tell\\s+me|work\\s+out|figure\\s+out|find)\\s+",
        IC,
    )

    // ---------------------------------------------------------------- words to symbols

    private val FUNCS = listOf("asin", "acos", "atan", "sqrt", "cbrt", "sin", "cos", "tan", "abs", "exp", "ln", "log")
    /** Function names (and "root3", "logb2"), constants: what may sit among the symbols. */
    private val IDENT = Regex("(?<![a-z])(?:asin|acos|atan|sqrt|cbrt|sin|cos|tan|abs|exp|ln|log|logb\\d+|root\\d+|pi|e)(?![a-z])")

    private val replacements = listOf(
        // Before the plain words: "the" is dropped first, so "to the power of" arrives as "to power of".
        "inverse sine of" to " asin ", "inverse cosine of" to " acos ", "inverse tangent of" to " atan ",
        "inverse sine" to " asin ", "inverse cosine" to " acos ", "inverse tangent" to " atan ",
        "arcsine of" to " asin ", "arccosine of" to " acos ", "arctangent of" to " atan ", "arcsin" to " asin ", "arccos" to " acos ", "arctan" to " atan ",
        "sine of" to " sin ", "cosine of" to " cos ", "tangent of" to " tan ", "sine" to " sin ", "cosine" to " cos ", "tangent" to " tan ",
        "natural log of" to " ln ", "natural logarithm of" to " ln ", "natural log" to " ln ",
        "logarithm of" to " log ", "log of" to " log ", "logarithm" to " log ",
        "absolute value of" to " abs ", "absolute value" to " abs ",
        "square root of" to " sqrt ", "square root" to " sqrt ", "cube root of" to " cbrt ", "cube root" to " cbrt ", "root of" to " sqrt ",
        "to power of" to "^", "raised to power of" to "^", "raised to" to "^", "to power" to "^",
        "multiplied by" to "*", "times" to "*", "divided by" to "/", "divided into" to "\\", "over" to "/",
        "plus" to "+", "added to" to "+", "minus" to "-", "take away" to "-", "less" to "-", "negative" to "-",
        "squared" to "^2", "cubed" to "^3", "factorial" to "!",
        "percent of" to "/100*", "modulo" to "#", "mod" to "#", "remainder of" to " ", "percent" to "/100",
    )

    /** The words of a question rewritten as symbols, with [radians] noted. */
    private class Prepared(val e: String, val radians: Boolean)

    private fun prepare(human: String): Prepared? {
        var e = " " + human.lowercase(us) + " "
        val radians = Regex("\\bradians?\\b|\\brad\\b").containsMatchIn(e)
        e = e.replace(Regex("\\b(?:in )?(?:degrees?|deg|radians?|rad)\\b|°"), " ")
        e = e.replace("×", "*").replace("÷", "/").replace("−", "-").replace("√", " sqrt ").replace("π", " pi ")
        // "15% off 80" is what's left after the discount; "% of" is a share.
        e = e.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent) off (?:of )?(\\d+(?:\\.\\d+)?)"), "$2*(1-$1/100)")
        e = e.replace("% of", "/100*").replace("%", "/100")
        e = e.replace(Regex("(?<=\\d)\\s*x\\s*(?=[\\d(])"), "*")
        e = e.replace(Regex("\\bthe\\b"), " ").replace(Regex("\\s+"), " ")
        // "300 to the 15th (power)", "2 to 10th"; "4th root of 16"; "log base 2 of 8"; "5 factorial", "factorial of 5".
        e = e.replace(Regex("\\bto (\\d+)(?:st|nd|rd|th)(?: power)?\\b"), "^$1")
        e = e.replace(Regex("\\b(\\d+)(?:st|nd|rd|th) root(?: of)?\\b"), " root$1 ")
        e = e.replace(Regex("\\blog(?:arithm)? (?:in )?base (\\d+)(?: of)?\\b"), " logb$1 ")
        e = e.replace(Regex("\\bfactorial of (\\d+)\\b"), "$1!")
        // "half of 90", "double 45", "twice 7", "a third of 12", "a quarter of 80".
        e = e.replace(Regex("\\b(?:a |one )?half of\\b"), " 0.5*").replace(Regex("\\b(?:a |one )?third of\\b"), " (1/3)*")
            .replace(Regex("\\b(?:a |one )?(?:quarter|fourth) of\\b"), " 0.25*").replace(Regex("\\b(?:double|twice)\\b"), " 2*").replace(Regex("\\btriple\\b"), " 3*")
        for ((word, sym) in replacements) e = e.replace(Regex("\\b${Regex.escape(word)}\\b"), Regex.escapeReplacement(sym))
        // "3 divided into 12" is 12 / 3.
        e = e.replace(Regex("(\\S+)\\s*\\\\\\s*(\\S+)"), "$2/$1")
        e = e.replace(Regex("\\b(?:of|is|by|what|equals?|equal to)\\b"), " ")
        e = e.replace(Regex("\\s+"), " ").trim()
        // Only math may remain: digits, operators, brackets, and known names.
        val bare = e.replace(Regex("(?<=\\d)e[+-]?(?=\\d)"), "").replace(IDENT, " ")
        if (bare.any { !(it.isDigit() || it in ".+-*/^()#! ") }) return null
        return Prepared(e, radians)
    }

    /** Returns null if the text is not a math expression. */
    fun tryParse(text: String): Result? {
        // "9,000" is nine thousand; "3, 5 and 10" stays a list.
        val human = text.trim().replace(Regex("(?<=\\d),(?=\\d{3}(?!\\d))"), "").replace(prefix, "")
            .trimEnd('?', '.', '!', ' ', '=').let { if (text.trim().trimEnd('?', '.', ' ').endsWith("!") && Regex("\\d!$").containsMatchIn(text.trim().trimEnd('?', '.', ' '))) "$it!" else it }
            .replace(Regex("\\s+(?:equal|equals|is|equal to)$", IC), "")
            .trim()
        if (human.isEmpty()) return null
        listOf(this::aggregate, this::rounding).firstNotNullOfOrNull { it(human) }?.let { return it }
        val p = prepare(human) ?: return null
        val func = Regex("(?<![a-z])(?:asin|acos|atan|sqrt|cbrt|sin|cos|tan|abs|exp|ln|log|logb\\d+|root\\d+)(?![a-z])").containsMatchIn(p.e)
        val constant = Regex("(?<![a-z])(?:pi|e)(?![a-z])").containsMatchIn(p.e)
        // Needs a number (or pi or e under a function: "ln e", "sin pi"), and something to do: an operator, a function, or "2pi".
        if (p.e.none { it.isDigit() } && !(func && constant)) return null
        val hasOp = p.e.any { it in "+-*/^#!" } || func || Regex("\\d\\s*(?:\\(|pi(?![a-z]))|\\)\\s*\\(").containsMatchIn(p.e)
        if (!hasOp) return null
        // A bare negative number ("-5") or a date-like "12-25" isn't a sum to work out.
        if (Regex("^-?\\d+(?:\\.\\d+)?$").matches(p.e)) return null
        return run(speakable(human), pretty(p.e)) { Parser(p.e, p.radians).let { parser -> parser.parseAll() to parser.steps } }
    }

    /** Works out [body], turning undefined and too-big answers into results of their own. */
    private fun run(spoken: String, expression: String, body: () -> Pair<BigDecimal, List<String>>): Result? = try {
        val (v, steps) = body()
        Result(spoken, toDouble(v), expression, steps, tidy(v))
    } catch (ex: Undefined) {
        Result(spoken, Double.NaN, expression)
    } catch (ex: TooBig) {
        Result(spoken, Double.POSITIVE_INFINITY, expression, tooBig = true)
    } catch (ex: Exception) {
        null
    }

    // ---------------------------------------------------------------- averages, sums and rounding

    private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?")

    /** "average of 3, 5 and 10", "sum of 4 6 and 8", "product of 2, 3 and 4". */
    private fun aggregate(human: String): Result? {
        val m = Regex("^(?:the )?(average|mean|sum|total|product) (?:of )?(.+)$", IC).find(human) ?: return null
        val list = m.groupValues[2]
        if (!Regex("^\\s*-?\\d[\\d.e+-]*(?:\\s*(?:,|and|&|\\s)\\s*-?\\d[\\d.e+-]*)+\\s*$", IC).matches(list)) return null
        val nums = NUMBER.findAll(list).map { BigDecimal(it.value) }.toList()
        if (nums.size < 2) return null
        val kind = m.groupValues[1].lowercase(us)
        val shownList = nums.joinToString(", ") { plain(it) }
        return run("the $kind of $shownList", "$kind($shownList)") {
            val steps = ArrayList<String>()
            if (kind == "product") {
                val r = nums.drop(1).fold(nums[0]) { a, b -> check(a.multiply(b)) }
                steps += nums.joinToString(" x ") { plain(it) } + " = " + plain(r)
                r to steps
            } else {
                val sum = nums.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
                steps += nums.joinToString(" + ") { plain(it) } + " = " + plain(sum)
                if (kind == "sum" || kind == "total") sum to steps
                else {
                    val avg = divide(sum, BigDecimal(nums.size))
                    steps += "${plain(sum)} / ${nums.size} = ${plain(avg)}"
                    avg to steps
                }
            }
        }
    }

    private val PLACES = mapOf("whole number" to 0, "integer" to 0, "tenth" to 1, "hundredth" to 2, "thousandth" to 3, "ten" to -1, "hundred" to -2, "thousand" to -3,
        "million" to -6)

    /** "round 3.14159 to 2 decimal places", "round 1234 to the nearest hundred", "round 2.5". */
    private fun rounding(human: String): Result? {
        val m = Regex("^round(?: off)? (.+?)(?: to (?:the )?(?:nearest )?(?:(\\d+) (?:decimal places?|decimals?|places?|digits?)|(whole number|integer|tenth|hundredth|thousandth|ten|hundred|thousand|million)))?$", IC)
            .find(human) ?: return null
        val inner = m.groupValues[1]
        val places = m.groupValues[2].toIntOrNull() ?: PLACES[m.groupValues[3].lowercase(us)] ?: 0
        val x = if (NUMBER.matches(inner)) BigDecimal(inner) else tryParse(inner)?.exact ?: return null
        val r = x.setScale(places, RoundingMode.HALF_UP)
        val to = when {
            places > 0 -> "$places decimal place${if (places == 1) "" else "s"}"
            places == 0 -> "the nearest whole number"
            else -> "the nearest ${PLACES.entries.first { it.value == places }.key}"
        }
        return Result("$inner rounded to $to", toDouble(r), "round(${plain(x)}, $places)", listOf("${plain(x)} -> ${plain(r)}"), tidy(r))
    }

    // ---------------------------------------------------------------- arithmetic

    private fun isWhole(v: BigDecimal) = v.signum() == 0 || v.stripTrailingZeros().scale() <= 0

    /** Digits before the point, for the size limit. */
    private fun size(v: BigDecimal) = v.precision() - v.scale()

    private fun check(v: BigDecimal): BigDecimal {
        if (size(v) > MAX_DIGITS) throw TooBig()
        // Decimals keep 40 significant digits; whole numbers keep them all.
        return if (isWhole(v)) v.setScale(0, RoundingMode.UNNECESSARY).let { if (it.scale() < 0) it.setScale(0) else it } else v.round(MC)
    }

    private fun tidy(v: BigDecimal): BigDecimal = if (isWhole(v)) v.setScale(0, RoundingMode.HALF_UP) else v.round(MC).stripTrailingZeros()

    private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
        if (b.signum() == 0) throw Undefined()
        return check(try { a.divide(b) } catch (ex: ArithmeticException) { a.divide(b, MC) })
    }

    private fun toDouble(v: BigDecimal): Double = v.toDouble()

    /** log10 of any positive number, even ones past a Double's range. */
    private fun log10(v: BigDecimal): Double {
        val d = v.toDouble()
        if (d > 0 && !d.isInfinite() && d >= java.lang.Double.MIN_NORMAL) return Math.log10(d)
        val exp = size(v) - 1
        return exp + Math.log10(v.movePointLeft(exp).toDouble())
    }

    /** A Double answer back as a number, refusing infinities and the like. */
    private fun fromDouble(d: Double): BigDecimal {
        if (d.isNaN()) throw Undefined()
        if (d.isInfinite()) throw TooBig()
        return BigDecimal(d.toString()).round(MathContext(15)).let { if (it.abs() < BigDecimal("1e-14")) BigDecimal.ZERO else it }
    }

    private fun power(a: BigDecimal, b: BigDecimal): BigDecimal {
        if (isWhole(b) && b.abs() <= BigDecimal(999_999_999)) {
            val n = b.toInt()
            if (a.signum() == 0) return if (n < 0) throw Undefined() else if (n == 0) BigDecimal.ONE else BigDecimal.ZERO
            if (a.abs().compareTo(BigDecimal.ONE) != 0 && Math.abs(n) * Math.abs(log10(a.abs())) > MAX_DIGITS + 5) {
                if (n > 0 == a.abs() > BigDecimal.ONE) throw TooBig() else return BigDecimal.ZERO
            }
            return when {
                n >= 0 && isWhole(a) -> check(a.pow(n))
                n >= 0 -> check(a.pow(n, MC))
                else -> divide(BigDecimal.ONE, a.pow(-n, MC))
            }
        }
        // A fractional power: real only for a positive base.
        if (a.signum() < 0) throw Undefined()
        if (a.signum() == 0) return BigDecimal.ZERO
        val lg = b.toDouble() * log10(a)
        if (lg > MAX_DIGITS) throw TooBig()
        if (lg < -300) return BigDecimal.ZERO
        val whole = Math.floor(lg)
        val r = BigDecimal(Math.pow(10.0, lg - whole).toString()).round(MathContext(15)).scaleByPowerOfTen(whole.toInt())
        // Exact roots stay exact: 16^0.5 is 4, 27^(1/3) is 3.
        val near = r.setScale(0, RoundingMode.HALF_UP)
        return if (isWhole(a) && r.subtract(near).abs() < BigDecimal("1e-13").max(r.abs().multiply(BigDecimal("1e-13")))) near else check(r)
    }

    private fun sqrt(a: BigDecimal): BigDecimal {
        if (a.signum() < 0) throw Undefined()
        if (a.signum() == 0) return BigDecimal.ZERO
        if (isWhole(a)) {
            val n = a.toBigInteger()
            val r = isqrt(n)
            if (r.multiply(r) == n) return BigDecimal(r)
        }
        // Newton's method from a Double guess, to 40 digits.
        var x = power(a, BigDecimal("0.5"))
        repeat(8) { x = x.add(a.divide(x, MC)).divide(BigDecimal(2), MC) }
        return x.round(MC)
    }

    private fun isqrt(n: BigInteger): BigInteger {
        if (n < BigInteger.valueOf(2)) return n
        var x = BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2)
        while (true) {
            val y = x.add(n.divide(x)).shiftRight(1)
            if (y >= x) return x
            x = y
        }
    }

    private fun factorial(a: BigDecimal): BigDecimal {
        if (!isWhole(a) || a.signum() < 0) throw Undefined()
        if (a > BigDecimal(3_200)) throw TooBig()
        var r = BigInteger.ONE
        for (i in 2..a.toInt()) r = r.multiply(BigInteger.valueOf(i.toLong()))
        return check(BigDecimal(r))
    }

    // ---------------------------------------------------------------- how answers read

    /** "1.4348907 x 10^37": [sig] significant digits and a power of ten. */
    private fun sci(v: BigDecimal, sig: Int, times: String, pow: String): String {
        val exp = size(v.abs()) - 1
        val m = v.movePointLeft(exp).round(MathContext(sig)).stripTrailingZeros().toPlainString()
        return "$m$times$pow$exp"
    }

    private fun group(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

    /** Every digit, grouped by thousands ("1,800,000"); decimals to 12 significant digits. */
    fun show(v: BigDecimal): String {
        if (isWhole(v)) {
            val s = v.toBigInteger().abs().toString()
            val body = if (s.length > 300) sci(v.abs(), 10, " x ", "10^") + " (${group(s.length.toString())} digits)" else group(s)
            return (if (v.signum() < 0) "-" else "") + body
        }
        val r = v.round(MathContext(12)).stripTrailingZeros()
        return if (r.abs() >= BigDecimal("1e15") || r.abs() < BigDecimal("1e-6")) sci(r, 12, " x ", "10^") else r.toPlainString()
    }

    /** How Brick says it: digits up to 15 of them, then "1.4348907 times 10 to the power of 37". */
    fun say(v: BigDecimal): String {
        val neg = v.signum() < 0
        val a = v.abs()
        val body = when {
            a.signum() == 0 -> "0"
            isWhole(a) && size(a) <= 15 -> a.toBigInteger().toString()
            !isWhole(a) && a < BigDecimal("1e15") && a >= BigDecimal("1e-6") -> a.round(MathContext(8)).stripTrailingZeros().toPlainString()
            else -> sci(a, 8, " times ", "10 to the power of ").replace("of -", "of negative ").removePrefix("1 times ")
        }
        return if (neg) "negative $body" else body
    }

    /** A number in a worked step: digits up to 20 of them, then "1.4348907 x 10^37". */
    fun plain(v: BigDecimal): String {
        val a = v.abs()
        val body = when {
            a.signum() == 0 -> "0"
            isWhole(a) && size(a) <= 20 -> a.toBigInteger().toString()
            !isWhole(a) && a < BigDecimal("1e15") && a >= BigDecimal("1e-6") -> a.round(MathContext(10)).stripTrailingZeros().toPlainString()
            else -> sci(a, 8, " x ", "10^")
        }
        return if (v.signum() < 0) "-$body" else body
    }

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "undefined"
        return say(BigDecimal(v.toString()))
    }

    /** A number as digits ("-3", "0.33333333"), for the worked steps. */
    fun plain(v: Double): String = if (v.isNaN() || v.isInfinite()) "undefined" else plain(BigDecimal(v.toString()))

    private fun speakable(human: String): String = human
        .replace("+", " plus ").replace("*", " times ").replace("/", " divided by ")
        .replace("^", " to the power of ").replace("=", " ").replace(Regex("(?<=\\d)!"), " factorial")
        .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), " times ")
        .replace(Regex("(?<=[\\d)])\\s*-\\s*(?=[\\d(])"), " minus ")
        .replace(Regex("\\s+"), " ").trim()

    /** The equation in symbols: "12 x 8 + 3", "sqrt 144", "5!". */
    private fun pretty(e: String): String = e.replace("*", " x ").replace("/", " / ").replace("+", " + ")
        .replace(Regex("(?<=[\\d)!])\\s*-\\s*"), " - ").replace("#", " mod ")
        .replace(Regex("(?<![a-z])(asin|acos|atan|sqrt|cbrt|sin|cos|tan|abs|exp|ln|log|logb\\d+|root\\d+)(?![a-z])"), "$1 ")
        .replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    // ---------------------------------------------------------------- the parser

    private class Parser(val s: String, val radians: Boolean) {
        var i = 0
        val steps = ArrayList<String>()

        fun step(a: BigDecimal, op: String, b: BigDecimal, r: BigDecimal): BigDecimal {
            steps += "${plain(a)} $op ${plain(b)} = ${plain(r)}"
            return r
        }

        fun parseAll(): BigDecimal {
            val v = expr()
            skip()
            if (i != s.length) throw IllegalArgumentException("Unexpected '${s[i]}'")
            return v
        }

        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skip(); return s.getOrNull(i) }

        fun expr(): BigDecimal {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; val b = term(); v = step(v, "+", b, check(v.add(b))) }
                    '-' -> { i++; val b = term(); v = step(v, "-", b, check(v.subtract(b))) }
                    else -> return v
                }
            }
        }

        fun term(): BigDecimal {
            var v = unary()
            while (true) {
                val c = peek()
                when {
                    c == '*' -> { i++; val b = unary(); v = step(v, "x", b, check(v.multiply(b))) }
                    c == '/' -> { i++; val b = unary(); v = step(v, "/", b, divide(v, b)) }
                    c == '#' -> { i++; val b = unary(); if (b.signum() == 0) throw Undefined(); v = step(v, "mod", b, check(v.remainder(b))) }
                    // Implicit multiplication: 2(3+4), 2pi, 3 sqrt 4.
                    c == '(' || (c != null && c.isLetter()) -> { val b = unary(); v = step(v, "x", b, check(v.multiply(b))) }
                    else -> return v
                }
            }
        }

        fun unary(): BigDecimal = when (peek()) {
            '-' -> { i++; unary().negate() }
            '+' -> { i++; unary() }
            else -> power()
        }

        fun power(): BigDecimal {
            val base = postfix()
            return if (peek() == '^') { i++; val b = unary(); step(base, "^", b, power(base, b)) } else base
        }

        fun postfix(): BigDecimal {
            var v = primary()
            while (peek() == '!') { i++; val r = factorial(v); steps += "${plain(v)}! = ${plain(r)}"; v = r }
            return v
        }

        fun func(name: String, a: BigDecimal): BigDecimal {
            fun rad(x: BigDecimal) = if (radians) x.toDouble() else Math.toRadians(x.toDouble())
            fun deg(x: Double) = if (radians) x else Math.toDegrees(x)
            val r = when {
                name == "sqrt" -> sqrt(a)
                name == "cbrt" -> power(a.abs(), BigDecimal.ONE.divide(BigDecimal(3), MC)).let { if (a.signum() < 0) it.negate() else it }
                name.startsWith("root") -> {
                    val n = name.removePrefix("root").toInt()
                    if (n == 0) throw Undefined()
                    if (a.signum() < 0 && n % 2 == 0) throw Undefined()
                    power(a.abs(), BigDecimal.ONE.divide(BigDecimal(n), MC)).let { if (a.signum() < 0) it.negate() else it }
                }
                name == "abs" -> a.abs()
                name == "sin" -> fromDouble(Math.sin(rad(a)))
                name == "cos" -> fromDouble(Math.cos(rad(a)))
                name == "tan" -> { val c = Math.cos(rad(a)); if (Math.abs(c) < 1e-12) throw Undefined(); fromDouble(Math.sin(rad(a)) / c) }
                name == "asin" -> { if (a.abs() > BigDecimal.ONE) throw Undefined(); fromDouble(deg(Math.asin(a.toDouble()))) }
                name == "acos" -> { if (a.abs() > BigDecimal.ONE) throw Undefined(); fromDouble(deg(Math.acos(a.toDouble()))) }
                name == "atan" -> fromDouble(deg(Math.atan(a.toDouble())))
                name == "exp" -> power(BigDecimal(Math.E.toString()), a)
                name == "ln" || name == "log" || name.startsWith("logb") -> {
                    if (a.signum() <= 0) throw Undefined()
                    val l10 = log10(a)
                    val r = when {
                        name == "log" -> l10
                        name == "ln" -> l10 * Math.log(10.0)
                        else -> { val b = name.removePrefix("logb").toDouble(); if (b <= 0 || b == 1.0) throw Undefined(); l10 / Math.log10(b) }
                    }
                    // log 1000 is exactly 3.
                    val near = Math.rint(r)
                    if (Math.abs(r - near) < 1e-12) BigDecimal(near.toLong()) else fromDouble(r)
                }
                else -> throw IllegalArgumentException(name)
            }
            val label = when {
                name.startsWith("root") -> "root${name.removePrefix("root")}"
                name.startsWith("logb") -> "log base ${name.removePrefix("logb")}"
                else -> name
            }
            val unit = if (name in setOf("sin", "cos", "tan") && !radians) " deg" else ""
            steps += "$label(${plain(a)}$unit) = ${plain(r)}"
            return r
        }

        fun primary(): BigDecimal {
            skip()
            val name = Regex("^(?:logb\\d+|root\\d+|asin|acos|atan|sqrt|cbrt|sin|cos|tan|abs|exp|ln|log)(?![a-z])").find(s.substring(i))?.value
            if (name != null) { i += name.length; return func(name, unary()) }
            if (Regex("^pi(?![a-z])").containsMatchIn(s.substring(i))) { i += 2; return BigDecimal("3.141592653589793238462643383279502884197") }
            if (Regex("^e(?![a-z])").containsMatchIn(s.substring(i))) { i += 1; return BigDecimal("2.718281828459045235360287471352662497757") }
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("Missing )")
                i++
                return v
            }
            val m = Regex("^\\d*\\.?\\d+(?:e[+-]?\\d+)?").find(s.substring(i)) ?: throw IllegalArgumentException("Number expected")
            i += m.value.length
            return BigDecimal(m.value).let { if (size(it) > MAX_DIGITS) throw TooBig() else it }
        }
    }
}

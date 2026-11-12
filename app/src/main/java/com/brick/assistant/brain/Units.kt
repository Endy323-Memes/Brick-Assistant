package com.brick.assistant.brain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** What a unit measures. Only units of the same kind convert. */
enum class Dimension(val label: String) {
    LENGTH("length"), MASS("weight"), TIME("time"), VOLUME("volume"), AREA("area"), SPEED("speed"),
    DATA("data"), TEMPERATURE("temperature"), ENERGY("energy"), PRESSURE("pressure"), ANGLE("angle"),
}

/**
 * A unit: [factor] times the dimension's base unit (meters, kilograms, seconds, liters, square meters, meters per
 * second, bytes, joules, pascals, degrees; temperature is special). [names] are what people call it, singular and
 * plural (the first is the spoken singular, the second the spoken plural), and [abbr] its short form.
 */
enum class MeasureUnit(val dim: Dimension, val factor: Double, val abbr: String, vararg val names: String) {
    // Length
    NANOMETER(Dimension.LENGTH, 1e-9, "nm", "nanometer", "nanometers", "nanometre", "nanometres"),
    MICROMETER(Dimension.LENGTH, 1e-6, "um", "micrometer", "micrometers", "micron", "microns", "micrometre", "micrometres"),
    MILLIMETER(Dimension.LENGTH, 1e-3, "mm", "millimeter", "millimeters", "millimetre", "millimetres", "mm"),
    CENTIMETER(Dimension.LENGTH, 1e-2, "cm", "centimeter", "centimeters", "centimetre", "centimetres", "cm", "cms"),
    METER(Dimension.LENGTH, 1.0, "m", "meter", "meters", "metre", "metres", "m"),
    KILOMETER(Dimension.LENGTH, 1000.0, "km", "kilometer", "kilometers", "kilometre", "kilometres", "km", "kms", "klick", "klicks"),
    INCH(Dimension.LENGTH, 0.0254, "in", "inch", "inches", "in"),
    FOOT(Dimension.LENGTH, 0.3048, "ft", "foot", "feet", "ft", "foots"),
    YARD(Dimension.LENGTH, 0.9144, "yd", "yard", "yards", "yd", "yds"),
    MILE(Dimension.LENGTH, 1609.344, "mi", "mile", "miles", "mi"),
    NAUTICAL_MILE(Dimension.LENGTH, 1852.0, "nmi", "nautical mile", "nautical miles"),
    LIGHT_YEAR(Dimension.LENGTH, 9.4607304725808e15, "ly", "light year", "light years", "lightyear", "lightyears"),

    // Mass
    MICROGRAM(Dimension.MASS, 1e-9, "ug", "microgram", "micrograms", "mcg"),
    MILLIGRAM(Dimension.MASS, 1e-6, "mg", "milligram", "milligrams", "milligramme", "milligrammes", "mg", "mgs"),
    GRAM(Dimension.MASS, 1e-3, "g", "gram", "grams", "gramme", "grammes", "g", "gs"),
    KILOGRAM(Dimension.MASS, 1.0, "kg", "kilogram", "kilograms", "kilo", "kilos", "kg", "kgs", "kilogramme", "kilogrammes"),
    METRIC_TON(Dimension.MASS, 1000.0, "t", "metric ton", "metric tons", "tonne", "tonnes"),
    OUNCE(Dimension.MASS, 0.028349523125, "oz", "ounce", "ounces", "oz"),
    POUND(Dimension.MASS, 0.45359237, "lb", "pound", "pounds", "lb", "lbs"),
    STONE(Dimension.MASS, 6.35029318, "st", "stone", "stones"),
    TON(Dimension.MASS, 907.18474, "ton", "ton", "tons", "short ton", "short tons"),
    CARAT(Dimension.MASS, 0.0002, "ct", "carat", "carats"),

    // Time
    NANOSECOND(Dimension.TIME, 1e-9, "ns", "nanosecond", "nanoseconds", "ns"),
    MICROSECOND(Dimension.TIME, 1e-6, "us", "microsecond", "microseconds"),
    MILLISECOND(Dimension.TIME, 1e-3, "ms", "millisecond", "milliseconds", "ms", "millis"),
    SECOND(Dimension.TIME, 1.0, "s", "second", "seconds", "sec", "secs", "s"),
    MINUTE(Dimension.TIME, 60.0, "min", "minute", "minutes", "min", "mins"),
    HOUR(Dimension.TIME, 3600.0, "h", "hour", "hours", "hr", "hrs", "h"),
    DAY(Dimension.TIME, 86400.0, "d", "day", "days"),
    WEEK(Dimension.TIME, 604800.0, "wk", "week", "weeks", "wk", "wks"),
    FORTNIGHT(Dimension.TIME, 1209600.0, "fortnight", "fortnight", "fortnights"),
    MONTH(Dimension.TIME, 2629746.0, "mo", "month", "months"), // an average month: 30.44 days
    YEAR(Dimension.TIME, 31536000.0, "yr", "year", "years", "yr", "yrs"), // 365 days
    LEAP_YEAR(Dimension.TIME, 31622400.0, "leap yr", "leap year", "leap years"),
    DECADE(Dimension.TIME, 315360000.0, "decade", "decade", "decades"),
    CENTURY(Dimension.TIME, 3153600000.0, "century", "century", "centuries"),
    MILLENNIUM(Dimension.TIME, 31536000000.0, "millennium", "millennium", "millennia", "millenniums"),

    // Volume
    MILLILITER(Dimension.VOLUME, 1e-3, "ml", "milliliter", "milliliters", "millilitre", "millilitres", "ml", "mls"),
    CENTILITER(Dimension.VOLUME, 1e-2, "cl", "centiliter", "centiliters", "centilitre", "centilitres", "cl"),
    DECILITER(Dimension.VOLUME, 0.1, "dl", "deciliter", "deciliters", "decilitre", "decilitres", "dl"),
    LITER(Dimension.VOLUME, 1.0, "L", "liter", "liters", "litre", "litres", "l"),
    CUBIC_METER(Dimension.VOLUME, 1000.0, "m3", "cubic meter", "cubic meters", "cubic metre", "cubic metres"),
    TEASPOON(Dimension.VOLUME, 0.00492892159375, "tsp", "teaspoon", "teaspoons", "tsp", "tsps"),
    TABLESPOON(Dimension.VOLUME, 0.01478676478125, "tbsp", "tablespoon", "tablespoons", "tbsp", "tbsps", "tbs"),
    FLUID_OUNCE(Dimension.VOLUME, 0.0295735295625, "fl oz", "fluid ounce", "fluid ounces", "fl oz"),
    CUP(Dimension.VOLUME, 0.2365882365, "cup", "cup", "cups"),
    PINT(Dimension.VOLUME, 0.473176473, "pt", "pint", "pints", "pt"),
    QUART(Dimension.VOLUME, 0.946352946, "qt", "quart", "quarts", "qt"),
    GALLON(Dimension.VOLUME, 3.785411784, "gal", "gallon", "gallons", "gal"),
    CUBIC_INCH(Dimension.VOLUME, 0.016387064, "in3", "cubic inch", "cubic inches"),
    CUBIC_FOOT(Dimension.VOLUME, 28.316846592, "ft3", "cubic foot", "cubic feet"),

    // Area
    SQ_MILLIMETER(Dimension.AREA, 1e-6, "mm2", "square millimeter", "square millimeters", "square millimetre", "square millimetres"),
    SQ_CENTIMETER(Dimension.AREA, 1e-4, "cm2", "square centimeter", "square centimeters", "square centimetre", "square centimetres"),
    SQ_METER(Dimension.AREA, 1.0, "m2", "square meter", "square meters", "square metre", "square metres"),
    SQ_KILOMETER(Dimension.AREA, 1e6, "km2", "square kilometer", "square kilometers", "square kilometre", "square kilometres"),
    SQ_INCH(Dimension.AREA, 0.00064516, "in2", "square inch", "square inches"),
    SQ_FOOT(Dimension.AREA, 0.09290304, "ft2", "square foot", "square feet", "sq ft"),
    SQ_YARD(Dimension.AREA, 0.83612736, "yd2", "square yard", "square yards"),
    ACRE(Dimension.AREA, 4046.8564224, "ac", "acre", "acres"),
    HECTARE(Dimension.AREA, 10000.0, "ha", "hectare", "hectares"),
    SQ_MILE(Dimension.AREA, 2589988.110336, "mi2", "square mile", "square miles"),

    // Speed
    METERS_PER_SECOND(Dimension.SPEED, 1.0, "m/s", "meter per second", "meters per second", "metres per second"),
    KPH(Dimension.SPEED, 1 / 3.6, "km/h", "kilometer per hour", "kilometers per hour", "kilometres per hour", "kph", "km/h", "kmh"),
    MPH(Dimension.SPEED, 0.44704, "mph", "mile per hour", "miles per hour", "mph"),
    KNOT(Dimension.SPEED, 0.514444444, "kn", "knot", "knots"),
    FEET_PER_SECOND(Dimension.SPEED, 0.3048, "ft/s", "foot per second", "feet per second"),

    // Data
    BIT(Dimension.DATA, 0.125, "bit", "bit", "bits"),
    BYTE(Dimension.DATA, 1.0, "B", "byte", "bytes"),
    KILOBYTE(Dimension.DATA, 1e3, "KB", "kilobyte", "kilobytes", "kb"),
    MEGABYTE(Dimension.DATA, 1e6, "MB", "megabyte", "megabytes", "mb", "megs"),
    GIGABYTE(Dimension.DATA, 1e9, "GB", "gigabyte", "gigabytes", "gb", "gigs"),
    TERABYTE(Dimension.DATA, 1e12, "TB", "terabyte", "terabytes", "tb"),
    PETABYTE(Dimension.DATA, 1e15, "PB", "petabyte", "petabytes", "pb"),
    KIBIBYTE(Dimension.DATA, 1024.0, "KiB", "kibibyte", "kibibytes"),
    MEBIBYTE(Dimension.DATA, 1048576.0, "MiB", "mebibyte", "mebibytes"),
    GIBIBYTE(Dimension.DATA, 1073741824.0, "GiB", "gibibyte", "gibibytes"),
    KILOBIT(Dimension.DATA, 125.0, "kbit", "kilobit", "kilobits"),
    MEGABIT(Dimension.DATA, 125000.0, "Mbit", "megabit", "megabits"),
    GIGABIT(Dimension.DATA, 1.25e8, "Gbit", "gigabit", "gigabits"),

    // Temperature (factor unused)
    CELSIUS(Dimension.TEMPERATURE, 1.0, "C", "degree celsius", "degrees celsius", "celsius", "centigrade", "degrees c", "c"),
    FAHRENHEIT(Dimension.TEMPERATURE, 1.0, "F", "degree fahrenheit", "degrees fahrenheit", "fahrenheit", "degrees f", "f"),
    KELVIN(Dimension.TEMPERATURE, 1.0, "K", "kelvin", "kelvin", "kelvins"),

    // Energy
    JOULE(Dimension.ENERGY, 1.0, "J", "joule", "joules"),
    KILOJOULE(Dimension.ENERGY, 1000.0, "kJ", "kilojoule", "kilojoules", "kj"),
    CALORIE(Dimension.ENERGY, 4.184, "cal", "calorie", "calories", "cal", "small calorie", "small calories"),
    KILOCALORIE(Dimension.ENERGY, 4184.0, "kcal", "kilocalorie", "kilocalories", "kcal", "food calorie", "food calories"),
    KILOWATT_HOUR(Dimension.ENERGY, 3.6e6, "kWh", "kilowatt hour", "kilowatt hours", "kwh"),

    // Pressure
    PASCAL(Dimension.PRESSURE, 1.0, "Pa", "pascal", "pascals"),
    KILOPASCAL(Dimension.PRESSURE, 1000.0, "kPa", "kilopascal", "kilopascals", "kpa"),
    BAR(Dimension.PRESSURE, 1e5, "bar", "bar", "bars"),
    PSI(Dimension.PRESSURE, 6894.757293168, "psi", "psi", "psi", "pound per square inch", "pounds per square inch"),
    ATMOSPHERE(Dimension.PRESSURE, 101325.0, "atm", "atmosphere", "atmospheres", "atm"),

    // Angle
    DEGREE(Dimension.ANGLE, 1.0, "deg", "degree", "degrees"),
    RADIAN(Dimension.ANGLE, 57.29577951308232, "rad", "radian", "radians", "rad"),
    TURN(Dimension.ANGLE, 360.0, "turn", "full turn", "full turns", "revolution", "revolutions", "rotation", "rotations"),
    ;

    val singular: String get() = names[0]
    val plural: String get() = names.getOrElse(1) { names[0] }

    fun spoken(amount: Double) = if (abs(amount - 1.0) < 1e-9) singular else plural

    companion object {
        /** Every name, longest first, so "fluid ounces" wins over "ounces" and "square feet" over "feet". */
        private val byName: List<Pair<String, MeasureUnit>> =
            entries.flatMap { u -> u.names.map { it.lowercase(Locale.US) to u } }.sortedByDescending { it.first.length }

        /** Alternation of every name, for the request patterns. */
        val pattern: String by lazy { byName.joinToString("|") { Regex.escape(it.first) } }

        fun named(s: String): MeasureUnit? {
            val t = s.lowercase(Locale.US).trim().replace(Patterns.get("\\s+"), " ").removePrefix("a ").removePrefix("an ").removePrefix("one ")
            return byName.firstOrNull { it.first == t }?.second
        }
    }
}

/** One conversion, worked out. */
data class Conversion(val amount: Double, val from: MeasureUnit, val to: MeasureUnit, val result: Double) {
    /** "1 second is 1,000 milliseconds." */
    val spoken: String get() = "${Units.format(amount)} ${from.spoken(amount)} is ${Units.format(result)} ${to.spoken(result)}."
}

/** Unit conversions: "seconds to milliseconds", "how many days are in a year", "5 feet in inches". */
object Units {
    fun convert(amount: Double, from: MeasureUnit, to: MeasureUnit): Double? {
        if (from.dim != to.dim) return null
        if (from.dim == Dimension.TEMPERATURE) {
            val c = when (from) {
                MeasureUnit.CELSIUS -> amount
                MeasureUnit.FAHRENHEIT -> (amount - 32) * 5 / 9
                else -> amount - 273.15
            }
            return when (to) {
                MeasureUnit.CELSIUS -> c
                MeasureUnit.FAHRENHEIT -> c * 9 / 5 + 32
                else -> c + 273.15
            }
        }
        return amount * from.factor / to.factor
    }

    fun conversion(amount: Double, from: MeasureUnit, to: MeasureUnit): Conversion? = convert(amount, from, to)?.let { Conversion(amount, from, to, it) }

    /** Up to 7 significant digits, thousands separated, no trailing zeros; tiny and huge numbers in plain words. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e15 || a < 1e-6) {
            val exp = floor(log10(a)).toInt()
            val mant = v / 10.0.pow(exp)
            return "%s times 10 to the %d".format(Locale.US, trim("%.4f".format(Locale.US, mant)), exp)
        }
        val digits = (7 - 1 - floor(log10(a)).toInt()).coerceIn(0, 10)
        val rounded = if (digits == 0) v.roundToLong().toDouble() else v
        val s = "%,.${digits}f".format(Locale.US, rounded)
        return trim(s)
    }

    private fun trim(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    /** The number said before a unit: "5", "2.5", "1,000", "a", "an", "one", "half a", "a quarter". */
    fun amount(s: String?): Double? {
        val t = s?.trim()?.lowercase(Locale.US).orEmpty()
        if (t.isEmpty() || t == "a" || t == "an" || t == "one" || t == "per" || t == "each" || t == "every" || t == "1") return 1.0
        if (t == "half a" || t == "half an" || t == "a half" || t == "half") return 0.5
        if (t == "a quarter" || t == "a quarter of a" || t == "quarter") return 0.25
        Patterns.get("^(\\d+)\\s*/\\s*(\\d+)$").find(t)?.let { return it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
        return t.replace(",", "").toDoubleOrNull()
    }

    private val NUM = "(?:-?\\d[\\d,]*(?:\\.\\d+)?|\\d+\\s*/\\s*\\d+|half an?|a half|a quarter(?: of an?)?|an?|one)"

    /**
     * Understands a conversion in everyday wording, or null:
     *  "convert 5 feet to inches", "5 kg in mg", "what's 10 miles in km", "seconds to milliseconds",
     *  "how many inches are in a foot", "how many milliseconds in a second", "how many ounces make a pound",
     *  "how many days does a year have", "days in a year", "how many feet is 3 miles", "100 f in c".
     */
    fun parse(raw: String): Conversion? {
        val s = raw.lowercase(Locale.US).replace(Patterns.get("[?!]"), " ").replace(Patterns.get("\\s+"), " ").trim()
            // "1,000" arrives as "1 000" once commas are cleaned out.
            .replace(Patterns.get("\\b(\\d{1,3})((?: \\d{3})+)\\b")) { it.groupValues[1] + it.groupValues[2].replace(" ", "") }
            .replace(Patterns.get("^(?:hey brick |brick |ok |okay |so |um |uh )+"), "")
            .replace(Patterns.get("^(?:can you |could you |please |would you |do you know |tell me |i want to know |i need to know |quick question )+"), "")
            .replace(Patterns.get("\\s+(?:please|for me|exactly|again|real quick|roughly|approximately|about)$"), "")
            .trim()
        val u = MeasureUnit.pattern
        val lead = "(?:(?:convert|change|turn|translate|switch)\\s+|(?:what(?:'s| is| are)|how much is|how much are|how many is|calculate|figure out|work out)\\s+)?"
        val join = "(?:to|into|in to|in|as|=|equals?|equal to|is how many|are how many|is what in|in terms of)"
        // "5 feet to inches", "convert 5 feet into inches", "what's 10 miles in km", "seconds to milliseconds"
        Patterns.get("^$lead(?:($NUM)\\s*)?($u)\\s+$join\\s+(?:how many\\s+)?($u)$").find(s)?.let { m ->
            return build(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        // "how many inches are in a foot", "how many milliseconds are there in 1 second", "how many ounces make a pound",
        // "how many cups in a gallon", "how many feet is 3 miles", "how many grams per kilogram"
        Patterns.get("^how (?:many|much)\\s+($u)\\s+(?:are |is |go |fit |make up |make |does it take |do you need |would )?(?:there |it take |be )?(?:in |into |to make |to make up |per |for |in a single |is |are |make |equals? |equal to )?($NUM)?\\s*($u)(?: equal| make| have| hold| contain| be)?$").find(s)?.let { m ->
            return build(m.groupValues[2], m.groupValues[3], m.groupValues[1])
        }
        // "how many days does a year have", "how many seconds does a minute have"
        Patterns.get("^how many\\s+($u)\\s+(?:does|do|are there in|can)\\s+($NUM)?\\s*($u)\\s+(?:have|hold|contain|last|take)$").find(s)?.let { m ->
            return build(m.groupValues[2], m.groupValues[3], m.groupValues[1])
        }
        // "days in a year", "seconds in a minute", "feet in a mile"
        Patterns.get("^(?:the )?(?:number of )?($u)\\s+(?:in|per|to)\\s+(?:a |an |one )?($NUM)?\\s*($u)$").find(s)?.let { m ->
            return build(m.groupValues[2], m.groupValues[3], m.groupValues[1])
        }
        // "a foot is how many inches", "1 mile is how many feet"
        Patterns.get("^($NUM)?\\s*($u)\\s+(?:is|are|equals?)\\s+how many\\s+($u)$").find(s)?.let { m ->
            return build(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        return null
    }

    private fun build(amount: String, from: String, to: String): Conversion? {
        val f = MeasureUnit.named(from) ?: return null
        val t = MeasureUnit.named(to) ?: return null
        if (f == t) return null
        // One-letter names only count for temperature with a number ("100 f in c"); "m in s" isn't anything.
        if ((from.trim().length == 1 || to.trim().length == 1) && f.dim != Dimension.TEMPERATURE) {
            if (f.dim != t.dim) return null
        }
        val a = amount(amount) ?: return null
        return conversion(a, f, t)
    }

    /** A few more units of the same kind for the readout ("1 day: 24 hours, 1,440 minutes"). */
    fun related(c: Conversion, count: Int = 4): List<Pair<MeasureUnit, Double>> {
        val near = MeasureUnit.entries.filter { it.dim == c.from.dim && it != c.from && it != c.to && it.names.size >= 2 }
        return near.mapNotNull { u -> convert(c.amount, c.from, u)?.let { u to it } }
            .filter { (_, v) -> abs(v) in 0.001..1e9 }
            .sortedBy { (_, v) -> abs(log10(abs(v))) }
            .take(count)
    }
}

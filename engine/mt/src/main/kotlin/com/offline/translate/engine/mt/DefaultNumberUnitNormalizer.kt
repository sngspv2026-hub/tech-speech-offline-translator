package com.offline.translate.engine.mt

import com.offline.translate.engine.api.NumberUnitNormalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic spoken number and technical SI/industry measurement unit normalizer
 * supporting Russian (ru), English (en), and Turkish (tr), plus inline Imperial -> SI conversion
 * (psi -> MPa, in -> mm, ft -> m, °F -> °C, gpm -> L/min).
 */
@Singleton
class DefaultNumberUnitNormalizer @Inject constructor() : NumberUnitNormalizer {

    private val ruUnits = mapOf(
        "мегапаскаль" to "MPa", "мегапаскаля" to "MPa", "мегапаскалей" to "MPa", "мпа" to "MPa",
        "килопаскаль" to "kPa", "килопаскаля" to "kPa", "килопаскалей" to "kPa", "кпа" to "kPa",
        "паскаль" to "Pa", "паскаля" to "Pa", "паскалей" to "Pa",
        "бар" to "bar", "бара" to "bar", "баров" to "bar",
        "фунт на квадратный дюйм" to "psi", "пси" to "psi",
        "дюйм" to "in", "дюйма" to "in", "дюймов" to "in",
        "фут" to "ft", "фута" to "ft", "футов" to "ft",
        "вольт" to "V", "вольта" to "V", "вольтов" to "V",
        "киловольт" to "kV", "киловольта" to "kV", "киловольтов" to "kV",
        "милливольт" to "mV", "милливольта" to "mV",
        "ампер" to "A", "ампера" to "A", "амперов" to "A",
        "килоампер" to "kA", "килоампера" to "kA", "килоамперов" to "kA",
        "миллиампер" to "mA", "миллиампера" to "mA",
        "ватт" to "W", "ватта" to "W",
        "киловатт" to "kW", "киловатта" to "kW",
        "мегаватт" to "MW", "мегаватта" to "MW",
        "герц" to "Hz", "герца" to "Hz",
        "килогерц" to "kHz", "килогерца" to "kHz",
        "мегагерц" to "MHz", "мегагерца" to "MHz",
        "гигагерц" to "GHz", "гигагерца" to "GHz",
        "ом" to "Ω", "ома" to "Ω", "омов" to "Ω",
        "килоом" to "kΩ", "килоома" to "kΩ",
        "мегаом" to "MΩ", "мегаома" to "MΩ",
        "миллиметр" to "mm", "миллиметра" to "mm", "миллиметров" to "mm",
        "микрометр" to "μm", "микрометра" to "μm", "микрометров" to "μm", "микрон" to "μm",
        "сантиметр" to "cm", "сантиметра" to "cm", "сантиметров" to "cm",
        "метр" to "m", "метра" to "m", "метров" to "m",
        "килограмм" to "kg", "килограмма" to "kg", "килограммов" to "kg",
        "гигабайт" to "GB", "гигабайта" to "GB",
        "мегабайт" to "MB", "мегабайта" to "MB",
        "миллисекунд" to "ms", "миллисекунды" to "ms", "миллисекунда" to "ms"
    )

    private val enUnits = mapOf(
        "megapascal" to "MPa", "megapascals" to "MPa",
        "kilopascal" to "kPa", "kilopascals" to "kPa",
        "pascal" to "Pa", "pascals" to "Pa",
        "bar" to "bar", "bars" to "bar",
        "psi" to "psi",
        "inch" to "in", "inches" to "in",
        "foot" to "ft", "feet" to "ft",
        "gpm" to "gpm",
        "volt" to "V", "volts" to "V",
        "kilovolt" to "kV", "kilovolts" to "kV",
        "millivolt" to "mV", "millivolts" to "mV",
        "ampere" to "A", "amperes" to "A", "amp" to "A", "amps" to "A",
        "kiloampere" to "kA", "kiloamperes" to "kA",
        "milliampere" to "mA", "milliamperes" to "mA",
        "watt" to "W", "watts" to "W",
        "kilowatt" to "kW", "kilowatts" to "kW",
        "megawatt" to "MW", "megawatts" to "MW",
        "hertz" to "Hz", "kilohertz" to "kHz", "megahertz" to "MHz", "gigahertz" to "GHz",
        "ohm" to "Ω", "ohms" to "Ω", "kiloohm" to "kΩ", "kiloohms" to "kΩ",
        "millimeter" to "mm", "millimeters" to "mm", "millimetre" to "mm", "millimetres" to "mm",
        "micrometer" to "μm", "micrometers" to "μm", "micron" to "μm", "microns" to "μm",
        "centimeter" to "cm", "centimeters" to "cm",
        "meter" to "m", "meters" to "m",
        "kilogram" to "kg", "kilograms" to "kg",
        "gigabyte" to "GB", "gigabytes" to "GB",
        "megabyte" to "MB", "megabytes" to "MB",
        "millisecond" to "ms", "milliseconds" to "ms"
    )

    private val trUnits = mapOf(
        "megapaskal" to "MPa",
        "kilopaskal" to "kPa",
        "paskal" to "Pa",
        "bar" to "bar",
        "psi" to "psi",
        "inç" to "in",
        "fit" to "ft",
        "volt" to "V", "kilovolt" to "kV", "milivolt" to "mV",
        "amper" to "A", "kiloamper" to "kA", "miliamper" to "mA", "kiloamperdir" to "kA",
        "vat" to "W", "watt" to "W", "kilovat" to "kW", "megavat" to "MW",
        "hertz" to "Hz", "kilohertz" to "kHz", "megahertz" to "MHz", "gigahertz" to "GHz",
        "ohm" to "Ω", "kiloohm" to "kΩ",
        "milimetre" to "mm", "mikrometre" to "μm", "santimetre" to "cm", "metre" to "m",
        "kilogram" to "kg",
        "gigabayt" to "GB", "megabayt" to "MB", "milisaniye" to "ms"
    )

    private val ruNumbers = mapOf(
        "ноль" to 0L, "нуль" to 0L,
        "один" to 1L, "одна" to 1L, "одно" to 1L, "одного" to 1L,
        "два" to 2L, "две" to 2L, "двух" to 2L,
        "три" to 3L, "трех" to 3L, "трёх" to 3L,
        "четыре" to 4L, "четырех" to 4L, "четырёх" to 4L,
        "пять" to 5L, "пяти" to 5L,
        "шесть" to 6L, "шести" to 6L,
        "семь" to 7L, "семи" to 7L,
        "восемь" to 8L, "восьми" to 8L,
        "девять" to 9L, "девяти" to 9L,
        "десять" to 10L, "десяти" to 10L,
        "одиннадцать" to 11L, "двенадцать" to 12L, "тринадцать" to 13L,
        "четырнадцать" to 14L, "пятнадцать" to 15L, "шестнадцать" to 16L,
        "семнадцать" to 17L, "восемнадцать" to 18L, "девятнадцать" to 19L,
        "двадцать" to 20L, "двадцати" to 20L,
        "тридцать" to 30L, "тридцати" to 30L,
        "сорок" to 40L, "сорока" to 40L,
        "пятьдесят" to 50L, "пятидесяти" to 50L,
        "шестьдесят" to 60L, "шестидесяти" to 60L,
        "семьдесят" to 70L, "семидесяти" to 70L,
        "восемьдесят" to 80L, "восьмидесяти" to 80L,
        "девяносто" to 90L, "девяноста" to 90L,
        "сто" to 100L, "ста" to 100L,
        "двести" to 200L, "двухсот" to 200L,
        "триста" to 300L, "четыреста" to 400L, "пятьсот" to 500L,
        "шестьсот" to 600L, "семьсот" to 700L, "восемьсот" to 800L, "девятьсот" to 900L,
        "тысяча" to 1000L, "тысячи" to 1000L, "тысяч" to 1000L
    )

    private val enNumbers = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L, "twenty" to 20L, "thirty" to 30L,
        "forty" to 40L, "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
        "eighty" to 80L, "ninety" to 90L, "hundred" to 100L, "thousand" to 1000L
    )

    private val trNumbers = mapOf(
        "sıfır" to 0L, "bir" to 1L, "iki" to 2L, "üç" to 3L, "dört" to 4L,
        "beş" to 5L, "altı" to 6L, "yedi" to 7L, "sekiz" to 8L, "dokuz" to 9L,
        "on" to 10L, "yirmi" to 20L, "otuz" to 30L, "kırk" to 40L,
        "elli" to 50L, "altmış" to 60L, "yetmiş" to 70L, "seksen" to 80L,
        "doksan" to 90L, "yüz" to 100L, "bin" to 1000L
    )

    private val imperialRegex = Regex(
        """(?<!\[≈\s)\b(\d+(?:\.\d+)?)\s*(psi|in|inch|inches|ft|feet|°F|gpm)\b(?!\s*\[≈)""",
        RegexOption.IGNORE_CASE
    )

    override fun normalize(text: String, languageCode: String): String {
        if (text.isBlank()) return text
        val lang = languageCode.lowercase()

        var working = text
            .replace(Regex("""градус(?:а|ов)?\s+Цельсия""", RegexOption.IGNORE_CASE), "°C")
            .replace(Regex("""degrees?\s+Celsius""", RegexOption.IGNORE_CASE), "°C")
            .replace(Regex("""santigrat\s+derece(?:de|dir)?""", RegexOption.IGNORE_CASE), "°C")
            .replace(Regex("""градус(?:а|ов)?\s+Фаренгейта""", RegexOption.IGNORE_CASE), "°F")
            .replace(Regex("""degrees?\s+Fahrenheit""", RegexOption.IGNORE_CASE), "°F")
            .replace(Regex("""fahrenhayt\s+derece(?:de|dir)?""", RegexOption.IGNORE_CASE), "°F")

        val numberDict = when (lang) {
            "ru" -> ruNumbers
            "tr" -> trNumbers
            else -> enNumbers
        }
        val unitDict = when (lang) {
            "ru" -> ruUnits
            "tr" -> trUnits
            else -> enUnits
        }

        val tokens = working.split(Regex("""\s+"""))
        val output = mutableListOf<String>()
        var i = 0

        while (i < tokens.size) {
            val cleanWord = tokens[i].lowercase().trimEnd(',', '.', ';', ':')
            val punctuationSuffix = tokens[i].substring(cleanWord.length)

            if (numberDict.containsKey(cleanWord)) {
                var currentSum = 0L
                var currentGroup = 0L
                var j = i
                while (j < tokens.size) {
                    val w = tokens[j].lowercase().trimEnd(',', '.', ';', ':')
                    val numVal = numberDict[w] ?: break
                    if (numVal == 100L) {
                        currentGroup = (if (currentGroup == 0L) 1L else currentGroup) * 100L
                    } else if (numVal == 1000L) {
                        currentGroup = (if (currentGroup == 0L) 1L else currentGroup) * 1000L
                        currentSum += currentGroup
                        currentGroup = 0L
                    } else {
                        currentGroup += numVal
                    }
                    j++
                }
                val totalNumber = currentSum + currentGroup
                if (j + 2 < tokens.size && tokens[j].lowercase() in setOf("целых", "целая")) {
                    val fracWord = tokens[j + 1].lowercase()
                    val fracScale = tokens[j + 2].lowercase()
                    val fracVal = numberDict[fracWord]
                    if (fracVal != null && fracScale.startsWith("десят")) {
                        output.add("$totalNumber.$fracVal")
                        j += 3
                    } else {
                        output.add(totalNumber.toString())
                    }
                } else if (j + 1 < tokens.size && tokens[j].lowercase() in setOf("point", "nokta", "virgül", "точка")) {
                    val fracWord = tokens[j + 1].lowercase().trimEnd(',', '.', ';', ':')
                    val fracVal = numberDict[fracWord]
                    if (fracVal != null) {
                        output.add("$totalNumber.$fracVal")
                        j += 2
                    } else {
                        output.add(totalNumber.toString())
                    }
                } else {
                    output.add(totalNumber.toString())
                }

                if (j < tokens.size) {
                    val nextClean = tokens[j].lowercase().trimEnd(',', '.', ';', ':')
                    val nextPunct = tokens[j].substring(nextClean.length)
                    val mappedUnit = unitDict[nextClean]
                    if (mappedUnit != null) {
                        output.add(mappedUnit + nextPunct)
                        j++
                    }
                }
                i = j
            } else {
                val mappedUnit = unitDict[cleanWord]
                val prevIsNumeric = output.lastOrNull()?.matches(Regex("""^-?\d+(\.\d+)?$""")) == true
                if (mappedUnit != null && prevIsNumeric) {
                    output.add(mappedUnit + punctuationSuffix)
                } else {
                    output.add(tokens[i])
                }
                i++
            }
        }

        return output.joinToString(" ")
    }

    /**
     * Converts non-metric / Imperial engineering units inline to SI units in brackets:
     * - 150 psi -> 150 psi [≈ 1.03 MPa]
     * - 2 in -> 2 in [50.8 mm]
     * - 10 ft -> 10 ft [3.05 m]
     * - 212 °F -> 212 °F [100 °C]
     * - 10 gpm -> 10 gpm [37.9 L/min]
     */
    override fun convertImperialToSiInline(text: String): String {
        if (text.isBlank()) return text
        return imperialRegex.replace(text) { match ->
            val rawVal = match.groupValues[1].toDoubleOrNull() ?: return@replace match.value
            val unitRaw = match.groupValues[2]
            val unitLower = unitRaw.lowercase()
            val siAnnotation = when (unitLower) {
                "psi" -> {
                    val mpa = rawVal * 0.006894757
                    "[≈ ${String.format(Locale.US, "%.2f", mpa)} MPa]"
                }
                "in", "inch", "inches" -> {
                    val mm = rawVal * 25.4
                    "[${String.format(Locale.US, "%.1f", mm)} mm]"
                }
                "ft", "feet" -> {
                    val meters = rawVal * 0.3048
                    "[${String.format(Locale.US, "%.2f", meters)} m]"
                }
                "°f" -> {
                    val celsius = (rawVal - 32.0) * 5.0 / 9.0
                    val formatted = if (celsius % 1.0 == 0.0) {
                        String.format(Locale.US, "%.0f", celsius)
                    } else {
                        String.format(Locale.US, "%.1f", celsius)
                    }
                    "[$formatted °C]"
                }
                "gpm" -> {
                    val lpm = rawVal * 3.78541
                    "[≈ ${String.format(Locale.US, "%.1f", lpm)} L/min]"
                }
                else -> return@replace match.value
            }
            "${match.groupValues[1]} $unitRaw $siAnnotation"
        }
    }
}

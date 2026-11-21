package com.offline.translate.engine.mt

import com.offline.translate.engine.api.TechnicalOcrCleaner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cleans typical optical character recognition (OCR) artifacts from industrial equipment nameplates,
 * PLC error screens, and engineering blueprints before translation:
 * - Fixes O/0 and I/1/B/8 character confusions in engineering designations (e.g., "DN-5O" -> "DN-50",
 *   "IP6B" -> "IP68", "38OV 5OHz" -> "380 V 50 Hz", "M12x1,75" -> "M12x1.75").
 * - Joins hyphenated line breaks and normalizes whitespace.
 */
@Singleton
class TechnicalOcrCleanerImpl @Inject constructor() : TechnicalOcrCleaner {

    override fun cleanOcrText(rawOcrText: String): String {
        if (rawOcrText.isBlank()) return rawOcrText

        val cleaned = rawOcrText
            // Join hyphenated words broken across lines on a nameplate
            .replace(Regex("""([а-яА-Яa-zA-Z])-\s*\r?\n\s*([а-яА-Яa-zA-Z])"""), "$1$2")
            // Replace newlines with spaces
            .replace(Regex("""\r?\n+"""), " ")
            // Fix letter 'O' mistaken for digit '0' in article codes (e.g. DN-5O -> DN-50)
            .replace(Regex("""\b([A-Z]{1,4}-\d*)O\b""")) { "${it.groupValues[1]}0" }
            // Fix letter 'O' mistaken for digit '0' before electrical/pressure units (e.g. 38OV -> 380V, 5OHz -> 50Hz)
            .replace(Regex("""\b(\d+)O(?=\d|\s*(?:V|A|Hz|MPa|kPa|kW|W|bar)\b)""")) { "${it.groupValues[1]}0" }
            // Fix IP6B -> IP68, IP6S -> IP65
            .replace(Regex("""\bIP([0-6])B\b"""), "IP$18")
            .replace(Regex("""\bIP([0-6])S\b"""), "IP$15")
            // Normalize metric thread comma to dot (M12x1,75 -> M12x1.75)
            .replace(Regex("""\b(M\d+x\d+),(\d+)\b"""), "$1.$2")
            // Separate glued numbers and electrical units (380V -> 380 V, 50Hz -> 50 Hz)
            .replace(Regex("""\b(\d+(?:\.\d+)?)(V|kV|A|kA|Hz|MHz|kW|MPa|bar)\b"""), "$1 $2")

        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }
}

package com.offline.translate.engine.mt

import com.offline.translate.core.database.GlossaryDao
import com.offline.translate.core.database.GlossaryTermEntity
import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.GlossaryEntry
import com.offline.translate.core.model.TechnicalDomain
import com.offline.translate.engine.api.PreprocessedTermPayload
import com.offline.translate.engine.api.TermAdapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements the technical terminology layer:
 * 1. Built-in seed glossaries across all 5 technical domains (IT, Mechanical, Electrical, Construction, Oil&Gas) for ru/en/tr.
 * 2. User glossaries with higher priority (100 vs 50) stored in Room.
 * 3. CSV and TBX (ISO 30042 XML) import and export.
 * 4. Compact zlib+Base64 (`TPKGLOSS1:...`) QR-ready offline glossary sharing between devices.
 * 5. ASR Hotwords extraction with boost weights for contextual biasing.
 * 6. Shielding of code identifiers (camelCase, snake_case), article numbers (DN-50, M12x1.75, IP68),
 *    normalized units (5 MPa), and inline SI annotations (`[≈ 1.03 MPa]`).
 */
@Singleton
class DefaultGlossaryTermAdapter @Inject constructor(
    private val glossaryDao: GlossaryDao
) : TermAdapter {

    private val nonTranslatableTokenRegex = Regex(
        """(?:\[≈?\s*\d+(?:\.\d+)?\s*(?:MPa|kPa|Pa|bar|mm|m|°C|L/min)\]|\b(?:[a-z]+[A-Z][a-zA-Z0-9]*|[a-zA-Z0-9]+_[a-zA-Z0-9_]+|[A-Z]{2,}-\d+[A-Za-z0-9.\-]*|IP[0-6][0-9K]?|M\d+x\d+(?:\.\d+)?|0x[0-9A-Fa-f]+|--[a-zA-Z0-9\-]+|\d+(?:\.\d+)?\s*(?:MPa|kPa|Pa|bar|psi|in|ft|gpm|kV|mV|V|kA|mA|A|MW|kW|W|GHz|MHz|kHz|Hz|kΩ|MΩ|Ω|mm|μm|cm|kg|GB|MB|ms|°C|°F)))"""
    )

    private var seeded = false

    private suspend fun ensureBuiltInSeeded() {
        if (seeded) return
        if (glossaryDao.count() == 0) {
            glossaryDao.upsertAll(builtInTerms())
        }
        seeded = true
    }

    override suspend fun preprocess(
        normalizedSourceText: String,
        sourceLang: String,
        targetLang: String,
        domainId: String
    ): PreprocessedTermPayload {
        ensureBuiltInSeeded()

        var workingText = normalizedSourceText
        val placeholders = linkedMapOf<String, String>()
        val preserved = mutableListOf<String>()
        val applied = mutableListOf<AppliedTermMatch>()
        var tokenIndex = 0

        // Step 1: Shield code identifiers, article numbers, normalized units, and inline SI conversions
        workingText = nonTranslatableTokenRegex.replace(workingText) { matchResult ->
            val token = matchResult.value
            val key = "__NT_${tokenIndex++}__"
            placeholders[key] = token
            preserved.add(token)
            key
        }

        // Step 2: Apply priority-ordered glossary terms (User priority 100 > Domain priority 50 > General priority 10)
        val terms = glossaryDao.getActiveTermsForPairAndDomain(
            sourceLang = sourceLang.lowercase(),
            targetLang = targetLang.lowercase(),
            domainId = domainId
        )

        for (term in terms) {
            val regexOptions = if (term.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = buildMorphAwareTermRegex(term.sourceTerm, sourceLang, regexOptions)
            if (pattern.containsMatchIn(workingText)) {
                val key = "__TERM_${tokenIndex++}__"
                workingText = pattern.replace(workingText, key)
                placeholders[key] = term.targetTerm
                applied.add(
                    AppliedTermMatch(
                        sourceTerm = term.sourceTerm,
                        targetTerm = term.targetTerm,
                        domainId = term.domainId,
                        isUserGlossary = term.isUserDefined
                    )
                )
            }
        }

        return PreprocessedTermPayload(
            preparedText = workingText,
            placeholdersToTarget = placeholders,
            appliedTerms = applied,
            preservedTokens = preserved
        )
    }

    override fun postprocess(
        rawTranslatedText: String,
        payload: PreprocessedTermPayload,
        targetLang: String
    ): String {
        var result = rawTranslatedText
        for ((placeholder, targetValue) in payload.placeholdersToTarget) {
            result = result.replace(placeholder, targetValue, ignoreCase = true)
        }
        return result.replace(Regex("""\s+"""), " ").trim()
    }

    override suspend fun extractAsrHotwords(sourceLang: String, domainId: String): Map<String, Float> {
        ensureBuiltInSeeded()
        val allDomainTerms = glossaryDao.getTermsFiltered(domainId = domainId, userOnly = false)
            .filter { it.sourceLang.equals(sourceLang, ignoreCase = true) }
        val result = linkedMapOf<String, Float>()
        for (entry in allDomainTerms) {
            // User glossary terms receive a higher ASR hotword boost (3.5) than built-in domain terms (2.0)
            val boost = if (entry.isUserDefined) 3.5f else 2.0f
            result[entry.sourceTerm] = boost
        }
        return result
    }

    override suspend fun importCsv(csvContent: String, isUserDefined: Boolean): Int {
        ensureBuiltInSeeded()
        val lines = csvContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return 0

        val entities = mutableListOf<GlossaryTermEntity>()
        for ((index, line) in lines.withIndex()) {
            if (index == 0 && line.lowercase().startsWith("src_lang")) continue
            val parts = line.split(",").map { it.trim().removeSurrounding("\"") }
            if (parts.size >= 5) {
                entities.add(
                    GlossaryTermEntity(
                        sourceLang = parts[0].lowercase(),
                        targetLang = parts[1].lowercase(),
                        domainId = parts[2],
                        sourceTerm = parts[3],
                        targetTerm = parts[4],
                        isUserDefined = isUserDefined,
                        caseSensitive = parts.getOrNull(5)?.toBooleanStrictOrNull() ?: false,
                        priority = parts.getOrNull(6)?.toIntOrNull() ?: if (isUserDefined) 100 else 50
                    )
                )
            }
        }
        if (entities.isNotEmpty()) {
            glossaryDao.upsertAll(entities)
        }
        return entities.size
    }

    override suspend fun exportCsv(domainId: String?, userOnly: Boolean): String {
        ensureBuiltInSeeded()
        val terms = glossaryDao.getTermsFiltered(domainId, userOnly)
        val sb = StringBuilder()
        sb.appendLine("src_lang,tgt_lang,domain,src_term,tgt_term,case_sensitive,priority")
        for (t in terms) {
            sb.appendLine(
                "${t.sourceLang},${t.targetLang},${t.domainId},\"${t.sourceTerm}\",\"${t.targetTerm}\",${t.caseSensitive},${t.priority}"
            )
        }
        return sb.toString()
    }

    override suspend fun importTbx(tbxXmlContent: String, isUserDefined: Boolean): Int {
        ensureBuiltInSeeded()
        val termEntryRegex = Regex("""<termEntry[^>]*>(.*?)</termEntry>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val domainRegex = Regex("""<descrip[^>]*type=["']subjectField["'][^>]*>(.*?)</descrip>""", RegexOption.IGNORE_CASE)
        val langSetRegex = Regex("""<langSet[^>]*xml:lang=["']([a-zA-Z\-]+)["'][^>]*>(.*?)</langSet>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val termRegex = Regex("""<term[^>]*>(.*?)</term>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val entities = mutableListOf<GlossaryTermEntity>()
        for (entryMatch in termEntryRegex.findAll(tbxXmlContent)) {
            val block = entryMatch.groupValues[1]
            val domain = domainRegex.find(block)?.groupValues?.get(1)?.trim() ?: TechnicalDomain.GENERAL_TECH.id
            val langTerms = mutableMapOf<String, String>()
            for (langMatch in langSetRegex.findAll(block)) {
                val langCode = langMatch.groupValues[1].substringBefore("-").lowercase()
                val termVal = termRegex.find(langMatch.groupValues[2])?.groupValues?.get(1)?.trim()
                if (!termVal.isNullOrBlank()) {
                    langTerms[langCode] = termVal
                }
            }
            val langs = langTerms.keys.toList()
            for (i in langs.indices) {
                for (j in langs.indices) {
                    if (i != j) {
                        val src = langs[i]
                        val tgt = langs[j]
                        entities.add(
                            GlossaryTermEntity(
                                sourceLang = src,
                                targetLang = tgt,
                                domainId = domain,
                                sourceTerm = langTerms.getValue(src),
                                targetTerm = langTerms.getValue(tgt),
                                isUserDefined = isUserDefined,
                                priority = if (isUserDefined) 100 else 50
                            )
                        )
                    }
                }
            }
        }
        if (entities.isNotEmpty()) {
            glossaryDao.upsertAll(entities)
        }
        return entities.size
    }

    override suspend fun exportTbx(domainId: String?, userOnly: Boolean): String {
        ensureBuiltInSeeded()
        val terms = glossaryDao.getTermsFiltered(domainId, userOnly)
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<martif type="TBX" xml:lang="en">""")
        sb.appendLine("""  <text><body>""")
        for (t in terms) {
            sb.appendLine("""    <termEntry>""")
            sb.appendLine("""      <descrip type="subjectField">${t.domainId}</descrip>""")
            sb.appendLine("""      <langSet xml:lang="${t.sourceLang}"><tig><term>${t.sourceTerm}</term></tig></langSet>""")
            sb.appendLine("""      <langSet xml:lang="${t.targetLang}"><tig><term>${t.targetTerm}</term></tig></langSet>""")
            sb.appendLine("""    </termEntry>""")
        }
        sb.appendLine("""  </body></text>""")
        sb.appendLine("""</martif>""")
        return sb.toString()
    }

    override suspend fun exportCompactShareCode(domainId: String?, userOnly: Boolean): String {
        val csv = exportCsv(domainId = domainId, userOnly = userOnly)
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { deflater ->
            deflater.write(csv.toByteArray(Charsets.UTF_8))
        }
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
        return "$SHARE_CODE_PREFIX$base64"
    }

    override suspend fun importCompactShareCode(shareCode: String, isUserDefined: Boolean): Int {
        val trimmed = shareCode.trim()
        require(trimmed.startsWith(SHARE_CODE_PREFIX)) { "Invalid share code prefix" }
        val payloadBase64 = trimmed.removePrefix(SHARE_CODE_PREFIX)
        val compressed = Base64.getUrlDecoder().decode(payloadBase64)
        val decompressedCsv = InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
            inflater.readBytes().toString(Charsets.UTF_8)
        }
        return importCsv(decompressedCsv, isUserDefined = isUserDefined)
    }

    private fun buildMorphAwareTermRegex(term: String, lang: String, options: Set<RegexOption>): Regex {
        val escaped = Regex.escape(term.trim())
        return if (lang == "ru" || lang == "tr") {
            val stem = if (term.length >= 5) Regex.escape(term.dropLast(1)) else escaped
            Regex("""\b$stem[а-яА-ЯёЁa-zA-ZğüşıöçĞÜŞİÖÇ]{0,3}\b""", options)
        } else {
            Regex("""\b$escaped(?:s|es)?\b""", options)
        }
    }

    private fun builtInTerms(): List<GlossaryTermEntity> {
        val seed = mutableListOf<GlossaryEntry>()
        fun addTriplet(domain: String, ru: String, en: String, tr: String) {
            seed.add(GlossaryEntry(sourceLang = "ru", targetLang = "en", domainId = domain, sourceTerm = ru, targetTerm = en, isUserDefined = false, priority = 50))
            seed.add(GlossaryEntry(sourceLang = "en", targetLang = "ru", domainId = domain, sourceTerm = en, targetTerm = ru, isUserDefined = false, priority = 50))
            seed.add(GlossaryEntry(sourceLang = "en", targetLang = "tr", domainId = domain, sourceTerm = en, targetTerm = tr, isUserDefined = false, priority = 50))
            seed.add(GlossaryEntry(sourceLang = "tr", targetLang = "en", domainId = domain, sourceTerm = tr, targetTerm = en, isUserDefined = false, priority = 50))
            seed.add(GlossaryEntry(sourceLang = "ru", targetLang = "tr", domainId = domain, sourceTerm = ru, targetTerm = tr, isUserDefined = false, priority = 50))
            seed.add(GlossaryEntry(sourceLang = "tr", targetLang = "ru", domainId = domain, sourceTerm = tr, targetTerm = ru, isUserDefined = false, priority = 50))
        }

        // 1. IT & Programming
        addTriplet("it_programming", "задержка вызова", "call latency", "çağrı gecikmesi")
        addTriplet("it_programming", "микроконтроллер", "microcontroller", "mikrodenetleyici")
        addTriplet("it_programming", "потоковая передача", "streaming", "akış")
        addTriplet("it_programming", "утечка памяти", "memory leak", "bellek sızıntısı")

        // 2. Mechanical Engineering
        addTriplet("mechanical", "рабочее давление", "operating pressure", "çalışma basıncı")
        addTriplet("mechanical", "клапан", "valve", "vana")
        addTriplet("mechanical", "крутящий момент", "torque", "tork")
        addTriplet("mechanical", "подшипник качения", "rolling bearing", "rulman")

        // 3. Electrical & Electronics
        addTriplet("electrical", "короткое замыкание", "short circuit", "kısa devre")
        addTriplet("electrical", "постоянный ток", "direct current", "doğru akım")
        addTriplet("electrical", "автоматический выключатель", "circuit breaker", "devre kesici")
        addTriplet("electrical", "печатная плата", "printed circuit board", "baskı devre kartı")

        // 4. Construction
        addTriplet("construction", "железобетон", "reinforced concrete", "betonarme")
        addTriplet("construction", "несущая способность", "bearing capacity", "taşıma kapasitesi")
        addTriplet("construction", "опалубка", "formwork", "kalıp")

        // 5. Oil, Gas & Energy
        addTriplet("oil_gas_energy", "запорная арматура", "shut-off valves", "kapama vanaları")
        addTriplet("oil_gas_energy", "устьевое давление", "wellhead pressure", "kuyu başı basıncı")
        addTriplet("oil_gas_energy", "буровой раствор", "drilling mud", "sondaj çamuru")

        return seed.map {
            GlossaryTermEntity(
                sourceLang = it.sourceLang,
                targetLang = it.targetLang,
                domainId = it.domainId,
                sourceTerm = it.sourceTerm,
                targetTerm = it.targetTerm,
                isUserDefined = it.isUserDefined,
                caseSensitive = it.caseSensitive,
                priority = it.priority
            )
        }
    }

    companion object {
        const val SHARE_CODE_PREFIX = "TPKGLOSS1:"
    }
}

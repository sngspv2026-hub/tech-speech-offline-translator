package com.offline.translate.core.model

import kotlinx.serialization.Serializable

/**
 * Performance and accuracy profile adapted for mid-range ARM devices (Xiaomi Redmi Note Pro 5G).
 */
@Serializable
enum class QualityProfile(
    val targetEndToEndLatencyMs: Long,
    val partialUpdateIntervalMs: Long,
    val beamSize: Int,
    val defaultCpuThreads: Int,
    val allowSmallWhisper: Boolean
) {
    FAST(
        targetEndToEndLatencyMs = 500L,
        partialUpdateIntervalMs = 250L,
        beamSize = 1,
        defaultCpuThreads = 4,
        allowSmallWhisper = false
    ),
    BALANCED(
        targetEndToEndLatencyMs = 800L,
        partialUpdateIntervalMs = 300L,
        beamSize = 2,
        defaultCpuThreads = 4,
        allowSmallWhisper = false
    ),
    ACCURATE(
        targetEndToEndLatencyMs = 1200L,
        partialUpdateIntervalMs = 400L,
        beamSize = 4,
        defaultCpuThreads = 4,
        allowSmallWhisper = true
    );

    fun downgradeForThermal(): QualityProfile = when (this) {
        ACCURATE -> BALANCED
        BALANCED -> FAST
        FAST -> FAST
    }
}

/**
 * Extensible technical domain descriptor. New domains can be registered by language packs or user glossaries.
 */
@Serializable
data class TechnicalDomain(
    val id: String,
    val names: Map<String, String>,
    val isBuiltIn: Boolean = true
) {
    fun localizedName(locale: String): String =
        names[locale] ?: names["en"] ?: id

    companion object {
        val GENERAL_TECH = TechnicalDomain(
            id = "general",
            names = mapOf(
                "ru" to "Общетехнический",
                "en" to "General Technical",
                "tr" to "Genel Teknik"
            )
        )
        val IT_PROGRAMMING = TechnicalDomain(
            id = "it_programming",
            names = mapOf(
                "ru" to "IT и программирование",
                "en" to "IT & Programming",
                "tr" to "BT ve Programlama"
            )
        )
        val MECHANICAL = TechnicalDomain(
            id = "mechanical",
            names = mapOf(
                "ru" to "Машиностроение",
                "en" to "Mechanical Engineering",
                "tr" to "Makine Mühendisliği"
            )
        )
        val ELECTRICAL = TechnicalDomain(
            id = "electrical",
            names = mapOf(
                "ru" to "Электротехника и электроника",
                "en" to "Electrical & Electronics",
                "tr" to "Elektrik ve Elektronik"
            )
        )
        val CONSTRUCTION = TechnicalDomain(
            id = "construction",
            names = mapOf(
                "ru" to "Строительство",
                "en" to "Construction & Civil Eng.",
                "tr" to "İnşaat Mühendisliği"
            )
        )
        val OIL_GAS_ENERGY = TechnicalDomain(
            id = "oil_gas_energy",
            names = mapOf(
                "ru" to "Нефтегаз и энергетика",
                "en" to "Oil, Gas & Energy",
                "tr" to "Petrol, Gaz ve Enerji"
            )
        )

        val BUILT_IN_DOMAINS = listOf(
            GENERAL_TECH,
            IT_PROGRAMMING,
            MECHANICAL,
            ELECTRICAL,
            CONSTRUCTION,
            OIL_GAS_ENERGY
        )

        fun byId(id: String): TechnicalDomain =
            BUILT_IN_DOMAINS.find { it.id == id }
                ?: TechnicalDomain(id = id, names = mapOf("en" to id), isBuiltIn = false)
    }
}

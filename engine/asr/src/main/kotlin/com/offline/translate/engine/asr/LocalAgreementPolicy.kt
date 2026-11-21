package com.offline.translate.engine.asr

/**
 * Implements the LocalAgreement-2 streaming stabilization algorithm for ASR hypotheses.
 * Given consecutive partial transcripts at ~300 ms ticks, extracts the Longest Common Token Prefix (LCTP)
 * as the confirmed stable text, leaving new unconfirmed words in the unstable tail.
 */
class LocalAgreementPolicy {

    private var previousTokens: List<String> = emptyList()
    private var committedPrefixTokens: List<String> = emptyList()

    data class SplitHypothesis(
        val confirmedText: String,
        val unstableTailText: String
    )

    fun update(currentTranscript: String, isFinal: Boolean): SplitHypothesis {
        val currentTokens = tokenize(currentTranscript)
        if (isFinal) {
            reset()
            return SplitHypothesis(
                confirmedText = currentTokens.joinToString(" "),
                unstableTailText = ""
            )
        }

        val commonLen = longestCommonPrefixLength(previousTokens, currentTokens)
        if (commonLen > committedPrefixTokens.size) {
            committedPrefixTokens = currentTokens.subList(0, commonLen)
        }

        previousTokens = currentTokens

        val confirmedCount = committedPrefixTokens.size.coerceAtMost(currentTokens.size)
        val confirmed = currentTokens.subList(0, confirmedCount).joinToString(" ")
        val tail = if (confirmedCount < currentTokens.size) {
            currentTokens.subList(confirmedCount, currentTokens.size).joinToString(" ")
        } else {
            ""
        }

        return SplitHypothesis(confirmedText = confirmed, unstableTailText = tail)
    }

    fun reset() {
        previousTokens = emptyList()
        committedPrefixTokens = emptyList()
    }

    private fun tokenize(text: String): List<String> =
        text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    private fun longestCommonPrefixLength(a: List<String>, b: List<String>): Int {
        val minSize = minOf(a.size, b.size)
        var i = 0
        while (i < minSize && a[i].equals(b[i], ignoreCase = true)) {
            i++
        }
        return i
    }
}

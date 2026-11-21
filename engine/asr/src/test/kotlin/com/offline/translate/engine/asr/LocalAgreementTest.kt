package com.offline.translate.engine.asr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgreementTest {

    @Test
    fun `LocalAgreement-2 commits common prefix across consecutive partial hypotheses`() {
        val policy = LocalAgreementPolicy()

        // First partial hypothesis: nothing confirmed yet, all in unstable tail
        val step1 = policy.update("рабочее давление на", isFinal = false)
        assertEquals("", step1.confirmedText)
        assertEquals("рабочее давление на", step1.unstableTailText)

        // Second partial hypothesis shares "рабочее давление на"
        val step2 = policy.update("рабочее давление на клапане DN-50", isFinal = false)
        assertEquals("рабочее давление на", step2.confirmedText)
        assertEquals("клапане DN-50", step2.unstableTailText)

        // Final hypothesis commits everything and clears tail
        val step3 = policy.update("рабочее давление на клапане DN-50 составляет 5 MPa", isFinal = true)
        assertEquals("рабочее давление на клапане DN-50 составляет 5 MPa", step3.confirmedText)
        assertEquals("", step3.unstableTailText)
    }

    @Test
    fun `MockAsrEngine emits partial events followed by final event`() = runTest {
        val engine = MockAsrEngine()
        val finalEvent = engine.finishStream()
        assertTrue(finalEvent != null && finalEvent.isFinal)
        assertTrue(finalEvent!!.confirmedText.isNotBlank())
    }
}

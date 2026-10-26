package com.anonh4x.anonping.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ImpairmentMathTest {
    @Test fun zeroSettingsProduceNoDelay() {
        assertEquals(0, ImpairmentMath.effectiveDelayMs(0, 0, 0))
    }

    @Test fun jitterAddsAValueWithinConfiguredRange() {
        assertEquals(120, ImpairmentMath.effectiveDelayMs(100, 20, 20))
        assertEquals(100, ImpairmentMath.effectiveDelayMs(100, 20, 0))
    }

    @Test fun randomOffsetIsClampedToConfiguredJitter() {
        assertEquals(115, ImpairmentMath.effectiveDelayMs(100, 15, 500))
        assertEquals(100, ImpairmentMath.effectiveDelayMs(100, 0, 500))
    }

    @Test fun settingsAreClampedToSupportedBounds() {
        assertEquals(3000, ImpairmentMath.effectiveDelayMs(9000, 9000, 9000))
        assertEquals(0, ImpairmentMath.effectiveDelayMs(-10, -10, -10))
    }
}

package com.anonh4x.anonping.vpn

/**
 * Pure delay math so network impairment bounds can be tested without Android runtime.
 * Jitter is an additional random delay in [0, jitterMs].
 */
internal object ImpairmentMath {
    fun effectiveDelayMs(baseMs: Int, jitterMs: Int, randomOffsetMs: Int): Int {
        val base = baseMs.coerceIn(0, 2000)
        val jitter = jitterMs.coerceIn(0, 1000)
        val offset = randomOffsetMs.coerceIn(0, jitter)
        return (base + offset).coerceAtMost(3000)
    }
}

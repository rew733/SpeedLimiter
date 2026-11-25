package com.example.speedlimiter

class RateLimiter(val bytesPerSecond: Long) {
    private var availableTokens: Double = bytesPerSecond.toDouble()
    private var lastRefillTimestamp: Long = System.nanoTime()
    @Volatile private var boostMultiplier: Double = 1.0

    val isBoostActive: Boolean
        get() = boostMultiplier > 1.0

    fun setBoost(active: Boolean) {
        boostMultiplier = if (active) 4.0 else 1.0
    }

    @Synchronized
    fun acquire(bytes: Int) {
        if (bytes <= 0) return
        val effectiveRate = (bytesPerSecond * boostMultiplier).toLong().coerceAtLeast(1024L)
        refill(effectiveRate)
        while (availableTokens < bytes) {
            val deficit = bytes - availableTokens
            val waitMillis = ((deficit / effectiveRate) * 1000.0).toLong().coerceAtLeast(1L)
            try {
                Thread.sleep(waitMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            refill(effectiveRate)
        }
        availableTokens -= bytes
    }

    private fun refill(effectiveRate: Long) {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            // سعة الدلو لا تقل عن MIN_BUCKET حتى لا تعلق acquire إذا كانت الحزمة أكبر من سرعة الحد
            availableTokens = (availableTokens + elapsedSeconds * effectiveRate)
                .coerceAtMost(maxOf(effectiveRate.toDouble(), MIN_BUCKET))
            lastRefillTimestamp = now
        }
    }

    private companion object {
        const val MIN_BUCKET = 4096.0
    }
}

package com.example.speedlimiter

class RateLimiter(private val bytesPerSecond: Long) {
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
            availableTokens = (availableTokens + elapsedSeconds * effectiveRate)
                .coerceAtMost(effectiveRate.toDouble())
            lastRefillTimestamp = now
        }
    }
}

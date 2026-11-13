package com.example.speedlimiter

class RateLimiter(private val bytesPerSecond: Long) {
    private var availableTokens: Double = bytesPerSecond.toDouble()
    private var lastRefillTimestamp: Long = System.nanoTime()

    @Synchronized
    fun acquire(bytes: Int) {
        refill()
        while (availableTokens < bytes) {
            val deficit = bytes - availableTokens
            val waitMillis = ((deficit / bytesPerSecond) * 1000.0).toLong().coerceAtLeast(1L)
            try {
                Thread.sleep(waitMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            refill()
        }
        availableTokens -= bytes
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            availableTokens = (availableTokens + elapsedSeconds * bytesPerSecond)
                .coerceAtMost(bytesPerSecond.toDouble())
            lastRefillTimestamp = now
        }
    }
}

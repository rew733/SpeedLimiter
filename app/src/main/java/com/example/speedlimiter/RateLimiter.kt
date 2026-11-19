package com.example.speedlimiter

class RateLimiter(initialBytesPerSecond: Long) {
    @Volatile
    var bytesPerSecond: Long = initialBytesPerSecond
        private set

    @Volatile
    var isBoostActive: Boolean = false
        private set

    private var availableTokens: Double = initialBytesPerSecond.toDouble()
    private var lastRefillTime: Long = System.nanoTime()

    @Synchronized
    fun updateSpeed(newBytesPerSecond: Long) {
        bytesPerSecond = newBytesPerSecond
        if (availableTokens > newBytesPerSecond) {
            availableTokens = newBytesPerSecond.toDouble()
        }
    }

    @Synchronized
    fun setBoost(active: Boolean) {
        isBoostActive = active
    }

    fun acquire(bytes: Int) {
        if (isBoostActive || bytesPerSecond <= 0L) return

        var waitMillis = 0L
        synchronized(this) {
            val now = System.nanoTime()
            val elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0
            if (elapsedSeconds > 0) {
                availableTokens = (availableTokens + elapsedSeconds * bytesPerSecond)
                    .coerceAtMost(bytesPerSecond.toDouble())
                lastRefillTime = now
            }

            if (availableTokens >= bytes) {
                availableTokens -= bytes
            } else {
                val deficit = bytes - availableTokens
                waitMillis = ((deficit / bytesPerSecond) * 1000.0).toLong()
                availableTokens = 0.0
                lastRefillTime = now + (waitMillis * 1_000_000L)
            }
        }

        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

package com.example.speedlimiter

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RateLimiter(initialBytesPerSecond: Long) {
    private val lock = ReentrantLock()
    @Volatile private var baseBytesPerSecond: Long = initialBytesPerSecond.coerceAtLeast(1024L)
    @Volatile var isBoostActive: Boolean = false
        private set

    private var availableTokens: Double = baseBytesPerSecond.toDouble()
    private var lastRefillTimeNs: Long = System.nanoTime()

    fun setLimitBytesPerSecond(bytesPerSecond: Long) {
        lock.withLock {
            refillTokensLocked()
            baseBytesPerSecond = bytesPerSecond.coerceAtLeast(1024L)
            val maxBurst = effectiveBytesPerSecond() * 1.5
            if (availableTokens > maxBurst) availableTokens = maxBurst
        }
    }

    fun setBoost(boost: Boolean) {
        lock.withLock {
            refillTokensLocked()
            isBoostActive = boost
            val maxBurst = effectiveBytesPerSecond() * 1.5
            if (availableTokens > maxBurst) availableTokens = maxBurst
        }
    }

    private fun effectiveBytesPerSecond(): Long {
        return if (isBoostActive) baseBytesPerSecond * 4L else baseBytesPerSecond
    }

    private fun refillTokensLocked() {
        val now = System.nanoTime()
        val elapsedSec = ((now - lastRefillTimeNs).coerceAtLeast(0L)) / 1_000_000_000.0
        lastRefillTimeNs = now
        if (elapsedSec > 0.0) {
            val rate = effectiveBytesPerSecond().toDouble()
            val maxBurst = rate * 1.5
            availableTokens = (availableTokens + elapsedSec * rate).coerceAtMost(maxBurst)
        }
    }

    fun acquire(bytes: Int) {
        if (bytes <= 0) return
        var remaining = bytes.toDouble()

        while (remaining > 0.0 && !Thread.currentThread().isInterrupted) {
            var sleepMs = 0L
            lock.withLock {
                refillTokensLocked()
                if (availableTokens >= remaining) {
                    availableTokens -= remaining
                    remaining = 0.0
                } else if (availableTokens > 0.0) {
                    remaining -= availableTokens
                    availableTokens = 0.0
                } else {
                    val rate = effectiveBytesPerSecond().toDouble().coerceAtLeast(1024.0)
                    val neededSec = (remaining / rate).coerceIn(0.005, 0.20)
                    sleepMs = (neededSec * 1000.0).toLong().coerceAtLeast(5L)
                }
            }
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }
}

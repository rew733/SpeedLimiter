package com.example.speedlimiter

import android.os.SystemClock

class RateLimiter(private val maxBytesPerSecond: Long) {
    private var availableTokens: Double = maxBytesPerSecond.toDouble()
    private var lastRefillTime: Long = SystemClock.elapsedRealtime()

    @Synchronized
    fun throttle(bytesNum: Int) {
        refill()
        while (availableTokens < bytesNum) {
            val deficit = bytesNum - availableTokens
            val millisToWait = (deficit * 1000 / maxBytesPerSecond).toLong()
            if (millisToWait > 0) {
                try {
                    Thread.sleep(millisToWait.coerceAtMost(50))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            refill()
        }
        availableTokens -= bytesNum.toDouble()
    }

    private fun refill() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastRefillTime
        if (elapsed > 0) {
            val tokensToAdd = (elapsed * maxBytesPerSecond) / 1000.0
            availableTokens = (availableTokens + tokensToAdd).coerceAtMost(maxBytesPerSecond.toDouble())
            lastRefillTime = now
        }
    }
}

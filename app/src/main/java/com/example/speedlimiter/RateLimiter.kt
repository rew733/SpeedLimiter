package com.example.speedlimiter

import android.os.SystemClock

class RateLimiter(initialBytesPerSecond: Long) {
    @Volatile
    private var maxBytesPerSecond: Long = initialBytesPerSecond.coerceAtLeast(1024L)
    private var availableTokens: Double = maxBytesPerSecond.toDouble()
    private var lastRefillTime: Long = SystemClock.elapsedRealtime()

    @Synchronized
    fun setSpeedLimitKbps(kbps: Long) {
        maxBytesPerSecond = (kbps * 1024L).coerceAtLeast(1024L)
        availableTokens = availableTokens.coerceAtMost(maxBytesPerSecond.toDouble())
        lastRefillTime = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun getSpeedLimitKbps(): Long {
        return maxBytesPerSecond / 1024L
    }

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

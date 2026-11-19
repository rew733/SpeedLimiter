package com.example.speedlimiter

class RateLimiter(private val bytesPerSecond: Long) {

    @Volatile
    var isBoostActive: Boolean = false
        private set

    private var nextAllowedTimeNanos: Long = System.nanoTime()

    fun setBoost(active: Boolean) {
        isBoostActive = active
    }

    fun acquire(bytes: Int) {
        if (isBoostActive || bytesPerSecond <= 0L || bytes <= 0) return

        val waitNanos: Long
        synchronized(this) {
            val now = System.nanoTime()
            // منع تراكم رصيد قديم (Burst) لأكثر من 40 مللي ثانية
            val minAllowed = now - 40_000_000L
            if (nextAllowedTimeNanos < minAllowed) {
                nextAllowedTimeNanos = minAllowed
            }

            val durationNanos = (bytes.toLong() * 1_000_000_000L) / bytesPerSecond
            val scheduledTime = nextAllowedTimeNanos
            nextAllowedTimeNanos += durationNanos
            waitNanos = scheduledTime - now
        }

        if (waitNanos > 0L) {
            try {
                val millis = waitNanos / 1_000_000L
                val nanos = (waitNanos % 1_000_000L).toInt()
                Thread.sleep(millis, nanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}

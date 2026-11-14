package com.example.speedlimiter

class RateLimiter(private val bytesPerSecond: Long) {
    private var nextAllowedTimeNs: Long = System.nanoTime()

    @Synchronized
    fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0 || bytes <= 0) return

        val now = System.nanoTime()
        // منع تراكم الرصيد القديم (Zero Burst): إذا مر وقت سكون، ابدأ الحساب من اللحظة الحالية فقط
        if (now > nextAllowedTimeNs) {
            nextAllowedTimeNs = now
        }

        val waitTimeNs = nextAllowedTimeNs - now
        if (waitTimeNs > 0) {
            val millis = waitTimeNs / 1_000_000L
            val nanos = (waitTimeNs % 1_000_000L).toInt()
            try {
                Thread.sleep(millis, nanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        // حساب الزمن المستحق لهذه الحزمة وإضافته للمستقبل
        val durationNs = (bytes.toLong() * 1_000_000_000L) / bytesPerSecond
        nextAllowedTimeNs += durationNs
    }
}

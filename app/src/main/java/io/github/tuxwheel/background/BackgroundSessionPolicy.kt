package io.github.tuxwheel.background

/** Monotonic times let the same policy run with the screen off and across wall-clock changes. */
internal class BackgroundSessionPolicy {
    var address: String? = null
        private set
    private var disconnectedAt: Long? = null
    private var choiceStartedAt: Long? = null
    private var lastAttemptAt: Long? = null

    fun choose(
        wheels: List<String>,
        present: Set<String>,
        blocked: Set<String>,
        now: Long,
        priorityWaitMillis: Long,
    ): String? {
        val eligible = wheels.filter { it in present && it !in blocked }
        if (eligible.isEmpty()) {
            choiceStartedAt = null
            return null
        }
        val started = choiceStartedAt ?: now.also { choiceStartedAt = it }
        val best = eligible.first()
        if (best != wheels.firstOrNull() && now - started < priorityWaitMillis) return null
        choiceStartedAt = null
        return best
    }

    fun begin(address: String, now: Long) {
        this.address = address
        disconnectedAt = now
        lastAttemptAt = now
        choiceStartedAt = null
    }

    fun updateConnection(connected: Boolean, now: Long) {
        if (connected) disconnectedAt = null
        else if (address != null && disconnectedAt == null) disconnectedAt = now
    }

    fun expired(now: Long, timeoutMillis: Long): Boolean =
        disconnectedAt?.let { now - it >= timeoutMillis } == true

    fun canRetry(now: Long): Boolean = lastAttemptAt?.let { now - it >= 30_000 } != false

    fun attempting(now: Long) {
        lastAttemptAt = now
    }

    fun end() {
        address = null
        disconnectedAt = null
        lastAttemptAt = null
        choiceStartedAt = null
    }
}

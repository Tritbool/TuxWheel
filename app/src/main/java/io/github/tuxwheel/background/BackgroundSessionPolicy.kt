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
    ): String? = pick(wheels.filter { it in present && it !in blocked }, wheels, now, priorityWaitMillis)

    /**
     * Re-evaluates the connection target across every known wheel while a session is running, so a
     * failing or vanished wheel no longer stays pinned. The current wheel is demoted to the end of
     * the candidate list so present alternatives get their turn, without resetting the session
     * grace window.
     */
    fun rechoose(
        wheels: List<String>,
        present: Set<String>,
        blocked: Set<String>,
        now: Long,
        priorityWaitMillis: Long,
    ): String? {
        val current = address ?: return choose(wheels, present, blocked, now, priorityWaitMillis)
        val eligible = wheels.filter { it in present && it !in blocked }
        val demoted = eligible.filter { it != current } + listOfNotNull(current.takeIf { it in eligible })
        return pick(demoted, wheels, now, priorityWaitMillis)
    }

    private fun pick(
        candidates: List<String>,
        wheels: List<String>,
        now: Long,
        priorityWaitMillis: Long,
    ): String? {
        if (candidates.isEmpty()) {
            choiceStartedAt = null
            return null
        }
        val started = choiceStartedAt ?: now.also { choiceStartedAt = it }
        val best = candidates.first()
        if (best != wheels.firstOrNull() && now - started < priorityWaitMillis) return null
        choiceStartedAt = null
        return best
    }

    /** Switches the session to another wheel without resetting the disconnection grace window. */
    fun retarget(address: String, now: Long) {
        this.address = address
        lastAttemptAt = now
        choiceStartedAt = null
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

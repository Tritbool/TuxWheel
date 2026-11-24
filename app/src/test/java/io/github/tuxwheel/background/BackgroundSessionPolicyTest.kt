package io.github.tuxwheel.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSessionPolicyTest {
    private val favorite = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"
    private val wheels = listOf(favorite, other)

    @Test fun `priority window reuses configured wait and favorite connects immediately`() {
        val policy = BackgroundSessionPolicy()
        assertNull(policy.choose(wheels, setOf(other), emptySet(), 0, 2_000))
        assertNull(policy.choose(wheels, setOf(other), emptySet(), 1_999, 2_000))
        assertEquals(other, policy.choose(wheels, setOf(other), emptySet(), 2_000, 2_000))
        assertEquals(favorite, policy.choose(wheels, setOf(favorite, other), emptySet(), 3_000, 2_000))
    }

    @Test fun `blocked wheels never reconnect and zero wait chooses available wheel`() {
        val policy = BackgroundSessionPolicy()
        assertNull(policy.choose(wheels, setOf(favorite), setOf(favorite), 0, 0))
        assertEquals(other, policy.choose(wheels, setOf(favorite, other), setOf(favorite), 0, 0))
    }

    @Test fun `absence cancels priority window instead of carrying an old deadline`() {
        val policy = BackgroundSessionPolicy()
        policy.choose(wheels, setOf(other), emptySet(), 0, 1_000)
        policy.choose(wheels, emptySet(), emptySet(), 900, 1_000)
        assertNull(policy.choose(wheels, setOf(other), emptySet(), 1_100, 1_000))
        assertEquals(other, policy.choose(wheels, setOf(other), emptySet(), 2_100, 1_000))
    }

    @Test fun `a zero-minute timeout gives the wheel up as soon as it disconnects`() {
        val policy = BackgroundSessionPolicy()
        policy.begin(favorite, 1_000)
        policy.updateConnection(true, 2_000)
        policy.updateConnection(false, 3_000)
        assertFalse(policy.expired(1_000, 0))
        assertTrue(policy.expired(3_000, 0))
    }

    @Test fun `fifteen minute grace starts on link loss and resets after reconnection`() {
        val policy = BackgroundSessionPolicy()
        policy.begin(favorite, 0)
        policy.updateConnection(true, 10)
        policy.updateConnection(false, 100)
        assertFalse(policy.expired(900_099, 900_000))
        assertTrue(policy.expired(900_100, 900_000))
        policy.updateConnection(true, 900_101)
        assertFalse(policy.expired(2_000_000, 900_000))
        policy.updateConnection(false, 2_000_000)
        assertTrue(policy.expired(2_060_000, 60_000))
    }

    @Test fun `retries do not reset grace and ending allows another wheel`() {
        val policy = BackgroundSessionPolicy()
        policy.begin(favorite, 0)
        assertFalse(policy.canRetry(29_999))
        assertTrue(policy.canRetry(30_000))
        policy.attempting(30_000)
        policy.updateConnection(false, 30_000)
        assertTrue(policy.expired(60_000, 60_000))
        policy.end()
        assertNull(policy.address)
        assertFalse(policy.expired(100_000, 60_000))
        assertEquals(other, policy.choose(wheels, setOf(other), emptySet(), 100_000, 0))
    }

    @Test fun `rechoose surveys every known wheel instead of pinning the last connected one`() {
        val policy = BackgroundSessionPolicy()
        policy.begin(other, 0)
        policy.updateConnection(true, 10)
        policy.updateConnection(false, 10)
        // The pinned wheel stays demoted while it is the only candidate.
        assertEquals(other, policy.rechoose(wheels, setOf(other), emptySet(), 30_000, 0))
        // A preferred wheel appearing later wins over the demoted failure.
        assertEquals(favorite, policy.rechoose(wheels, setOf(favorite, other), emptySet(), 30_100, 0))
        policy.retarget(favorite, 30_100)
        assertEquals(favorite, policy.address)
        // The grace window keeps counting from the original link loss, not from the switch.
        assertFalse(policy.expired(900_009, 900_000))
        assertTrue(policy.expired(900_010, 900_000))
    }

    @Test fun `rechoose falls back to another saved wheel once the pinned one vanishes`() {
        val policy = BackgroundSessionPolicy()
        policy.begin(favorite, 0)
        policy.updateConnection(false, 10)
        assertEquals(other, policy.rechoose(wheels, setOf(other), emptySet(), 30_000, 0))
        assertNull(policy.rechoose(wheels, emptySet(), emptySet(), 40_000, 0))
    }
}

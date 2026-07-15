package com.talkiewalkie.channel

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

class HalfDuplexLockTest {

    private lateinit var lock: HalfDuplexLock

    @Before fun setUp() {
        lock = HalfDuplexLock()
    }

    @Test fun initiallyFree() {
        assertNull(lock.current)
        assertFalse(lock.isHeld)
    }

    @Test fun firstAcquireSucceeds() {
        assertTrue(lock.acquire("Alice"))
        assertEquals("Alice", lock.current)
        assertTrue(lock.isHeld)
    }

    @Test fun secondAcquireWhileHeldFails() {
        lock.acquire("Alice")
        assertFalse(lock.acquire("Bob"))
        assertEquals("Alice", lock.current)   // original holder unchanged
    }

    @Test fun sameHolderAcquireAgainFails() {
        // Re-entrant acquire is not supported — caller must release first.
        lock.acquire("Alice")
        assertFalse(lock.acquire("Alice"))
    }

    @Test fun nonHolderCannotRelease() {
        lock.acquire("Alice")
        assertFalse(lock.release("Bob"))
        assertTrue(lock.isHeld)
    }

    @Test fun holderCanRelease() {
        lock.acquire("Alice")
        assertTrue(lock.release("Alice"))
        assertNull(lock.current)
        assertFalse(lock.isHeld)
    }

    @Test fun releaseOnFreeLockFails() {
        assertFalse(lock.release("Alice"))
    }

    @Test fun acquireAfterRelease() {
        lock.acquire("Alice")
        lock.release("Alice")
        assertTrue(lock.acquire("Bob"))
        assertEquals("Bob", lock.current)
    }

    @Test fun releaseByWrongHolderDoesNotFreeForOthers() {
        lock.acquire("Alice")
        lock.release("Bob")      // no-op
        assertFalse(lock.acquire("Carol"))  // still held by Alice
    }

    @Test fun cycleMultipleTimes() {
        repeat(100) { i ->
            val name = "User$i"
            assertTrue("iteration $i acquire", lock.acquire(name))
            assertEquals(name, lock.current)
            assertTrue("iteration $i release", lock.release(name))
            assertFalse(lock.isHeld)
        }
    }

    @Test fun exactlyOneThreadAcquiresUnderContention() {
        val results   = ConcurrentLinkedQueue<Boolean>()
        val readyLatch = CountDownLatch(10)
        val startLatch = CountDownLatch(1)

        val threads = (1..10).map { i ->
            Thread {
                readyLatch.countDown()
                startLatch.await()          // all threads start simultaneously
                results.add(lock.acquire("User$i"))
            }
        }
        threads.forEach { it.start() }
        readyLatch.await()
        startLatch.countDown()
        threads.forEach { it.join(2_000) }

        assertEquals("exactly one thread should win", 1, results.count { it })
        assertNotNull("lock should be held", lock.current)
    }
}

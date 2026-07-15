package com.talkiewalkie.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the transmission-timeout countdown logic.
 *
 * The production code (WalkieTalkieService) embeds the countdown inline inside
 * a launched coroutine. These tests reproduce that same structure in isolation
 * — same delays, same loop — and advance virtual time with TestCoroutineScheduler
 * so the full 30-second timeout runs in milliseconds.
 *
 * Constant mirrors match the service's private constants:
 *   TX_TIMEOUT_SECS = 30, TX_WARNING_SECS = 5
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TxTimeoutTest {

    private val timeoutMs  = 30_000L
    private val warningMs  =  5_000L
    private val waitMs     = timeoutMs - warningMs   // 25 000

    // Launches a countdown coroutine that mirrors the service's txTimeoutJob.
    // Returns (warnings emitted, didStop). The caller controls virtual time.
    private fun TestScope.launchCountdown(): Pair<MutableList<Int>, MutableList<Boolean>> {
        val warnings = mutableListOf<Int>()
        val stopped  = mutableListOf<Boolean>()
        launch {
            delay(waitMs)
            var remaining = (warningMs / 1_000L).toInt()
            while (remaining > 0) {
                warnings.add(remaining)
                delay(1_000L)
                remaining--
            }
            stopped.add(true)
        }
        return warnings to stopped
    }

    // ── pre-warning silence ───────────────────────────────────────────────────

    @Test fun noWarningFiresBeforeWarningWindow() = runTest {
        val (warnings, _) = launchCountdown()
        testScheduler.advanceTimeBy(waitMs - 1)
        assertTrue("no warning before $waitMs ms", warnings.isEmpty())
    }

    @Test fun firstWarningFiresExactlyAtWaitMark() = runTest {
        val (warnings, _) = launchCountdown()
        testScheduler.advanceTimeBy(waitMs)
        runCurrent()
        assertEquals(listOf(5), warnings)
    }

    // ── full countdown ────────────────────────────────────────────────────────

    @Test fun allFiveWarningsFire() = runTest {
        val (warnings, _) = launchCountdown()
        testScheduler.advanceTimeBy(timeoutMs)
        runCurrent()
        assertEquals(listOf(5, 4, 3, 2, 1), warnings)
    }

    @Test fun countdownCompletesAfterFullTimeout() = runTest {
        val (_, stopped) = launchCountdown()
        testScheduler.advanceTimeBy(timeoutMs)
        runCurrent()
        assertEquals(listOf(true), stopped)
    }

    @Test fun stopsDoNotFireBeforeTimeoutCompletes() = runTest {
        val (_, stopped) = launchCountdown()
        testScheduler.advanceTimeBy(timeoutMs - 1)
        assertTrue(stopped.isEmpty())
    }

    // ── per-second spacing ────────────────────────────────────────────────────

    @Test fun eachWarningIsOneSecondApart() = runTest {
        val (warnings, _) = launchCountdown()
        for (i in 5 downTo 1) {
            val elapsed = waitMs + (5 - i) * 1_000L
            testScheduler.advanceTimeBy(if (i == 5) waitMs else 1_000L)
            runCurrent()
            assertEquals("after ${elapsed}ms, expected warnings ${ (5 downTo i).toList() }",
                (5 downTo i).toList(), warnings)
        }
    }

    // ── cancellation ──────────────────────────────────────────────────────────

    @Test fun cancelBeforeWarningWindowFiresNoWarnings() = runTest {
        val (warnings, stopped) = launchCountdown()
        val job = coroutineContext[Job]!!.children.first()
        testScheduler.advanceTimeBy(waitMs - 1)
        job.cancel()
        testScheduler.advanceTimeBy(timeoutMs)   // would have fired everything
        assertTrue(warnings.isEmpty())
        assertTrue(stopped.isEmpty())
    }

    @Test fun cancelAfterFirstWarningStopsRemainingWarnings() = runTest {
        val (warnings, stopped) = launchCountdown()
        val job = coroutineContext[Job]!!.children.first()
        testScheduler.advanceTimeBy(waitMs)
        runCurrent()
        assertEquals(listOf(5), warnings)

        job.cancel()
        testScheduler.advanceTimeBy(warningMs)
        assertEquals("cancelled — no more warnings", listOf(5), warnings)
        assertTrue(stopped.isEmpty())
    }

    @Test fun cancelOnLastWarningPreventsStop() = runTest {
        val (warnings, stopped) = launchCountdown()
        val job = coroutineContext[Job]!!.children.first()
        // Advance to after the 4th warning (remaining = 1) but before the delay expires
        testScheduler.advanceTimeBy(waitMs + 4 * 1_000L)
        runCurrent()
        assertEquals(listOf(5, 4, 3, 2), warnings)

        job.cancel()
        testScheduler.advanceTimeBy(2_000L)
        assertEquals(listOf(5, 4, 3, 2), warnings)
        assertTrue(stopped.isEmpty())
    }
}

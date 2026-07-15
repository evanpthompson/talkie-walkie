package com.talkiewalkie.model

import org.junit.Assert.*
import org.junit.Test

class WalkieStateTest {

    // ── ConnectionState.isActive ──────────────────────────────────────────────

    @Test fun disconnectedIsNotActive()  = assertFalse(ConnectionState.Disconnected.isActive)
    @Test fun searchingIsNotActive()     = assertFalse(ConnectionState.Searching.isActive)
    @Test fun reconnectingIsNotActive()  = assertFalse(ConnectionState.Reconnecting(1).isActive)
    @Test fun hostingIsActive()          = assertTrue(ConnectionState.Hosting.isActive)
    @Test fun connectedIsActive()        = assertTrue(ConnectionState.Connected("Hub").isActive)

    // ── ConnectionState.label ────────────────────────────────────────────────

    @Test fun disconnectedLabel()  = assertEquals("Not connected",      ConnectionState.Disconnected.label)
    @Test fun hostingLabel()       = assertEquals("Hosting channel…",   ConnectionState.Hosting.label)
    @Test fun searchingLabel()     = assertEquals("Searching for hub…", ConnectionState.Searching.label)

    @Test fun connectedLabelContainsDeviceName() {
        val label = ConnectionState.Connected("Alice's Phone").label
        assertTrue(label.contains("Alice's Phone"))
    }

    @Test fun reconnectingLabelContainsAttemptNumber() {
        val label = ConnectionState.Reconnecting(3).label
        assertTrue(label.contains("3"))
    }

    // ── WalkieState defaults ─────────────────────────────────────────────────

    @Test fun defaultStateIsClean() {
        val s = WalkieState()
        assertNull(s.channelName)
        assertEquals(Role.NONE, s.role)
        assertEquals(ConnectionState.Disconnected, s.connection)
        assertTrue(s.members.isEmpty())
        assertFalse(s.isTransmitting)
        assertFalse(s.isReceiving)
        assertNull(s.currentTransmitter)
        assertFalse(s.isBlocked)
        assertFalse(s.ridingMode)
        assertFalse(s.speakerOn)
        assertFalse(s.listeningForCommand)
        assertNull(s.lastCommandText)
    }

    // ── copy preserves unrelated fields ──────────────────────────────────────

    @Test fun copyPreservesChannelAndRole() {
        val initial = WalkieState(channelName = "riders", role = Role.HUB)
        val updated = initial.copy(isTransmitting = true)
        assertEquals("riders", updated.channelName)
        assertEquals(Role.HUB, updated.role)
        assertTrue(updated.isTransmitting)
    }

    @Test fun copyingBlockedClearsOtherTransmitFields() {
        val s = WalkieState(isTransmitting = true, isBlocked = false)
            .copy(isTransmitting = false, isBlocked = true)
        assertFalse(s.isTransmitting)
        assertTrue(s.isBlocked)
    }

    // ── Role enum ────────────────────────────────────────────────────────────

    @Test fun roleEnumValues() {
        val roles = Role.entries.toList()
        assertTrue(roles.contains(Role.NONE))
        assertTrue(roles.contains(Role.HUB))
        assertTrue(roles.contains(Role.CLIENT))
    }

    // ── members list ─────────────────────────────────────────────────────────

    @Test fun membersDefaultsEmpty() = assertTrue(WalkieState().members.isEmpty())

    @Test fun membersPreservedOnCopy() {
        val names = listOf("Alice", "Bob")
        val s = WalkieState(members = names).copy(isTransmitting = true)
        assertEquals(names, s.members)
    }

    // ── txSecondsLeft ─────────────────────────────────────────────────────────

    @Test fun txSecondsLeftIsNullByDefault() = assertNull(WalkieState().txSecondsLeft)

    @Test fun txSecondsLeftPreservedOnCopy() {
        val s = WalkieState(isTransmitting = true).copy(txSecondsLeft = 3)
        assertEquals(3, s.txSecondsLeft)
        assertTrue(s.isTransmitting)
    }

    @Test fun txSecondsLeftClearedOnStopPttCopy() {
        val transmitting = WalkieState(isTransmitting = true, txSecondsLeft = 2)
        val stopped = transmitting.copy(isTransmitting = false, txSecondsLeft = null)
        assertFalse(stopped.isTransmitting)
        assertNull(stopped.txSecondsLeft)
    }

    @Test fun txSecondsLeftCountdownRange() {
        // Verify that all countdown values 5..1 can be represented in state
        for (n in 5 downTo 1) {
            val s = WalkieState(isTransmitting = true, txSecondsLeft = n)
            assertEquals(n, s.txSecondsLeft)
        }
    }

    // ── audioLevel ────────────────────────────────────────────────────────────

    @Test fun audioLevelIsZeroByDefault() = assertEquals(0f, WalkieState().audioLevel, 0.001f)

    @Test fun audioLevelPreservedOnCopy() {
        val s = WalkieState(audioLevel = 0.6f).copy(isTransmitting = true)
        assertEquals(0.6f, s.audioLevel, 0.001f)
    }

    @Test fun audioLevelClearedOnStopCopy() {
        val s = WalkieState(isTransmitting = true, audioLevel = 0.8f)
            .copy(isTransmitting = false, audioLevel = 0f)
        assertEquals(0f, s.audioLevel, 0.001f)
    }

    @Test fun audioLevelAndTxSecondsLeftAreIndependent() {
        val s = WalkieState(audioLevel = 0.5f, txSecondsLeft = 4)
        assertEquals(0.5f, s.audioLevel, 0.001f)
        assertEquals(4, s.txSecondsLeft)
    }
}

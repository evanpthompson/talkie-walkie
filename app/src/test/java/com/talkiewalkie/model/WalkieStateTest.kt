package com.talkiewalkie.model

import org.junit.Assert.*
import org.junit.Test

class WalkieStateTest {

    // ── ConnectionState.isActive ──────────────────────────────────────────────

    @Test fun disconnectedIsNotActive() = assertFalse(ConnectionState.Disconnected.isActive)
    @Test fun searchingIsNotActive()    = assertFalse(ConnectionState.Searching.isActive)
    @Test fun hostingIsActive()         = assertTrue(ConnectionState.Hosting.isActive)
    @Test fun connectedIsActive()       = assertTrue(ConnectionState.Connected("Hub").isActive)

    // ── ConnectionState.label ────────────────────────────────────────────────

    @Test fun disconnectedLabel()  = assertEquals("Not connected",      ConnectionState.Disconnected.label)
    @Test fun hostingLabel()       = assertEquals("Hosting channel…",   ConnectionState.Hosting.label)
    @Test fun searchingLabel()     = assertEquals("Searching for hub…", ConnectionState.Searching.label)

    @Test fun connectedLabelContainsDeviceName() {
        val label = ConnectionState.Connected("Alice's Phone").label
        assertTrue(label.contains("Alice's Phone"))
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
}

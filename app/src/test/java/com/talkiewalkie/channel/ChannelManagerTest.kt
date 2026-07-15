package com.talkiewalkie.channel

import org.junit.Assert.*
import org.junit.Test

class ChannelManagerTest {

    @Test fun sameNameAlwaysProducesSameUuid() {
        assertEquals(ChannelManager.channelUuid("riders"), ChannelManager.channelUuid("riders"))
    }

    @Test fun differentNamesProduceDifferentUuids() {
        assertNotEquals(ChannelManager.channelUuid("riders"), ChannelManager.channelUuid("hikers"))
    }

    @Test fun uuidIsVersion3() {
        assertEquals(3, ChannelManager.channelUuid("test").version())
    }

    @Test fun emptyNameIsStable() {
        assertEquals(ChannelManager.channelUuid(""), ChannelManager.channelUuid(""))
    }

    @Test fun caseIsSignificant() {
        assertNotEquals(ChannelManager.channelUuid("Riders"), ChannelManager.channelUuid("riders"))
    }

    @Test fun unicodeNameIsStable() {
        val u1 = ChannelManager.channelUuid("ライダー")
        val u2 = ChannelManager.channelUuid("ライダー")
        assertEquals(u1, u2)
    }

    @Test fun longNameDoesNotCrash() {
        val name = "a".repeat(1_000)
        assertNotNull(ChannelManager.channelUuid(name))
    }
}

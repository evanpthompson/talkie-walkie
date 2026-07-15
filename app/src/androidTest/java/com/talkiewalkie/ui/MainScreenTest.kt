package com.talkiewalkie.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit4.runners.AndroidJUnit4
import com.talkiewalkie.model.ConnectionState
import com.talkiewalkie.model.Role
import com.talkiewalkie.model.WalkieState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val hubState = WalkieState(
        channelName = "riders",
        role        = Role.HUB,
        connection  = ConnectionState.Hosting,
        members     = listOf("My Phone"),
    )

    // ── channel header ────────────────────────────────────────────────────────

    @Test fun channelNameIsDisplayed() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("riders").assertIsDisplayed()
    }

    @Test fun hubBadgeShownForHubRole() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("HUB").assertIsDisplayed()
    }

    @Test fun clientBadgeShownForClientRole() {
        val state = hubState.copy(role = Role.CLIENT, connection = ConnectionState.Connected("Hub"))
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("CLIENT").assertIsDisplayed()
    }

    @Test fun noBadgeWhenRoleIsNone() {
        rule.setContent { MainScreen(state = WalkieState(), onPttDown = {}, onPttUp = {}) }
        rule.onAllNodesWithText("HUB").assertCountEquals(0)
        rule.onAllNodesWithText("CLIENT").assertCountEquals(0)
    }

    // ── connection status card ────────────────────────────────────────────────

    @Test fun hostingLabelDisplayed() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("Hosting channel…").assertIsDisplayed()
    }

    @Test fun connectedLabelDisplayed() {
        val state = hubState.copy(connection = ConnectionState.Connected("Bob's Phone"))
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("Connected — Bob's Phone").assertIsDisplayed()
    }

    // ── member roster ─────────────────────────────────────────────────────────

    @Test fun memberNamesAreDisplayed() {
        val state = hubState.copy(members = listOf("My Phone", "Alice", "Bob"))
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("My Phone").assertIsDisplayed()
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test fun rosterHiddenWhenMembersEmpty() {
        val state = hubState.copy(members = emptyList())
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("Channel members").assertDoesNotExist()
    }

    @Test fun transmittingMemberHasMicIcon() {
        val state = hubState.copy(
            members            = listOf("My Phone", "Alice"),
            currentTransmitter = "Alice",
        )
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithContentDescription("Transmitting").assertIsDisplayed()
    }

    // ── PTT button labels ─────────────────────────────────────────────────────

    @Test fun pttShowsPushToTalkWhenConnected() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("PUSH TO TALK").assertIsDisplayed()
    }

    @Test fun pttShowsTransmittingWhenActive() {
        val state = hubState.copy(isTransmitting = true)
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("TRANSMITTING").assertIsDisplayed()
    }

    @Test fun pttShowsNotConnectedWhenDisconnected() {
        rule.setContent { MainScreen(state = WalkieState(), onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("NOT CONNECTED").assertIsDisplayed()
    }

    @Test fun pttShowsChannelBusyWhenBlocked() {
        val state = hubState.copy(isBlocked = true)
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("CHANNEL BUSY").assertIsDisplayed()
    }

    // ── riding mode toggle ────────────────────────────────────────────────────

    @Test fun ridingModeSwitchReflectsState() {
        rule.setContent {
            MainScreen(state = hubState.copy(ridingMode = true), onPttDown = {}, onPttUp = {})
        }
        rule.onNodeWithTag("riding_mode_switch").assertIsToggleable()
        rule.onNodeWithTag("riding_mode_switch").assertIsOn()
    }

    @Test fun ridingModeSwitchOffByDefault() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithTag("riding_mode_switch").assertIsOff()
    }

    @Test fun ridingModeToggleCallsCallback() {
        var received = false
        rule.setContent {
            MainScreen(
                state              = hubState,
                onPttDown          = {},
                onPttUp            = {},
                onRidingModeToggle = { received = it },
            )
        }
        rule.onNodeWithTag("riding_mode_switch").performClick()
        assertTrue(received)
    }

    // ── speaker toggle ────────────────────────────────────────────────────────

    @Test fun speakerSwitchOffByDefault() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithTag("speaker_switch").assertIsOff()
    }

    @Test fun speakerSwitchOnWhenStateIsOn() {
        rule.setContent {
            MainScreen(state = hubState.copy(speakerOn = true), onPttDown = {}, onPttUp = {})
        }
        rule.onNodeWithTag("speaker_switch").assertIsOn()
    }

    @Test fun speakerToggleCallsCallback() {
        var received = false
        rule.setContent {
            MainScreen(
                state          = hubState,
                onPttDown      = {},
                onPttUp        = {},
                onSpeakerToggle = { received = it },
            )
        }
        rule.onNodeWithTag("speaker_switch").performClick()
        assertTrue(received)
    }

    // ── blocked overlay ───────────────────────────────────────────────────────

    @Test fun blockedOverlayShownWhenBlocked() {
        val state = hubState.copy(isBlocked = true)
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("Channel Busy").assertIsDisplayed()
        rule.onNodeWithText("Another device is transmitting").assertIsDisplayed()
    }

    @Test fun blockedOverlayHiddenWhenNotBlocked() {
        rule.setContent { MainScreen(state = hubState, onPttDown = {}, onPttUp = {}) }
        rule.onAllNodesWithText("Channel Busy").assertCountEquals(0)
    }

    // ── listening-for-command indicator ───────────────────────────────────────

    @Test fun listeningIndicatorShownWhenActive() {
        val state = hubState.copy(listeningForCommand = true)
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("Listening for command…").assertIsDisplayed()
    }

    @Test fun lastCommandTextShownWhenPresent() {
        val state = hubState.copy(lastCommandText = "join riders")
        rule.setContent { MainScreen(state = state, onPttDown = {}, onPttUp = {}) }
        rule.onNodeWithText("Heard: \"join riders\"").assertIsDisplayed()
    }

    // ── PTT callbacks ─────────────────────────────────────────────────────────

    @Test fun pttDownAndUpCallbacksFire() {
        var downCalled = false
        var upCalled   = false
        rule.setContent {
            MainScreen(
                state     = hubState,
                onPttDown = { downCalled = true },
                onPttUp   = { upCalled   = true },
            )
        }
        rule.onNodeWithText("PUSH TO TALK").performTouchInput { down(center) }
        assertTrue(downCalled)
        rule.onNodeWithText("PUSH TO TALK").performTouchInput { up() }
        assertTrue(upCalled)
    }

    @Test fun pttCallbacksDoNotFireWhenDisconnected() {
        var downCalled = false
        rule.setContent {
            MainScreen(
                state     = WalkieState(),      // Disconnected / NONE role
                onPttDown = { downCalled = true },
                onPttUp   = {},
            )
        }
        rule.onNodeWithText("NOT CONNECTED").performTouchInput { down(center) }
        assertFalse(downCalled)
    }
}

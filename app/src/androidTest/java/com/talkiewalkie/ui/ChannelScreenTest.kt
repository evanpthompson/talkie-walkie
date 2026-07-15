package com.talkiewalkie.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit4.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // ── initial state ─────────────────────────────────────────────────────────

    @Test fun bothButtonsDisabledWhenNameIsEmpty() {
        rule.setContent { ChannelScreen(onCreateChannel = {}, onJoinChannel = {}) }
        rule.onNodeWithText("Create Channel  (Host)").assertIsNotEnabled()
        rule.onNodeWithText("Join Channel").assertIsNotEnabled()
    }

    @Test fun titleIsVisible() {
        rule.setContent { ChannelScreen(onCreateChannel = {}, onJoinChannel = {}) }
        rule.onNodeWithText("Talkie-Walkie").assertIsDisplayed()
    }

    @Test fun channelNameFieldIsVisible() {
        rule.setContent { ChannelScreen(onCreateChannel = {}, onJoinChannel = {}) }
        rule.onNodeWithText("Channel name").assertIsDisplayed()
    }

    // ── input enables buttons ─────────────────────────────────────────────────

    @Test fun buttonsEnabledAfterTypingName() {
        rule.setContent { ChannelScreen(onCreateChannel = {}, onJoinChannel = {}) }
        rule.onNodeWithText("Channel name").performTextInput("riders")
        rule.onNodeWithText("Create Channel  (Host)").assertIsEnabled()
        rule.onNodeWithText("Join Channel").assertIsEnabled()
    }

    @Test fun buttonsDisabledForWhitespaceOnlyInput() {
        rule.setContent { ChannelScreen(onCreateChannel = {}, onJoinChannel = {}) }
        rule.onNodeWithText("Channel name").performTextInput("   ")
        rule.onNodeWithText("Create Channel  (Host)").assertIsNotEnabled()
        rule.onNodeWithText("Join Channel").assertIsNotEnabled()
    }

    // ── callbacks ─────────────────────────────────────────────────────────────

    @Test fun createCallbackReceivesChannelName() {
        var received = ""
        rule.setContent {
            ChannelScreen(onCreateChannel = { received = it }, onJoinChannel = {})
        }
        rule.onNodeWithText("Channel name").performTextInput("riders")
        rule.onNodeWithText("Create Channel  (Host)").performClick()
        assertEquals("riders", received)
    }

    @Test fun joinCallbackReceivesChannelName() {
        var received = ""
        rule.setContent {
            ChannelScreen(onCreateChannel = {}, onJoinChannel = { received = it })
        }
        rule.onNodeWithText("Channel name").performTextInput("riders")
        rule.onNodeWithText("Join Channel").performClick()
        assertEquals("riders", received)
    }

    @Test fun channelNameIsTrimmedBeforeCallback() {
        var received = ""
        rule.setContent {
            ChannelScreen(onCreateChannel = { received = it }, onJoinChannel = {})
        }
        rule.onNodeWithText("Channel name").performTextInput("  riders  ")
        rule.onNodeWithText("Create Channel  (Host)").performClick()
        assertEquals("riders", received)
    }

    @Test fun joinTrimmedBeforeCallback() {
        var received = ""
        rule.setContent {
            ChannelScreen(onCreateChannel = {}, onJoinChannel = { received = it })
        }
        rule.onNodeWithText("Channel name").performTextInput("  hikers  ")
        rule.onNodeWithText("Join Channel").performClick()
        assertEquals("hikers", received)
    }

    // ── keyboard IME ──────────────────────────────────────────────────────────

    @Test fun imeDoneActionTriggersJoin() {
        var joinCalled = false
        rule.setContent {
            ChannelScreen(onCreateChannel = {}, onJoinChannel = { joinCalled = true })
        }
        rule.onNodeWithText("Channel name").performTextInput("riders")
        rule.onNodeWithText("Channel name").performImeAction()
        assertTrue(joinCalled)
    }
}

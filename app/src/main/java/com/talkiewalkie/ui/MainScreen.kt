package com.talkiewalkie.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.talkiewalkie.model.ConnectionState
import com.talkiewalkie.model.Role
import com.talkiewalkie.model.WalkieState

@Composable
fun MainScreen(
    state: WalkieState,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onRidingModeToggle: (Boolean) -> Unit = {},
    onSpeakerToggle: (Boolean) -> Unit    = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier              = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment   = Alignment.CenterHorizontally,
            verticalArrangement   = Arrangement.spacedBy(16.dp),
        ) {
            ChannelHeader(state)

            ConnectionStatusCard(state)

            if (state.members.isNotEmpty()) {
                MemberRoster(
                    members            = state.members,
                    currentTransmitter = state.currentTransmitter,
                )
            }

            Spacer(Modifier.weight(1f))

            state.currentTransmitter?.let { who ->
                Text(
                    "🔊 $who is transmitting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            if (state.listeningForCommand) {
                CommandListeningIndicator()
            }

            state.lastCommandText?.let { text ->
                Text(
                    "Heard: \"$text\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PttButton(
                isTransmitting = state.isTransmitting,
                isBlocked      = state.isBlocked,
                txSecondsLeft  = state.txSecondsLeft,
                // Keyed on connection only — not isBlocked — so that the
                // finger-release event still fires after a BLOCKED frame
                // arrives mid-press and lets stopPtt() clear the flag.
                enabled        = state.connection.isActive,
                onDown         = onPttDown,
                onUp           = onPttUp,
            )

            if (state.connection.isActive) {
                Text(
                    "Vol ↑ = Push to Talk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            RidingModeRow(
                ridingMode = state.ridingMode,
                onToggle   = onRidingModeToggle,
            )

            SpeakerRow(
                speakerOn = state.speakerOn,
                onToggle  = onSpeakerToggle,
            )
        }

        if (state.isBlocked) BlockedOverlay()
    }
}

// ── sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ChannelHeader(state: WalkieState) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            state.channelName ?: "Talkie-Walkie",
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.role != Role.NONE) {
            val label = if (state.role == Role.HUB) "HUB" else "CLIENT"
            val color = if (state.role == Role.HUB)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.secondary
            Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
                Text(
                    label,
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style      = MaterialTheme.typography.labelSmall,
                    color      = color,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: WalkieState) {
    val icon = when {
        state.connection.isActive                        -> Icons.Default.BluetoothConnected
        state.connection is ConnectionState.Reconnecting -> Icons.Default.Sync
        else                                             -> Icons.Default.Bluetooth
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(state.connection.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MemberRoster(members: List<String>, currentTransmitter: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Channel members",
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier            = Modifier.heightIn(max = 150.dp),
            ) {
                items(members) { name ->
                    val isTx = name == currentTransmitter
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isTx) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Transmitting",
                                tint     = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Spacer(Modifier.size(16.dp))
                        }
                        Text(
                            name,
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isTx) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandListeningIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "cmd-pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label         = "cmd-alpha",
    )
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.RecordVoiceOver,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary.copy(alpha = alpha),
        )
        Text(
            "Listening for command…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun PttButton(
    isTransmitting: Boolean,
    isBlocked: Boolean,
    txSecondsLeft: Int?,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val targetColor = when {
        isBlocked                          -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        isTransmitting && txSecondsLeft != null -> MaterialTheme.colorScheme.tertiary
        isTransmitting                     -> MaterialTheme.colorScheme.error
        enabled                            -> MaterialTheme.colorScheme.primary
        else                               -> MaterialTheme.colorScheme.surfaceVariant
    }
    val color by animateColorAsState(targetColor, label = "ptt-color")
    val scale by animateFloatAsState(
        targetValue   = if (isTransmitting) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label         = "ptt-scale",
    )

    val label = when {
        isBlocked                          -> "CHANNEL BUSY"
        isTransmitting && txSecondsLeft != null -> "${txSecondsLeft}s LEFT"
        isTransmitting                     -> "TRANSMITTING"
        enabled                            -> "PUSH TO TALK"
        else                               -> "NOT CONNECTED"
    }
    val icon = if (isTransmitting) Icons.Default.Mic else Icons.Default.MicOff

    Surface(
        shape    = CircleShape,
        color    = color,
        modifier = Modifier
            .size(180.dp)
            .scale(scale)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    onDown()
                    tryAwaitRelease()
                    onUp()
                })
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint     = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    label,
                    style     = MaterialTheme.typography.labelLarge,
                    color     = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RidingModeRow(ridingMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = if (ridingMode) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text("Riding mode", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (ridingMode) "Say \"Porcupine\" to activate voice commands"
                    else "Tap to enable hands-free voice commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked         = ridingMode,
            onCheckedChange = onToggle,
            modifier        = Modifier.testTag("riding_mode_switch"),
        )
    }
}

@Composable
private fun SpeakerRow(speakerOn: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (speakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                contentDescription = null,
                tint = if (speakerOn) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text("Loudspeaker", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (speakerOn) "Playing through speaker" else "Playing through earpiece",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked         = speakerOn,
            onCheckedChange = onToggle,
            modifier        = Modifier.testTag("speaker_switch"),
        )
    }
}

@Composable
private fun BlockedOverlay() {
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                modifier              = Modifier.padding(24.dp),
                horizontalAlignment   = Alignment.CenterHorizontally,
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.MicOff,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp),
                )
                Text("Channel Busy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Another device is transmitting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

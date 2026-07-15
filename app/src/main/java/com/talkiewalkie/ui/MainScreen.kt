package com.talkiewalkie.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.talkiewalkie.model.ConnectionState
import com.talkiewalkie.model.WalkieState

@Composable
fun MainScreen(
    state: WalkieState,
    pairedDevices: List<BluetoothDevice>,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement  = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionStatusCard(state.connection)

        // Replaces normal content while Gemini is processing a voice command.
        AnimatedVisibility(
            visible = state.listeningForCommand,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            CommandListeningBanner()
        }

        Spacer(Modifier.weight(1f))

        PttButton(
            isTransmitting        = state.isTransmitting,
            isListeningForCommand = state.listeningForCommand,
            enabled               = state.connection.isConnected && !state.listeningForCommand,
            onDown                = onPttDown,
            onUp                  = onPttUp,
        )

        WakeWordRow(
            enabled   = state.wakeWordEnabled,
            connected = state.connection.isConnected,
            onToggle  = onWakeWordToggle,
        )

        // Show last Gemini-interpreted command text as subtle feedback.
        state.lastCommandText?.let { text ->
            Text(
                text  = "Heard: \"$text\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        if (!state.connection.isConnected) {
            PairedDeviceList(
                devices   = pairedDevices,
                onConnect = onConnectDevice,
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(connection: ConnectionState) {
    val icon = if (connection.isConnected) Icons.Default.BluetoothConnected
               else Icons.Default.Bluetooth
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(connection.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CommandListeningBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue   = 0.4f,
        targetValue    = 1f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha),
            )
            Column {
                Text(
                    "Listening for command…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Say: \"connect to John\" · \"start transmitting\" · \"disconnect\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PttButton(
    isTransmitting: Boolean,
    isListeningForCommand: Boolean,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val targetColor = when {
        isListeningForCommand -> MaterialTheme.colorScheme.secondary
        isTransmitting        -> MaterialTheme.colorScheme.error
        else                  -> MaterialTheme.colorScheme.primary
    }
    val color by animateColorAsState(targetColor, label = "ptt-color")

    val scale by animateFloatAsState(
        targetValue   = if (isTransmitting || isListeningForCommand) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label         = "ptt-scale",
    )

    val label = when {
        isListeningForCommand -> "LISTENING…"
        isTransmitting        -> "TRANSMITTING"
        enabled               -> "PUSH TO TALK"
        else                  -> "NOT CONNECTED"
    }

    val icon = when {
        isListeningForCommand -> Icons.Default.RecordVoiceOver
        isTransmitting        -> Icons.Default.Mic
        else                  -> Icons.Default.MicOff
    }

    Surface(
        shape = CircleShape,
        color = if (enabled || isListeningForCommand) color
                else MaterialTheme.colorScheme.surfaceVariant,
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
private fun WakeWordRow(
    enabled: Boolean,
    connected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Voice commands", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !connected          -> "Connect first"
                    enabled             -> "Say \"Porcupine\", then your command"
                    else                -> "Disabled — use PTT button"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked         = enabled && connected,
            onCheckedChange = onToggle,
            enabled         = connected,
        )
    }
}

@Composable
private fun PairedDeviceList(
    devices: List<BluetoothDevice>,
    onConnect: (BluetoothDevice) -> Unit,
) {
    if (devices.isEmpty()) return
    Column {
        Text(
            "Paired devices",
            style    = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
                OutlinedButton(
                    onClick  = { onConnect(device) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(device.name ?: device.address)
                }
            }
        }
    }
}

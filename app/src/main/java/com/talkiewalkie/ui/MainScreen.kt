package com.talkiewalkie.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
        verticalArrangement  = Arrangement.spacedBy(24.dp),
    ) {
        ConnectionStatusCard(state.connection)

        Spacer(Modifier.weight(1f))

        PttButton(
            isTransmitting = state.isTransmitting,
            enabled        = state.connection.isConnected,
            onDown         = onPttDown,
            onUp           = onPttUp,
        )

        WakeWordRow(
            enabled   = state.wakeWordEnabled,
            connected = state.connection.isConnected,
            onToggle  = onWakeWordToggle,
        )

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
private fun PttButton(
    isTransmitting: Boolean,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val targetColor = if (isTransmitting) MaterialTheme.colorScheme.error
                      else MaterialTheme.colorScheme.primary
    val color by animateColorAsState(targetColor, label = "ptt-color")

    val scale by animateFloatAsState(
        targetValue = if (isTransmitting) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ptt-scale",
    )

    val label = if (isTransmitting) "TRANSMITTING" else if (enabled) "PUSH TO TALK" else "NOT CONNECTED"

    Surface(
        shape = CircleShape,
        color = if (enabled) color else MaterialTheme.colorScheme.surfaceVariant,
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
                    if (isTransmitting) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
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
            Text("Wake word", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (enabled && connected) "Say \"Porcupine\" to transmit"
                else if (!connected) "Connect first"
                else "Disabled",
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

package com.talkiewalkie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.talkiewalkie.channel.FoundChannel
import com.talkiewalkie.model.Role
import com.talkiewalkie.prefs.ChannelPrefs

@Composable
fun ChannelScreen(
    onCreateChannel: (String) -> Unit,
    onJoinChannel: (String) -> Unit,
    scanResults: List<FoundChannel> = emptyList(),
    isScanning: Boolean = false,
    onScan: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context   = LocalContext.current
    val lastSaved = remember { ChannelPrefs.load(context) }

    var channelName by remember { mutableStateOf("") }
    val trimmed = channelName.trim()
    val valid   = trimmed.isNotEmpty()

    Column(
        modifier            = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 56.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Talkie-Walkie",
            style     = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Scan for nearby channels or enter a name to host",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // ── last session ──────────────────────────────────────────────────────

        if (lastSaved != null) {
            val (savedName, savedRole) = lastSaved
            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Last session",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(savedName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick  = {
                            if (savedRole == Role.HUB) onCreateChannel(savedName)
                            else onJoinChannel(savedName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (savedRole == Role.HUB) "Rejoin as Host" else "Rejoin Channel")
                    }
                }
            }
        }

        // ── channel discovery ─────────────────────────────────────────────────

        Spacer(Modifier.height(24.dp))

        if (isScanning) {
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Scanning for channels…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            OutlinedButton(
                onClick  = onScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan for Channels")
            }
        }

        scanResults.forEach { channel ->
            Spacer(Modifier.height(8.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier              = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(channel.channelName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "on ${channel.deviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onJoinChannel(channel.channelName) }) {
                        Text("Join")
                    }
                }
            }
        }

        if (scanResults.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onScan, modifier = Modifier.align(Alignment.End)) {
                Text("Scan again")
            }
        }

        // ── manual entry ──────────────────────────────────────────────────────

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            "Or enter a channel name",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = channelName,
            onValueChange = { channelName = it },
            label         = { Text("Channel name") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (valid) onJoinChannel(trimmed)
            }),
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick  = { if (valid) onCreateChannel(trimmed) },
            enabled  = valid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create Channel  (Host)")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick  = { if (valid) onJoinChannel(trimmed) },
            enabled  = valid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Join Channel")
        }
    }
}

package com.talkiewalkie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.talkiewalkie.model.Role
import com.talkiewalkie.prefs.ChannelPrefs

@Composable
fun ChannelScreen(
    onCreateChannel: (String) -> Unit,
    onJoinChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context  = LocalContext.current
    val lastSaved = remember { ChannelPrefs.load(context) }

    var channelName by remember { mutableStateOf("") }
    val trimmed = channelName.trim()
    val valid   = trimmed.isNotEmpty()

    Column(
        modifier               = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment    = Alignment.CenterHorizontally,
        verticalArrangement    = Arrangement.Center,
    ) {
        Text(
            "Talkie-Walkie",
            style     = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Enter a channel name to host or join",
            style  = MaterialTheme.typography.bodyMedium,
            color  = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

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
                    Text(
                        savedName,
                        style = MaterialTheme.typography.titleMedium,
                    )
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

        Spacer(Modifier.height(if (lastSaved != null) 24.dp else 40.dp))

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

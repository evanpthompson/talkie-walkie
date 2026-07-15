package com.talkiewalkie.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ChannelScreen(
    onCreateChannel: (String) -> Unit,
    onJoinChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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

        Spacer(Modifier.height(40.dp))

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

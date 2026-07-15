package com.talkiewalkie.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TransmitRed  = Color(0xFFE53935)
private val ReceiveGreen = Color(0xFF43A047)
private val NavyBlue     = Color(0xFF1565C0)

private val LightColors = lightColorScheme(
    primary   = NavyBlue,
    error     = TransmitRed,
    secondary = ReceiveGreen,
)

private val DarkColors = darkColorScheme(
    primary   = Color(0xFF90CAF9),
    error     = Color(0xFFEF9A9A),
    secondary = ReceiveGreen,
)

@Composable
fun TalkieWalkieTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content     = content,
    )
}

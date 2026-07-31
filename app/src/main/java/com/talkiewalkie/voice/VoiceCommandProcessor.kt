package com.talkiewalkie.voice

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content

class VoiceCommandProcessor(apiKey: String) {

    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey    = apiKey,
        tools     = listOf(walkieTalkieTools),
        systemInstruction = content {
            text(
                """
                You control a Bluetooth walkie-talkie Android app.
                The user spoke a voice command immediately after a wake word.
                Parse their intent and call exactly one of the provided functions.
                If the command is ambiguous, pick the most likely interpretation.
                Ignore filler words and background noise in the transcription.
                """.trimIndent()
            )
        }
    )

    suspend fun process(spokenText: String): VoiceCommand {
        if (spokenText.isBlank()) return VoiceCommand.Unknown(spokenText)

        return try {
            val response = model.generateContent(spokenText)
            val call = response.candidates
                .firstOrNull()
                ?.content
                ?.parts
                ?.filterIsInstance<FunctionCallPart>()
                ?.firstOrNull()
                ?: return VoiceCommand.Unknown(spokenText)

            // FunctionCallPart.args is a plain Map<String, String> in this SDK version.
            val args = call.args

            when (call.name) {
                "create_channel"     -> VoiceCommand.CreateChannel(args["channel_name"].orEmpty())
                "join_channel"       -> VoiceCommand.JoinChannel(args["channel_name"].orEmpty())
                "start_transmitting" -> VoiceCommand.StartTransmitting
                "stop_transmitting"  -> VoiceCommand.StopTransmitting
                "disconnect"         -> VoiceCommand.Disconnect
                "set_riding_mode"    ->
                    VoiceCommand.SetRidingMode(args["enabled"]?.toBooleanStrictOrNull() ?: true)
                else -> VoiceCommand.Unknown(spokenText)
            }
        } catch (_: Exception) {
            VoiceCommand.Unknown(spokenText)
        }
    }
}

private val walkieTalkieTools = Tool(
    functionDeclarations = listOf(
        FunctionDeclaration(
            name        = "create_channel",
            description = "Create a new channel and become the hub for other devices",
            parameters  = listOf(
                Schema.str("channel_name", "Name of the channel to create")
            ),
            requiredParameters = listOf("channel_name")
        ),
        FunctionDeclaration(
            name        = "join_channel",
            description = "Join an existing channel hosted by another device",
            parameters  = listOf(
                Schema.str("channel_name", "Name of the channel to join")
            ),
            requiredParameters = listOf("channel_name")
        ),
        FunctionDeclaration(
            name        = "start_transmitting",
            description = "Start transmitting the user's voice to the channel",
            parameters  = emptyList(),
            requiredParameters = emptyList()
        ),
        FunctionDeclaration(
            name        = "stop_transmitting",
            description = "Stop transmitting audio",
            parameters  = emptyList(),
            requiredParameters = emptyList()
        ),
        FunctionDeclaration(
            name        = "disconnect",
            description = "Leave the current channel",
            parameters  = emptyList(),
            requiredParameters = emptyList()
        ),
        FunctionDeclaration(
            name        = "set_riding_mode",
            description = "Enable or disable riding mode (hands-free voice command activation)",
            parameters  = listOf(
                Schema.bool("enabled", "True to enable riding mode, false to disable it")
            ),
            requiredParameters = listOf("enabled")
        )
    )
)

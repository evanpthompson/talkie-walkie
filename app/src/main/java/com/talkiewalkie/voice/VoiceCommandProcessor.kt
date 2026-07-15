package com.talkiewalkie.voice

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.FunctionType
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.content

class VoiceCommandProcessor(apiKey: String) {

    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey,
        tools = listOf(walkieTalkieTools),
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

            val args = call.args ?: emptyMap()

            when (call.name) {
                "connect_to_device" -> {
                    val name = (args["device_name"] as? JsonPrimitive)?.content.orEmpty()
                    VoiceCommand.ConnectToDevice(name)
                }
                "start_transmitting" -> VoiceCommand.StartTransmitting
                "stop_transmitting"  -> VoiceCommand.StopTransmitting
                "disconnect"         -> VoiceCommand.Disconnect
                "set_wake_word" -> {
                    val enabled = (args["enabled"] as? JsonPrimitive)?.boolean ?: true
                    VoiceCommand.SetWakeWord(enabled)
                }
                else -> VoiceCommand.Unknown(spokenText)
            }
        } catch (e: Exception) {
            VoiceCommand.Unknown(spokenText)
        }
    }
}

private val walkieTalkieTools = Tool(
    functionDeclarations = listOf(
        FunctionDeclaration(
            name = "connect_to_device",
            description = "Connect to a paired Bluetooth device by name",
            parameters = Schema(
                type = FunctionType.OBJECT,
                properties = mapOf(
                    "device_name" to Schema(
                        type = FunctionType.STRING,
                        description = "The name of the Bluetooth device to connect to"
                    )
                ),
                required = listOf("device_name")
            )
        ),
        FunctionDeclaration(
            name = "start_transmitting",
            description = "Start transmitting the user's voice to the connected device",
            parameters = emptyObjectSchema()
        ),
        FunctionDeclaration(
            name = "stop_transmitting",
            description = "Stop transmitting audio",
            parameters = emptyObjectSchema()
        ),
        FunctionDeclaration(
            name = "disconnect",
            description = "Disconnect from the currently connected Bluetooth device",
            parameters = emptyObjectSchema()
        ),
        FunctionDeclaration(
            name = "set_wake_word",
            description = "Enable or disable voice-triggered transmission via wake word",
            parameters = Schema(
                type = FunctionType.OBJECT,
                properties = mapOf(
                    "enabled" to Schema(
                        type = FunctionType.BOOLEAN,
                        description = "True to enable wake word detection, false to disable it"
                    )
                ),
                required = listOf("enabled")
            )
        )
    )
)

private fun emptyObjectSchema() = Schema(
    type = FunctionType.OBJECT,
    properties = emptyMap(),
    required = emptyList()
)

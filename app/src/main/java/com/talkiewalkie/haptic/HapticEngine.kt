package com.talkiewalkie.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticEngine(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun pttOpen()   = vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    fun pttClose()  = vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    fun blocked()   = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
    fun rxStart()   = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 35, 25), -1))
    fun txWarning() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))

    private fun vibrate(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }
}

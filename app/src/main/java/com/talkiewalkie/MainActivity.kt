package com.talkiewalkie

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talkiewalkie.channel.FoundChannel
import com.talkiewalkie.model.WalkieState
import com.talkiewalkie.service.WalkieTalkieService
import com.talkiewalkie.ui.ChannelScreen
import com.talkiewalkie.ui.MainScreen
import com.talkiewalkie.ui.theme.TalkieWalkieTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var walkieService: WalkieTalkieService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            walkieService = (binder as WalkieTalkieService.LocalBinder).service()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) bindWalkieService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        setContent {
            TalkieWalkieTheme {
                val service = walkieService
                val state by (service?.state ?: MutableStateFlow(WalkieState()))
                    .collectAsStateWithLifecycle()
                val scanResults by (service?.scanResults
                        ?: MutableStateFlow<List<FoundChannel>>(emptyList()))
                    .collectAsStateWithLifecycle()
                val isScanning by (service?.isScanning ?: MutableStateFlow(false))
                    .collectAsStateWithLifecycle()

                LaunchedEffect(state.ridingMode) {
                    if (state.ridingMode) {
                        window.addFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true)
                            setTurnScreenOn(true)
                        }
                    } else {
                        window.clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(false)
                            setTurnScreenOn(false)
                        }
                    }
                }

                if (state.channelName == null) {
                    ChannelScreen(
                        onCreateChannel = { name -> service?.createChannel(name) },
                        onJoinChannel   = { name -> service?.joinChannel(name) },
                        scanResults     = scanResults,
                        isScanning      = isScanning,
                        onScan          = { service?.scanForChannels() },
                    )
                } else {
                    MainScreen(
                        state              = state,
                        onPttDown          = { service?.startPtt() },
                        onPttUp            = { service?.stopPtt() },
                        onRidingModeToggle = { on -> service?.setRidingMode(on) },
                        onSpeakerToggle    = { on -> service?.setSpeakerOn(on) },
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(Manifest.permission.FOREGROUND_SERVICE)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun bindWalkieService() {
        val intent = Intent(this, WalkieTalkieService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.repeatCount == 0) {
            val svc = walkieService ?: return super.onKeyDown(keyCode, event)
            if (svc.state.value.connection.isActive) {
                svc.startPtt()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val svc = walkieService ?: return super.onKeyUp(keyCode, event)
            if (svc.state.value.isTransmitting) {
                svc.stopPtt()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        if (bound) unbindService(serviceConnection)
        super.onDestroy()
    }
}

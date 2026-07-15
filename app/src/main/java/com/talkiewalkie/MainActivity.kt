package com.talkiewalkie

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.talkiewalkie.model.WalkieState
import com.talkiewalkie.service.WalkieTalkieService
import com.talkiewalkie.ui.MainScreen
import com.talkiewalkie.ui.theme.TalkieWalkieTheme

class MainActivity : ComponentActivity() {

    private var walkieService: WalkieTalkieService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            walkieService = (binder as WalkieTalkieService.LocalBinder).service()
            bound = true
            walkieService?.goLive()
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
                val pairedDevices = remember { getPairedDevices() }

                MainScreen(
                    state          = state,
                    pairedDevices  = pairedDevices,
                    onPttDown      = { service?.startPtt() },
                    onPttUp        = { service?.stopPtt() },
                    onConnectDevice = { device -> service?.connectTo(device.address) },
                    onWakeWordToggle = { enabled -> service?.setWakeWordEnabled(enabled) },
                )
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

    @Suppress("MissingPermission")
    private fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    override fun onDestroy() {
        if (bound) unbindService(serviceConnection)
        super.onDestroy()
    }
}

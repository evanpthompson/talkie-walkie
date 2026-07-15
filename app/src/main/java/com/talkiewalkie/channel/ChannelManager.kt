package com.talkiewalkie.channel

import android.bluetooth.BluetoothAdapter
import java.util.UUID

object ChannelManager {
    fun channelUuid(name: String): UUID =
        UUID.nameUUIDFromBytes("tw.channel.$name".toByteArray(Charsets.UTF_8))

    @Suppress("MissingPermission")
    fun pairedDeviceNames(adapter: BluetoothAdapter): List<String> =
        adapter.bondedDevices?.mapNotNull { it.name } ?: emptyList()
}

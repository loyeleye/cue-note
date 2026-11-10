package com.cuenote.practice

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.cuenote.audio.Headphones
import com.cuenote.audio.Output
import com.cuenote.audio.UsbMic

/** What the phone plays through and records from right now, updated as things are plugged in. */
data class Devices(val output: Output, val lessonMic: Boolean)

@Composable
fun rememberDevices(): Devices {
    val context = LocalContext.current
    fun read() = Devices(Headphones.current(context), UsbMic.find(context) != null)
    var devices by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val am = context.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { devices = read() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { devices = read() }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    return devices
}

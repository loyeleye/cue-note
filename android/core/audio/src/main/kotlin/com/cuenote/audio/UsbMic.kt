package com.cuenote.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** What the phone can see of the plugged-in mic receiver. */
data class MicInfo(
    val name: String,
    val isUsb: Boolean,
    /** Channel counts the device reports; empty means "any". */
    val channelCounts: List<Int>,
) {
    val supportsStereo: Boolean get() = channelCounts.isEmpty() || 2 in channelCounts
}

object UsbMic {
    private val usbTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)

    /** The USB input (the Lark receiver) if one is plugged in. */
    fun find(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in usbTypes }
    }

    fun describe(device: AudioDeviceInfo?): MicInfo? = device?.let {
        MicInfo(
            name = it.productName?.toString().orEmpty().ifBlank { "USB mic" },
            isUsb = it.type in usbTypes,
            channelCounts = it.channelCounts.toList(),
        )
    }

    fun describeAny(device: AudioDeviceInfo?): String = when {
        device == null -> "unknown"
        device.type in usbTypes -> device.productName?.toString().orEmpty().ifBlank { "USB mic" }
        device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone's built-in mic"
        else -> device.productName?.toString().orEmpty().ifBlank { "type ${device.type}" }
    }
}

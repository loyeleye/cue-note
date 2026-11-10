package com.cuenote.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** How the student is listening while they practice. */
enum class Listening {
    /** Wired or USB-C headphones: the backing stays out of the take, with little delay. */
    WIRED,
    /** Bluetooth: no bleed, but usually too much delay to sing along in time. */
    BLUETOOTH,
    /** The phone's own speaker: anything played can leak into the take. */
    SPEAKER,
}

/** What the phone is playing through now, and a key to remember its timing by. */
data class Output(val listening: Listening, val name: String) {
    /** The timing check's result is kept per pair of headphones. */
    val key: String get() = "${listening.name.lowercase()}:$name"
}

object Headphones {
    private val wired = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
    )
    private val bluetooth = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /**
     * Wired wins over Bluetooth when both are connected, as Android routes
     * media to a plugged-in pair. The lesson mic receiver can also show up
     * as a USB output; it isn't headphones, so it is skipped.
     */
    fun current(context: Context): Output {
        val am = context.getSystemService(AudioManager::class.java)
        val receiver = UsbMic.find(context)?.productName?.toString()
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filterNot { it.type == AudioDeviceInfo.TYPE_USB_DEVICE && receiver != null && it.productName?.toString() == receiver }
        fun nameOf(d: AudioDeviceInfo) = d.productName?.toString().orEmpty().ifBlank { "headphones" }
        outs.firstOrNull { it.type in wired }?.let { return Output(Listening.WIRED, nameOf(it)) }
        outs.firstOrNull { it.type in bluetooth }?.let { return Output(Listening.BLUETOOTH, nameOf(it)) }
        return Output(Listening.SPEAKER, "phone speaker")
    }
}

package com.cuenote.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.posture.PostureService

/**
 * One line on the recording screen while posture is on: what the camera is
 * doing ("watching", "waiting for singing", "paused: phone warm") and how
 * many moments it has flagged, and a "Reset posture" button. Posture keeps the screen on, dimmed right
 * down, since some phones close the camera when the screen turns off.
 */
@Composable
internal fun PostureLine() {
    val on by PostureService.running.collectAsStateWithLifecycle()
    if (!on) return
    val context = LocalContext.current
    var status by remember { mutableStateOf("starting") }
    var flags by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                status = i.getStringExtra(PostureService.EXTRA_STATUS) ?: status
                flags = i.getIntExtra(PostureService.EXTRA_FLAGS, flags)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PostureService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val window = (context as? ComponentActivity)?.window
        val before = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply { screenBrightness = 0.01f }
        onDispose {
            context.unregisterReceiver(receiver)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply { screenBrightness = before ?: -1f }
        }
    }
    Text(
        "Posture: $status" + if (flags > 0) " · $flags flagged" else "",
        color = if (status.startsWith("paused") || status.startsWith("off")) Color(0xFFF2A49B) else Color(0xFFACA2B5),
    )
    // For a lesson that's about posture: both pause, and the phone talks the
    // student back into place and takes how they stand now as the new baseline.
    if (!status.startsWith("off") && status != "resetting") {
        TextButton(onClick = { PostureService.recalibrate(context) }) {
            Text("Reset posture", color = Color(0xFFE0A94A))
        }
    }
}

package com.cuenote.lesson

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * The debug timeline, shown in a WebView from the lesson folder so it plays
 * the lesson's own audio chunks. Saving labels writes labels.json next to the
 * lesson; "Start over" or the laptop runner then scores against it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TimelineScreen(lessonDir: File, onLabelsSaved: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Debug timeline") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    // The page and the audio chunks are files in the app's own lesson folder.
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun saveLabels(json: String) {
                            File(lessonDir, "labels.json").writeText(json)
                            post {
                                Toast.makeText(ctx, "Saved labels.json", Toast.LENGTH_SHORT).show()
                                onLabelsSaved()
                            }
                        }
                    }, "CueNote")
                    loadUrl(File(lessonDir, "pipeline/timeline.html").toURI().toString())
                }
            },
        )
    }
}

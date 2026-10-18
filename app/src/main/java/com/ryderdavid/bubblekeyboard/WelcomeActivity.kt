package com.ryderdavid.bubblekeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit

/**
 * The welcome video: a minute, narrated, of how the bar works. Shown once (the first time
 * Voicebar's settings open) and again from How to use it. Skip any time; at the end, Watch again
 * or Continue.
 */
class WelcomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.get(this).edit { putBoolean(Prefs.WELCOME_SEEN, true) }
        val uri = Uri.parse("android.resource://$packageName/${R.raw.welcome}")
        setContent {
            BubbleTheme {
                var ended by remember { mutableStateOf(false) }
                var video by remember { mutableStateOf<VideoView?>(null) }
                Box(Modifier.fillMaxSize().background(Color(0xFFE9ECF7))) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(uri)
                                setOnCompletionListener { ended = true }
                                setOnPreparedListener { start() }
                                // tap to pause or carry on
                                setOnClickListener { if (isPlaying) pause() else { ended = false; start() } }
                                video = this
                            }
                        },
                        modifier = Modifier.align(Alignment.Center).safeDrawingPadding(),
                    )
                    Row(Modifier.align(if (ended) Alignment.BottomCenter else Alignment.TopEnd).safeDrawingPadding().padding(20.dp)) {
                        if (ended) {
                            FilledTonalButton(onClick = { ended = false; video?.seekTo(0); video?.start() }) { Text(stringResource(R.string.welcome_again)) }
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = { finish() }) { Text(stringResource(R.string.welcome_continue)) }
                        } else {
                            FilledTonalButton(onClick = { finish() }) { Text(stringResource(R.string.welcome_skip)) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, WelcomeActivity::class.java))
        fun seen(context: Context) = Prefs.get(context).getBoolean(Prefs.WELCOME_SEEN, false)
    }
}

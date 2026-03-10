package com.rayneo.client

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var eglBase: EglBase

    private lateinit var streamClient: StreamClient
    private lateinit var rayNeoSession: RayNeoSession

    private var shouldAutoRecenter = true
    private var autoRecenterAfterMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eglBase = EglBase.create()

        // Full-screen video surface — this is what the glasses display
        videoView = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 12, 24, 12)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "Starting..."
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { topMargin = 16; marginStart = 16 })
        }
        setContentView(root)

        // Keep screen on — glasses need a live signal
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system UI for a cleaner display on the glasses
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        streamClient = StreamClient(
            context = this,
            serverHost = BuildConfig.SERVER_HOST,
            serverPort = BuildConfig.SERVER_PORT,
            onStatus = { msg ->
                runOnUiThread { statusText.text = msg }
                // Hide status overlay after we're streaming so it doesn't obscure the video
                if (msg == "Streaming") {
                    lifecycleScope.launch {
                        delay(2000)
                        runOnUiThread { statusText.visibility = View.GONE }
                    }
                }
            },
            onVideoTrack = { track -> attachVideoTrack(track) }
        )

        rayNeoSession = RayNeoSession(
            context = this,
            onStatus = { msg -> runOnUiThread { statusText.text = msg } },
            onOrientation = { quaternion ->
                // Auto-recenter yaw on first stable pose
                if (shouldAutoRecenter && SystemClock.elapsedRealtime() >= autoRecenterAfterMs) {
                    shouldAutoRecenter = false
                }
                // Forward pose to server for reprojection / window positioning
                streamClient.sendPose(quaternion)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        shouldAutoRecenter = true
        autoRecenterAfterMs = SystemClock.elapsedRealtime() + AUTO_RECENTER_DELAY_MS
        videoView.onResume()
        streamClient.start()
        rayNeoSession.start()
    }

    override fun onPause() {
        rayNeoSession.stop()
        streamClient.stop()
        videoView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        videoView.release()
        eglBase.release()
        super.onDestroy()
    }

    private fun attachVideoTrack(track: VideoTrack) {
        track.addSink(videoView)
        statusText.visibility = View.GONE
    }

    companion object {
        private const val AUTO_RECENTER_DELAY_MS = 2000L
    }
}

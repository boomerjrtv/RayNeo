package com.rayneo.client

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoTrack

private const val TAG = "XRVideoView"

/**
 * Composite view that wraps the full 3DOF XR rendering pipeline.
 *
 * Layout: a single [GLSurfaceView] that fills the frame.
 *
 * Lifecycle summary:
 *   1. Construct / inflate the view.
 *   2. Call [attachVideoTrack] once a WebRTC [VideoTrack] and shared [EglBase] are available.
 *      This creates an [EglRenderer] that decodes WebRTC frames onto the [XRRenderer]'s
 *      [android.graphics.SurfaceTexture], completing the video pipeline.
 *   3. Feed IMU data in real-time via [updateHeadPose].
 *   4. Call [recenter] whenever the user wants to reset the "forward" direction.
 *   5. Call [release] in onPause / onDestroy to free GL and WebRTC resources.
 *
 * Threading:
 *   - [updateHeadPose] and [recenter] are thread-safe (delegated to [XRRenderer]).
 *   - [attachVideoTrack] and [release] must be called on the main thread.
 */
class XRVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val glSurfaceView: GLSurfaceView
    private val renderer = XRRenderer()

    // Non-null after attachVideoTrack(); used for cleanup in release()
    private var eglRenderer: EglRenderer? = null
    private var attachedTrack: VideoTrack? = null

    init {
        glSurfaceView = GLSurfaceView(context).apply {
            // Request an OpenGL ES 2.0 context
            setEGLContextClientVersion(2)

            // Render continuously for smooth head-tracked motion
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            setRenderer(renderer)
        }

        addView(
            glSurfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    // ── Head-pose API ─────────────────────────────────────────────────────────

    /**
     * Forward the latest IMU quaternion [w, x, y, z] to the renderer.
     * Thread-safe; call from the IMU/sensor thread at full rate.
     */
    fun updateHeadPose(quaternion: FloatArray) {
        renderer.updateHeadPose(quaternion)
    }

    /**
     * Reset the "forward" reference to the current head orientation.
     * After this call the virtual screen will appear directly ahead.
     * Thread-safe.
     */
    fun recenter() {
        renderer.recenter()
    }

    // ── Video pipeline ────────────────────────────────────────────────────────

    /**
     * Wire up a WebRTC [VideoTrack] to the XR renderer.
     *
     * This function:
     *   1. Waits (via a one-shot [GLSurfaceView.queueEvent]) until the [XRRenderer]'s
     *      [android.graphics.SurfaceTexture] is available on the GL thread.
     *   2. Wraps the [SurfaceTexture] in a [Surface].
     *   3. Creates an [EglRenderer] that uses the provided [eglBase] context so its
     *      EGL context shares the texture objects with the GL thread.
     *   4. Initialises the [EglRenderer] with the [Surface] as its render target.
     *   5. Adds the [EglRenderer] as a sink to [track].
     *
     * Must be called on the main thread.  Call at most once per view instance
     * (call [release] before re-attaching a different track).
     *
     * @param track   The incoming WebRTC video track from [StreamClient].
     * @param eglBase The shared [EglBase] created in the host Activity.
     */
    fun attachVideoTrack(track: VideoTrack, eglBase: EglBase) {
        // We need the SurfaceTexture, which is only valid after the GL surface has been
        // created.  Queue the work on the GL thread so we're guaranteed it exists.
        glSurfaceView.queueEvent {
            val st = renderer.surfaceTexture
            if (st == null) {
                Log.e(TAG, "SurfaceTexture not ready — attachVideoTrack called too early?")
                return@queueEvent
            }

            val surface = Surface(st)

            // EglRenderer renders WebRTC frames into the Surface (which wraps our OES
            // SurfaceTexture) using a shared EGL context so the decoded texture is
            // immediately available to XRRenderer on the GL thread.
            val eglRend = EglRenderer("XRVideoView").also { eglRenderer = it }
            eglRend.init(
                eglBase.eglBaseContext,
                EglBase.CONFIG_PLAIN,
                GlRectDrawer()
            )
            eglRend.createEglSurface(surface)

            // Back on the main thread, add the sink to the track
            post {
                track.addSink(eglRend)
                attachedTrack = track
                Log.i(TAG, "VideoTrack attached to XRRenderer via EglRenderer")
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Release GL and WebRTC resources.  Call from the host Activity's onPause or onDestroy.
     * After calling release(), do not call any other methods on this view.
     */
    fun release() {
        attachedTrack?.removeSink(eglRenderer)
        attachedTrack = null

        eglRenderer?.release()
        eglRenderer = null

        renderer.surfaceTexture?.release()

        glSurfaceView.onPause()
    }

    /**
     * Resume rendering after [release] has NOT been called — mirrors [GLSurfaceView.onResume].
     * Call from the host Activity's onResume.
     */
    fun resume() {
        glSurfaceView.onResume()
    }
}

package com.rayneo.client

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "XRRenderer"

/**
 * GLSurfaceView.Renderer implementing 3DOF head-locked rendering.
 *
 * Architecture overview:
 *   - An OES external texture is fed by a SurfaceTexture.
 *   - The SurfaceTexture is wrapped in a Surface and handed to WebRTC's EglRenderer,
 *     which decodes frames onto it.  This renderer then draws those frames as a large
 *     flat "cinema screen" quad whose apparent position is fixed in world space while
 *     the user rotates their head.
 *   - Head orientation comes from OrientationFilter as a [w, x, y, z] quaternion.
 *   - recenter() records the current orientation as "forward"; all subsequent view
 *     matrices are computed relative to that reference.
 *
 * Virtual screen geometry (world space):
 *   Quad corners: (-4, -2.25, -3) … (4, 2.25, -3)  →  16:9 at z = -3 m
 *   Horizontal angular size ≈ 2 * atan(4/3) ≈ 106°  (matches a "cinema" FOV feel)
 */
class XRRenderer : android.opengl.GLSurfaceView.Renderer, VideoSink {

    // ── Public state ──────────────────────────────────────────────────────────

    /** The SurfaceTexture WebRTC frames are decoded onto.  Valid after onSurfaceCreated(). */
    @Volatile var surfaceTexture: SurfaceTexture? = null
        private set

    // ── Head-pose state ───────────────────────────────────────────────────────

    // Both guarded by poseLock; written from arbitrary IMU thread, read from GL thread.
    private val poseLock = Any()
    private var currentQuat = floatArrayOf(1f, 0f, 0f, 0f)  // w, x, y, z
    private var centerQuat  = floatArrayOf(1f, 0f, 0f, 0f)

    // ── GL resource handles ───────────────────────────────────────────────────

    private var programHandle   = 0
    private var oesTextureId    = 0
    private var quadVbo         = 0
    private var aPositionLoc    = 0
    private var aTexCoordLoc    = 0
    private var uMvpMatrixLoc   = 0
    private var uTexMatrixLoc   = 0
    private var uTextureLoc     = 0

    // Scratch matrices — only ever accessed on the GL thread
    private val projectionMatrix  = FloatArray(16)
    private val viewMatrix        = FloatArray(16)
    private val modelMatrix       = FloatArray(16)
    private val mvpMatrix         = FloatArray(16)
    private val tmpMatrix         = FloatArray(16)
    private val texMatrix         = FloatArray(16)

    // SurfaceTexture update flag — set from any thread, consumed on GL thread
    @Volatile private var frameAvailable = false
    private val frameLock = Any()

    // ── VideoSink implementation ──────────────────────────────────────────────

    /**
     * Called by WebRTC when a decoded frame is ready.
     * We don't need to do anything here — EglRenderer pushes frames directly
     * onto our SurfaceTexture.  This sink implementation exists only so callers
     * can add this renderer as a VideoTrack sink when NOT using EglRenderer.
     */
    override fun onFrame(frame: VideoFrame) {
        // Intentionally empty: frame delivery is handled via EglRenderer → SurfaceTexture.
        // If you attach this directly as a VideoSink (without EglRenderer), frames will
        // be silently dropped — that is the intended behaviour for this renderer.
        frame.retain()
        frame.release()
    }

    // ── Head-pose API ─────────────────────────────────────────────────────────

    /** Update the current head orientation.  Thread-safe; may be called from any thread. */
    fun updateHeadPose(quaternion: FloatArray) {
        require(quaternion.size == 4) { "Quaternion must be [w, x, y, z]" }
        synchronized(poseLock) {
            currentQuat[0] = quaternion[0]
            currentQuat[1] = quaternion[1]
            currentQuat[2] = quaternion[2]
            currentQuat[3] = quaternion[3]
        }
    }

    /**
     * Record the current orientation as the "forward" reference direction.
     * After this call, the virtual screen will appear dead-ahead.
     */
    fun recenter() {
        synchronized(poseLock) {
            centerQuat[0] = currentQuat[0]
            centerQuat[1] = currentQuat[1]
            centerQuat[2] = currentQuat[2]
            centerQuat[3] = currentQuat[3]
        }
    }

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        oesTextureId = createOesTexture()
        programHandle = buildShaderProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC)
        quadVbo = buildQuadVbo()

        // Cache attribute / uniform locations
        aPositionLoc  = GLES20.glGetAttribLocation(programHandle,  "aPosition")
        aTexCoordLoc  = GLES20.glGetAttribLocation(programHandle,  "aTexCoord")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uMvpMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix")
        uTextureLoc   = GLES20.glGetUniformLocation(programHandle, "uTexture")

        checkGlError("onSurfaceCreated — attribute/uniform lookup")

        // Create SurfaceTexture on the GL thread (it must be tied to the current GL context)
        val st = SurfaceTexture(oesTextureId)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) { frameAvailable = true }
        }
        surfaceTexture = st

        // Identity texture transform until SurfaceTexture provides one
        Matrix.setIdentityM(texMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        // Perspective projection: 90° vertical FOV, matching the RayNeo glasses' display
        val aspect = width.toFloat() / height.toFloat()
        perspectiveM(projectionMatrix, 0, 90f, aspect, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Update SurfaceTexture with the latest decoded video frame (if any)
        synchronized(frameLock) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Snapshot pose under lock so we hold it consistently for this frame
        val cq: FloatArray
        val rq: FloatArray
        synchronized(poseLock) {
            cq = centerQuat.copyOf()
            rq = currentQuat.copyOf()
        }

        // relative = inverse(center) * current
        val relQuat = quatMultiply(quatConjugate(cq), rq)

        // Build view matrix from relative quaternion (3DOF: rotation only, no translation)
        quatToRotationMatrix(relQuat, viewMatrix)

        // Model matrix — quad is already positioned in world space, identity is fine
        Matrix.setIdentityM(modelMatrix, 0)

        // MVP = Projection * View * Model
        Matrix.multiplyMM(tmpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tmpMatrix, 0)

        drawQuad()
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private fun drawQuad() {
        GLES20.glUseProgram(programHandle)

        // Bind OES texture to unit 0 and tell the sampler uniform about it
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        // Upload matrices
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        // Bind VBO and set up vertex attribute pointers
        // Buffer layout: [x, y, z, u, v]  — 5 floats per vertex, 4 vertices
        val stride = 5 * BYTES_PER_FLOAT
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(
            aPositionLoc, 3, GLES20.GL_FLOAT, false, stride, 0
        )

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc, 2, GLES20.GL_FLOAT, false, stride, 3 * BYTES_PER_FLOAT
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)

        checkGlError("drawQuad")
    }

    // ── GL resource creation ──────────────────────────────────────────────────

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,     GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,     GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
        checkGlError("createOesTexture")
        return id
    }

    /**
     * Cinema screen quad in world space.
     *
     * Corners (x, y, z):  (-4, 2.25, -3)  top-left
     *                      ( 4, 2.25, -3)  top-right
     *                      (-4,-2.25, -3)  bottom-left
     *                      ( 4,-2.25, -3)  bottom-right
     *
     * UV: (0,0) top-left → (1,1) bottom-right, laid out as TRIANGLE_STRIP.
     *
     * Buffer layout per vertex: [x, y, z, u, v]
     */
    private fun buildQuadVbo(): Int {
        // TRIANGLE_STRIP order: TL, TR, BL, BR
        val vertices = floatArrayOf(
            // x      y      z     u     v
            -4f,  2.25f, -3f,  0f,  0f,   // top-left
             4f,  2.25f, -3f,  1f,  0f,   // top-right
            -4f, -2.25f, -3f,  0f,  1f,   // bottom-left
             4f, -2.25f, -3f,  1f,  1f,   // bottom-right
        )

        val buf: FloatBuffer = ByteBuffer
            .allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices); position(0) }

        val vboIds = IntArray(1)
        GLES20.glGenBuffers(1, vboIds, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertices.size * BYTES_PER_FLOAT,
            buf,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        checkGlError("buildQuadVbo")
        return vboIds[0]
    }

    private fun buildShaderProgram(vertSrc: String, fragSrc: String): Int {
        val vertShader = compileShader(GLES20.GL_VERTEX_SHADER,   vertSrc)
        val fragShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)

        val program = GLES20.glCreateProgram()
        check(program != 0) { "glCreateProgram failed" }

        GLES20.glAttachShader(program, vertShader)
        GLES20.glAttachShader(program, fragShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == GLES20.GL_FALSE) {
            val infoLog = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertShader)
            GLES20.glDeleteShader(fragShader)
            error("Program link failed: $infoLog")
        }

        // Shaders are now linked into the program; individual objects can be freed
        GLES20.glDeleteShader(vertShader)
        GLES20.glDeleteShader(fragShader)

        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed for type $type" }

        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == GLES20.GL_FALSE) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Shader compile failed (type=$type): $infoLog")
        }
        return shader
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    /** Returns the conjugate (= inverse for a unit quaternion) of [w, x, y, z]. */
    private fun quatConjugate(q: FloatArray): FloatArray =
        floatArrayOf(q[0], -q[1], -q[2], -q[3])

    /** Hamilton product of two quaternions, both in [w, x, y, z] format. */
    private fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val aw = a[0]; val ax = a[1]; val ay = a[2]; val az = a[3]
        val bw = b[0]; val bx = b[1]; val by = b[2]; val bz = b[3]
        return floatArrayOf(
            aw*bw - ax*bx - ay*by - az*bz,
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw
        )
    }

    /**
     * Convert a unit quaternion [w, x, y, z] into a column-major 4x4 rotation matrix
     * suitable for use as an OpenGL view matrix.
     *
     * The resulting matrix rotates world vectors into camera space (i.e. it is the
     * transpose of the body-to-world rotation matrix, which for unit quaternions equals
     * the conjugate rotation).
     */
    private fun quatToRotationMatrix(q: FloatArray, m: FloatArray) {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]

        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        // Column-major storage for OpenGL
        //   col 0
        m[0]  = 1f - 2f*(yy + zz)
        m[1]  =      2f*(xy + wz)
        m[2]  =      2f*(xz - wy)
        m[3]  = 0f
        //   col 1
        m[4]  =      2f*(xy - wz)
        m[5]  = 1f - 2f*(xx + zz)
        m[6]  =      2f*(yz + wx)
        m[7]  = 0f
        //   col 2
        m[8]  =      2f*(xz + wy)
        m[9]  =      2f*(yz - wx)
        m[10] = 1f - 2f*(xx + yy)
        m[11] = 0f
        //   col 3 (translation column — 3DOF has none)
        m[12] = 0f
        m[13] = 0f
        m[14] = 0f
        m[15] = 1f
    }

    /**
     * Build a perspective projection matrix into [m] starting at [offset].
     * Equivalent to gluPerspective.
     *
     * @param fovYDeg  Vertical field of view in degrees.
     * @param aspect   Viewport width / height.
     * @param near     Near clip plane distance (positive).
     * @param far      Far clip plane distance (positive).
     */
    private fun perspectiveM(
        m: FloatArray, offset: Int,
        fovYDeg: Float, aspect: Float,
        near: Float, far: Float
    ) {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovYDeg.toDouble() / 2.0)).toFloat()
        m.fill(0f, offset, offset + 16)
        m[offset + 0]  = f / aspect
        m[offset + 5]  = f
        m[offset + 10] = (far + near) / (near - far)
        m[offset + 11] = -1f
        m[offset + 14] = (2f * far * near) / (near - far)
    }

    // ── Error checking ────────────────────────────────────────────────────────

    private fun checkGlError(op: String) {
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError 0x${Integer.toHexString(error)}")
            error = GLES20.glGetError()
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val BYTES_PER_FLOAT = 4

        // GL_TEXTURE_EXTERNAL_OES is not exposed in GLES20 constants
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65

        private val VERTEX_SHADER_SRC = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord   = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
    }
}

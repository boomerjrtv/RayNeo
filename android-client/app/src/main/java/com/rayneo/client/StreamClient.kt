package com.rayneo.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StreamClient"

/**
 * Connects to the RayNeo server, handles WebRTC signaling over WebSocket,
 * and exposes the incoming video track for display.
 *
 * Flow:
 *   1. WS connect → receive StreamConfig + clientId
 *   2. Create RTCPeerConnection
 *   3. Create SDP offer → send to server
 *   4. Receive SDP answer → set remote description
 *   5. Exchange ICE candidates
 *   6. onVideoTrack fires → caller attaches to SurfaceViewRenderer
 *   7. IMU poses are sent continuously as JSON over the WS data channel
 */
class StreamClient(
    private val context: Context,
    private val serverHost: String,
    private val serverPort: Int,
    private val onStatus: (String) -> Unit,
    private val onVideoTrack: (VideoTrack) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connected = AtomicBoolean(false)

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null
    private var clientId: String? = null

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    fun start() {
        scope.launch { connect() }
    }

    fun stop() {
        connected.set(false)
        peerConnection?.close()
        webSocket?.close(1000, "stopping")
        scope.cancel()
        peerConnectionFactory.dispose()
    }

    /** Called from IMU thread — sends latest head pose to server for reprojection. */
    fun sendPose(quaternion: FloatArray, timestampUs: Long = System.nanoTime() / 1000) {
        if (!connected.get()) return
        val json = JSONObject().apply {
            put("type", "head_pose")
            put("orientation", org.json.JSONArray(quaternion.map { it.toDouble() }))
            put("position", org.json.JSONArray(listOf(0.0, 0.0, 0.0))) // 3DOF — no positional tracking
            put("timestamp_us", timestampUs)
        }
        webSocket?.send(json.toString())
    }

    private suspend fun connect() {
        withContext(Dispatchers.Main) { onStatus("Initializing WebRTC...") }
        initPeerConnectionFactory()

        val url = "ws://$serverHost:$serverPort/signal"
        withContext(Dispatchers.Main) { onStatus("Connecting to $url") }

        val request = Request.Builder().url(url).build()
        httpClient.newWebSocket(request, wsListener)
    }

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            // STUN for LAN — no TURN needed if phone and server are on same network
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("type", "ice_candidate")
                    put("candidate", candidate.sdp)
                    put("sdp_mid", candidate.sdpMid)
                    put("sdp_mline_index", candidate.sdpMLineIndex)
                }
                webSocket?.send(json.toString())
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                if (track is VideoTrack) {
                    Log.i(TAG, "video track received")
                    scope.launch(Dispatchers.Main) {
                        onStatus("Streaming")
                        onVideoTrack(track)
                    }
                }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "peer connection state: $state")
                connected.set(state == PeerConnection.PeerConnectionState.CONNECTED)
                scope.launch(Dispatchers.Main) { onStatus(state.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: error("Failed to create PeerConnection")

        // Add a receive-only video transceiver — we only receive from server, never send video
        peerConnection!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("sdp", sdp.description)
                        }
                        webSocket?.send(json.toString())
                        Log.i(TAG, "SDP offer sent")
                    }
                    override fun onSetFailure(e: String) = Log.e(TAG, "setLocalDescription failed: $e")
                    override fun onCreateSuccess(s: SessionDescription) {}
                    override fun onCreateFailure(e: String) {}
                }, sdp)
            }
            override fun onCreateFailure(e: String) = Log.e(TAG, "createOffer failed: $e")
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String) {}
        }, constraints)
    }

    private fun handleAnswer(sdpStr: String) {
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() = Log.i(TAG, "remote description set")
            override fun onSetFailure(e: String) = Log.e(TAG, "setRemoteDescription failed: $e")
            override fun onCreateSuccess(s: SessionDescription) {}
            override fun onCreateFailure(e: String) {}
        }, sdp)
    }

    private fun handleIceCandidate(json: JSONObject) {
        val candidate = IceCandidate(
            json.optString("sdp_mid"),
            json.optInt("sdp_mline_index"),
            json.getString("candidate")
        )
        peerConnection?.addIceCandidate(candidate)
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")
            webSocket = ws
            scope.launch(Dispatchers.Main) { onStatus("Signaling connected") }
            // Peer connection + offer are created after server sends StreamConfig
        }

        override fun onMessage(ws: WebSocket, text: String) {
            runCatching {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "connected" -> {
                        clientId = json.optString("client_id")
                        Log.i(TAG, "client_id=$clientId")
                    }
                    "stream_config" -> {
                        Log.i(TAG, "stream config received, creating offer")
                        scope.launch(Dispatchers.Main) { onStatus("Creating WebRTC offer...") }
                        createPeerConnection()
                        createAndSendOffer()
                    }
                    "answer" -> handleAnswer(json.getString("sdp"))
                    "ice_candidate" -> handleIceCandidate(json)
                    "error" -> Log.e(TAG, "server error: ${json.optString("message")}")
                }
            }.onFailure { Log.e(TAG, "ws message parse error", it) }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            scope.launch(Dispatchers.Main) { onStatus("Connection failed — retrying...") }
            // Retry after a delay
            scope.launch {
                delay(3000)
                connect()
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            connected.set(false)
            Log.i(TAG, "WebSocket closed: $reason")
        }
    }
}

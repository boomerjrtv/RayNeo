package com.rayneo.client

// Ported unchanged from informalTechCode/RayNeo-Air-Series-3DoF-Scaffold
// Credit: https://github.com/informalTechCode/RayNeo-Air-Series-3DoF-Scaffold

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RayNeoSession(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onOrientation: (FloatArray) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private var receiverRegistered = false
    private var ioThread: Thread? = null
    private var activeDeviceId: Int = -1
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var assembler = RayNeoPacketAssembler()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        startStreaming(device)
                    else postStatus("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (!started.get()) return
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (isRayNeo(device) && !running.get()) requestPermissionOrStart(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (device.deviceId == activeDeviceId) { postStatus("RayNeo disconnected"); running.set(false) }
                }
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerReceiver()
        val device = findRayNeoDevice()
        if (device == null) { postStatus("RayNeo not found (VID 0x1bbb / PID 0xaf50)"); return }
        requestPermissionOrStart(device)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        running.set(false)
        ioThread?.join(600); ioThread = null
        closeConnection()
        unregisterReceiver()
    }

    private fun requestPermissionOrStart(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            postStatus("Requesting USB permission...")
            usbManager.requestPermission(device, buildPermissionIntent()); return
        }
        startStreaming(device)
    }

    private fun startStreaming(device: UsbDevice) {
        if (!started.get() || running.get()) return
        val conn = usbManager.openDevice(device) ?: run { postStatus("Failed to open USB device"); return }
        val sel = RayNeoProtocol.selectEndpoints(device) ?: run { conn.close(); postStatus("No IN/OUT endpoints"); return }
        if (!conn.claimInterface(sel.usbInterface, true)) { conn.close(); postStatus("Failed to claim interface"); return }
        connection = conn; usbInterface = sel.usbInterface
        inEndpoint = sel.inEndpoint; outEndpoint = sel.outEndpoint
        activeDeviceId = device.deviceId; assembler = RayNeoPacketAssembler()
        running.set(true)
        ioThread = Thread(::ioLoop, "RayNeoUsbThread").also { it.start() }
    }

    private fun ioLoop() {
        try {
            postStatus("Initializing protocol...")
            val info = initializeProtocol() ?: run { postStatus("Initialization failed"); return }
            val imuRotX = if (info.boardId == RayNeoProtocol.BOARD_AIR_4_PRO) -20f else 0f
            postStatus(String.format(Locale.US, "Streaming board=0x%02X rotX=%+.1f°", info.boardId, imuRotX))
            val filter = OrientationFilter(imuRotX)
            val firstAt = SystemClock.elapsedRealtime() + SENSOR_WARMUP_MS
            if (!initializeSensorPose(filter, firstAt)) { postStatus("No initial IMU sample"); return }
            while (running.get()) {
                val pkt = readPacket(250) ?: continue
                if (pkt is RayNeoPacket.Sensor) postOrientation(filter.update(pkt.sample))
            }
        } finally {
            bestEffortSend(RayNeoProtocol.CMD_CLOSE_IMU)
            closeConnection(); running.set(false); ioThread = null
        }
    }

    private fun initializeSensorPose(filter: OrientationFilter, firstAt: Long): Boolean {
        postStatus("Waiting for IMU warmup...")
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val pkt = readPacket(200) ?: continue
            if (pkt is RayNeoPacket.Sensor) {
                val q = filter.update(pkt.sample)
                if (SystemClock.elapsedRealtime() >= firstAt) { postOrientation(q); return true }
            }
        }
        return false
    }

    private fun initializeProtocol(): RayNeoDeviceInfo? {
        if (!sendCommand(RayNeoProtocol.CMD_ACQUIRE_DEVICE_INFO)) return null
        val info = waitForDeviceInfo(2500) ?: return null
        if (!sendCommand(RayNeoProtocol.CMD_OPEN_IMU)) return null
        if (!waitForAck(RayNeoProtocol.CMD_OPEN_IMU, 1500)) return null
        return info
    }

    private fun waitForDeviceInfo(timeoutMs: Int): RayNeoDeviceInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val pkt = readPacket(200) ?: continue
            if (pkt is RayNeoPacket.Response && pkt.cmd == RayNeoProtocol.CMD_ACQUIRE_DEVICE_INFO)
                return RayNeoProtocol.parseDeviceInfo(pkt.raw)
        }
        return null
    }

    private fun waitForAck(expectedCmd: Int, timeoutMs: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val pkt = readPacket(200) ?: continue
            if (pkt is RayNeoPacket.Response && pkt.cmd == expectedCmd) return true
        }
        return false
    }

    private fun readPacket(timeoutMs: Int): RayNeoPacket? {
        val conn = connection ?: return null
        val epIn = inEndpoint ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val buf = ByteArray(maxOf(epIn.maxPacketSize, 64))
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            assembler.nextPacket()?.let { return RayNeoProtocol.classifyPacket(it) }
            val n = conn.bulkTransfer(epIn, buf, buf.size, 50)
            if (n > 0) assembler.append(buf, n) else SystemClock.sleep(5)
        }
        return null
    }

    private fun sendCommand(cmd: Int, arg: Int = 0): Boolean {
        val conn = connection ?: return false
        val epOut = outEndpoint ?: return false
        val pkt = RayNeoProtocol.buildCommandPacket(cmd, arg, epOut.maxPacketSize)
        return conn.bulkTransfer(epOut, pkt, pkt.size, 1000) == pkt.size
    }

    private fun bestEffortSend(cmd: Int, arg: Int = 0) {
        if (connection != null && outEndpoint != null) sendCommand(cmd, arg)
    }

    private fun closeConnection() {
        connection?.let { conn -> usbInterface?.let { runCatching { conn.releaseInterface(it) } }; runCatching { conn.close() } }
        connection = null; usbInterface = null; inEndpoint = null; outEndpoint = null
        activeDeviceId = -1; assembler = RayNeoPacketAssembler()
    }

    private fun findRayNeoDevice() = usbManager.deviceList.values.firstOrNull { isRayNeo(it) }
    private fun isRayNeo(d: UsbDevice) = d.vendorId == RayNeoProtocol.VENDOR_ID && d.productId == RayNeoProtocol.PRODUCT_ID
    private fun buildPermissionIntent() = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply { addAction(ACTION_USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun postStatus(msg: String) = mainHandler.post { onStatus(msg) }
    private fun postOrientation(q: FloatArray) = mainHandler.post { onOrientation(q) }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.rayneo.client.USB_PERMISSION"
        private const val SENSOR_WARMUP_MS = 2000L
    }
}

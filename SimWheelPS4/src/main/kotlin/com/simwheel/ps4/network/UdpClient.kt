package com.simwheel.ps4.network

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class UdpClient(
    private val phoneName: String,
    private val onVibration: (intensity: Float, duration: Int) -> Unit,
    private val onConnected: (pcName: String) -> Unit,
    private val onDisconnected: () -> Unit
) {

    private var socket: DatagramSocket? = null
    private var pcAddress: InetAddress? = null
    private val port = 4567
    private val running = AtomicBoolean(false)

    var isConnected = false
        private set

    // Last sent state to avoid redundant packets
    private var lastSteering = Float.NaN
    private var lastThrottle = Float.NaN
    private var lastBrake = Float.NaN
    private val lastButtons = mutableMapOf<Int, Boolean>()

    fun connect(ip: String) {
        disconnect()
        Thread {
            try {
                socket = DatagramSocket(port).also { it.soTimeout = 3000 }
                pcAddress = InetAddress.getByName(ip)
                running.set(true)
                isConnected = true
                sendDiscovery()
                listenLoop()
            } catch (e: Exception) {
                isConnected = false
                onDisconnected()
            }
        }.apply { isDaemon = true }.start()
    }

    fun discover() {
        disconnect()
        Thread {
            try {
                val sock = DatagramSocket(port)
                sock.broadcast = true
                sock.soTimeout = 5000
                running.set(true)
                socket = sock

                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val discovery = JSONObject().apply {
                    put("type", "discover")
                    put("phoneName", phoneName)
                }
                val bytes = discovery.toString().toByteArray()
                sock.send(DatagramPacket(bytes, bytes.size, broadcastAddr, port))

                // Wait for reply
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val reply = JSONObject(String(pkt.data, 0, pkt.length))
                if (reply.optString("type") == "discover_reply") {
                    pcAddress = pkt.address
                    isConnected = true
                    val pcName = reply.optString("name", pkt.address.hostAddress)
                    onConnected(pcName)
                    listenLoop()
                }
            } catch (e: Exception) {
                isConnected = false
                onDisconnected()
            }
        }.apply { isDaemon = true }.start()
    }

    fun sendState(
        steering: Float,
        throttle: Float,
        brake: Float,
        buttonChanges: Map<Int, Boolean>
    ) {
        val addr = pcAddress ?: return
        val sock = socket ?: return

        val json = JSONObject()
        var hasData = false

        if (steering != lastSteering) {
            json.put("steering", steering.toDouble())
            json.put("throttle", throttle.toDouble())
            json.put("brake", brake.toDouble())
            lastSteering = steering
            lastThrottle = throttle
            lastBrake = brake
            hasData = true
        } else if (throttle != lastThrottle || brake != lastBrake) {
            json.put("steering", steering.toDouble())
            json.put("throttle", throttle.toDouble())
            json.put("brake", brake.toDouble())
            lastThrottle = throttle
            lastBrake = brake
            hasData = true
        }

        buttonChanges.forEach { (pcCode, pressed) ->
            if (lastButtons[pcCode] != pressed) {
                json.put(pcCode.toString(), pressed)
                lastButtons[pcCode] = pressed
                hasData = true
            }
        }

        if (!hasData) return

        json.put("phoneName", phoneName)

        try {
            val bytes = json.toString().toByteArray()
            sock.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (_: Exception) {}
    }

    fun disconnect() {
        running.set(false)
        isConnected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        pcAddress = null
        lastSteering = Float.NaN
        lastThrottle = Float.NaN
        lastBrake = Float.NaN
        lastButtons.clear()
    }

    private fun sendDiscovery() {
        val addr = pcAddress ?: return
        val sock = socket ?: return
        val discovery = JSONObject().apply {
            put("type", "discover")
            put("phoneName", phoneName)
        }
        val bytes = discovery.toString().toByteArray()
        sock.send(DatagramPacket(bytes, bytes.size, addr, port))
    }

    private fun listenLoop() {
        val sock = socket ?: return
        val buf = ByteArray(4096)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val json = JSONObject(String(pkt.data, 0, pkt.length))
                when (json.optString("type")) {
                    "discover_reply" -> {
                        if (!isConnected) {
                            isConnected = true
                            onConnected(json.optString("name", pkt.address.hostAddress))
                        }
                    }
                    "vibration" -> {
                        val intensity = json.optDouble("intensity", 0.8).toFloat()
                        val duration = json.optInt("duration", 200)
                        onVibration(intensity, duration)
                    }
                }
            } catch (_: Exception) { /* timeout or closed */ }
        }
    }
}

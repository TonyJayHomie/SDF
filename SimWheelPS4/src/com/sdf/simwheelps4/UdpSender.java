package com.sdf.simwheelps4;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP transport to SimWheel PC Receiver v3.0.
 * Wire format matches Receiver.cpp:
 *   { "steering": <deg>, "throttle": 0..1, "brake": 0..1, "dx": 0, "dy": 0, "<btnId>": bool, ... }
 *
 * Sends at a fixed cadence (default 100 Hz) when "streaming" is on. Performs
 * UDP discovery by broadcasting a JSON {"type":"discover","phoneName":...} packet
 * and waiting for {"type":"discover_reply"} responses.
 */
public class UdpSender {
    private static final String TAG = "UdpSender";

    public interface Listener {
        void onDiscovered(String pcIp, String pcName, String connection);
        void onError(String message);
        void onStats(int packetsSent, int packetsPerSec);
    }

    private final HandlerThread thread;
    private final Handler handler;
    private final Listener listener;
    private final String phoneName;

    private DatagramSocket socket;
    private InetAddress targetAddr;
    private int targetPort = 4567;
    private final AtomicBoolean streaming = new AtomicBoolean(false);

    // Live state — read by send loop, mutated from any thread
    private volatile float steeringDeg = 0f;
    private volatile float throttle    = 0f;
    private volatile float brake       = 0f;
    private volatile float clutch      = 0f;
    private volatile boolean sendClutch= false;
    private volatile float mouseDx     = 0f;
    private volatile float mouseDy     = 0f;
    private volatile boolean mouseMode = false;
    private final ConcurrentHashMap<Integer, Boolean> buttons = new ConcurrentHashMap<Integer, Boolean>();

    private final AtomicLong totalPackets = new AtomicLong(0);
    private long statsWindowStart = 0;
    private int  statsWindowCount = 0;
    private int  lastRate = 0;

    private final Runnable sendTick = new Runnable() {
        @Override public void run() {
            sendOnce();
            if (streaming.get()) handler.postDelayed(this, 10); // ~100 Hz
        }
    };

    public UdpSender(String phoneName, Listener listener) {
        this.phoneName = phoneName == null ? "Phone" : phoneName;
        this.listener  = listener;
        this.thread = new HandlerThread("UdpSender");
        this.thread.start();
        this.handler = new Handler(this.thread.getLooper());
    }

    public void shutdown() {
        streaming.set(false);
        handler.post(new Runnable() {
            @Override public void run() {
                if (socket != null) { socket.close(); socket = null; }
            }
        });
        thread.quitSafely();
    }

    /** Open the socket (idempotent). */
    private boolean ensureSocket() {
        if (socket != null && !socket.isClosed()) return true;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(800);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "socket open failed", e);
            if (listener != null) listener.onError("Socket open failed: " + e.getMessage());
            return false;
        }
    }

    public void setTarget(final String ip, final int port) {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    targetAddr = InetAddress.getByName(ip);
                    targetPort = port;
                } catch (Exception e) {
                    if (listener != null) listener.onError("Bad IP: " + e.getMessage());
                }
            }
        });
    }

    public void startStreaming() {
        if (streaming.compareAndSet(false, true)) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (!ensureSocket()) { streaming.set(false); return; }
                    statsWindowStart = SystemClock.elapsedRealtime();
                    statsWindowCount = 0;
                    handler.post(sendTick);
                }
            });
        }
    }

    public void stopStreaming() { streaming.set(false); }

    public boolean isStreaming() { return streaming.get(); }

    /** Update the live wheel/pedal values (call as often as you like). */
    public void updateAxes(float steeringDeg, float throttle, float brake) {
        this.steeringDeg = steeringDeg;
        this.throttle    = throttle;
        this.brake       = brake;
    }

    public void updateClutch(boolean enabled, float value) {
        this.sendClutch = enabled;
        this.clutch     = value;
    }

    public void updateMouse(boolean enabled, float dx, float dy) {
        this.mouseMode = enabled;
        this.mouseDx   = dx;
        this.mouseDy   = dy;
    }

    public void setButton(int receiverCode, boolean pressed) {
        if (receiverCode <= 0) return;
        buttons.put(receiverCode, pressed);
    }

    /** Broadcast a discovery probe and listen briefly for a reply. */
    public void discover(final String broadcastIp) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (!ensureSocket()) return;
                try {
                    JSONObject j = new JSONObject();
                    j.put("type", "discover");
                    j.put("phoneName", phoneName);
                    byte[] data = j.toString().getBytes("UTF-8");

                    InetAddress addr = InetAddress.getByName(broadcastIp == null ? "255.255.255.255" : broadcastIp);
                    DatagramPacket pkt = new DatagramPacket(data, data.length, addr, targetPort);
                    socket.send(pkt);

                    byte[] buf = new byte[1024];
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    long deadline = SystemClock.elapsedRealtime() + 1500;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        try {
                            socket.receive(reply);
                            String s = new String(reply.getData(), 0, reply.getLength(), "UTF-8");
                            JSONObject r = new JSONObject(s);
                            if ("discover_reply".equals(r.optString("type"))) {
                                String name = r.optString("name", "PC");
                                String conn = r.optString("connection", "wifi");
                                String ip   = reply.getAddress().getHostAddress();
                                if (listener != null) listener.onDiscovered(ip, name, conn);
                                return;
                            }
                        } catch (java.net.SocketTimeoutException te) {
                            break;
                        }
                    }
                    if (listener != null) listener.onError("No PC replied to discover");
                } catch (Exception e) {
                    if (listener != null) listener.onError("Discover failed: " + e.getMessage());
                }
            }
        });
    }

    private void sendOnce() {
        if (socket == null || socket.isClosed() || targetAddr == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("steering", steeringDeg);
            j.put("throttle", throttle);
            j.put("brake", brake);
            // Receiver requires the trailing-space key "clutch " (bug-for-bug match
            // with the original C++ source). Only sent when the user enables the
            // clutch slider so we don't overwrite vJoy RX otherwise.
            if (sendClutch) j.put("clutch ", clutch);
            // Receiver expects EITHER zaxis OR (dx, dy). We always send dx/dy
            // (0 when not in mouse mode) so j.at("dx") never throws.
            j.put("dx", mouseMode ? mouseDx : 0);
            j.put("dy", mouseMode ? mouseDy : 0);
            j.put("phoneName", phoneName);

            Iterator<Map.Entry<Integer, Boolean>> it = buttons.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Boolean> e = it.next();
                j.put(Integer.toString(e.getKey()), e.getValue().booleanValue());
            }

            byte[] data = j.toString().getBytes("UTF-8");
            DatagramPacket pkt = new DatagramPacket(data, data.length, targetAddr, targetPort);
            socket.send(pkt);
            long sent = totalPackets.incrementAndGet();
            statsWindowCount++;
            long now = SystemClock.elapsedRealtime();
            if (now - statsWindowStart >= 1000) {
                lastRate = (int)(statsWindowCount * 1000L / Math.max(1, now - statsWindowStart));
                statsWindowStart = now;
                statsWindowCount = 0;
                if (listener != null) listener.onStats((int)sent, lastRate);
            }
        } catch (JSONException e) {
            // unreachable: keys are well-formed
        } catch (Exception e) {
            Log.w(TAG, "send error", e);
        }
    }
}

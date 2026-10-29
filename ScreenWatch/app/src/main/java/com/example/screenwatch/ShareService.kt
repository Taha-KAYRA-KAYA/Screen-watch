package com.example.screenwatch

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

class ShareService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "sw.start"
        const val ACTION_CANCEL = "sw.cancel"
        @Volatile var pairingCode: String? = null
        @Volatile private var failedTries = 0

        fun newCode(): String {
            failedTries = 0
            val c = (100000 + SecureRandom().nextInt(900000)).toString()
            pairingCode = c
            return c
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var pending: Socket? = null
    @Volatile private var client: Socket? = null
    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_CANCEL -> { runCatching { pending?.close() }; pending = null }
            else -> startListening()
        }
        return START_NOT_STICKY
    }

    // ---------- foreground / bildirim ----------
    private fun listenType() =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

    private fun channel(): String {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("sw", "ScreenWatch", NotificationManager.IMPORTANCE_HIGH))
        return "sw"
    }

    private fun fg(text: String, type: Int) {
        val n = Notification.Builder(this, channel())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ScreenWatch")
            .setContentText(text)
            .setOngoing(true)
            .build()
        startForeground(1, n, type)
    }

    // ---------- dinleme ----------
    private fun startListening() {
        if (running) return
        running = true
        fg("Bağlantı bekleniyor", listenType())
        thread {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.PORT)) }
                server = ss
                registerNsd(ss.localPort)
                while (running) {
                    val s = ss.accept()
                    thread { handleClient(s) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "ScreenWatch"
            serviceType = Proto.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo?) {}
            override fun onRegistrationFailed(i: NsdServiceInfo?, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo?, e: Int) {}
        }
        regListener = l
        nsd = getSystemService(NsdManager::class.java)
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun handleClient(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15000
            val inp = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())
            when (inp.readByte().toInt()) {
                Proto.MODE_PAIR -> {
                    val code = inp.readUTF()
                    val expected = pairingCode
                    if (expected != null && code == expected) {
                        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
                            .joinToString("") { "%02x".format(it) }
                        Proto.prefs(this).edit().putString("secret", secret).apply()
                        pairingCode = null
                        out.writeBoolean(true); out.writeUTF(secret); out.flush()
                    } else {
                        if (++failedTries >= 5) pairingCode = null
                        out.writeBoolean(false); out.flush()
                    }
                    s.close()
                }
                Proto.MODE_AUTH -> {
                    val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    out.write(nonce); out.flush()
                    val resp = ByteArray(32); inp.readFully(resp)
                    val secret = Proto.prefs(this).getString("secret", null)
                    if (secret != null && MessageDigest.isEqual(Proto.hmac(secret, nonce), resp)) {
                        out.writeBoolean(true); out.flush()
                        s.soTimeout = 0
                        requestCapture(s)
                    } else {
                        out.writeBoolean(false); out.flush()
                        s.close()
                    }
                }
                else -> s.close()
            }
        } catch (_: Exception) {
            runCatching { s.close() }
        }
    }

    // ---------- ekran yakalama izni (her seferinde tek dokunuş) ----------
    private fun requestCapture(s: Socket) {
        if (client != null) { runCatching { s.close() }; return }
        runCatching { pending?.close() }
        pending = s
        val ci = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 0, ci, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, channel())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Ekran paylaşım isteği")
            .setContentText("Başlatmak için dokunun")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
        runCatching { startActivity(ci) } // uygulama açıksa doğrudan açılır
        main.postDelayed({
            if (pending === s) { runCatching { s.close() }; pending = null }
        }, 60_000)
    }

    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        val s = pending ?: return
        pending = null
        getSystemService(NotificationManager::class.java).cancel(2)
        val data = intent.getParcelableExtra<Intent>("data") ?: return
        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)

        fg("Ekran paylaşılıyor", ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, main)
        projection = p
        client = s
        startEncoding(s, p)
    }

    private fun startEncoding(sock: Socket, p: MediaProjection) {
        val dm = resources.displayMetrics
        val scale = minOf(1f, 720f / minOf(dm.widthPixels, dm.heightPixels))
        val w = ((dm.widthPixels * scale).toInt() / 16) * 16
        val h = ((dm.heightPixels * scale).toInt() / 16) * 16

        val fmt = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000)
        }
        val enc = MediaCodec.createEncoderByType("video/avc")
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        display = p.createVirtualDisplay(
            "sw", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        thread {
            try {
                val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 65536))
                out.writeInt(w); out.writeInt(h); out.flush()
                val info = MediaCodec.BufferInfo()
                while (running && client === sock) {
                    val idx = enc.dequeueOutputBuffer(info, 100_000)
                    if (idx >= 0) {
                        if (info.size > 0) {
                            val buf = enc.getOutputBuffer(idx)!!
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.get(bytes)
                            out.writeInt(info.size); out.writeInt(info.flags); out.write(bytes)
                            out.flush()
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
            } catch (_: Exception) {
            } finally {
                stopCapture()
            }
        }
    }

    @Synchronized
    private fun stopCapture() {
        val p = projection; projection = null
        val e = encoder; encoder = null
        val d = display; display = null
        val c = client; client = null
        runCatching { d?.release() }
        runCatching { e?.stop(); e?.release() }
        runCatching { p?.stop() }
        runCatching { c?.close() }
        if (running && p != null) fg("Bağlantı bekleniyor", listenType())
    }

    override fun onDestroy() {
        running = false
        stopCapture()
        runCatching { server?.close() }
        runCatching { pending?.close() }
        runCatching { regListener?.let { nsd?.unregisterService(it) } }
        super.onDestroy()
    }
}

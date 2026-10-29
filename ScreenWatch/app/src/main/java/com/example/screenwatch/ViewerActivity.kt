package com.example.screenwatch

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ViewerActivity : Activity(), SurfaceHolder.Callback {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var sv: SurfaceView
    private lateinit var status: TextView
    @Volatile private var alive = true
    @Volatile private var socket: Socket? = null
    private var nsd: NsdManager? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private var started = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        sv = SurfaceView(this)
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        sv.holder.addCallback(this)
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        if (started) return
        started = true
        thread { loop(h.surface) }
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}

    override fun onStop() { super.onStop(); finish() }

    override fun onDestroy() {
        alive = false
        runCatching { socket?.close() }
        stopDiscovery()
        super.onDestroy()
    }

    private fun setStatus(t: String) = ui.post {
        status.text = t
        status.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun quit() { alive = false; ui.post { finish() } }

    // ---------- bağlantı döngüsü ----------
    private fun loop(surface: Surface) {
        while (alive) {
            try { session(surface) } catch (e: Exception) {
                if (alive) setStatus("Bağlantı koptu, yeniden deneniyor…")
            }
            runCatching { socket?.close() }
            if (alive) Thread.sleep(2000)
        }
    }

    private fun session(surface: Surface) {
        val prefs = Proto.prefs(this)
        setStatus("Paylaşan telefon aranıyor…")
        val host = findHost()
        if (host == null) {
            if (prefs.getString("host", null) == null) quit()
            else setStatus("Paylaşan telefon bulunamadı (kapalı olabilir)…")
            return
        }

        val s = Socket()
        s.connect(InetSocketAddress(host, Proto.PORT), 3000)
        s.tcpNoDelay = true
        socket = s
        val out = DataOutputStream(s.getOutputStream())
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))

        val secret = prefs.getString("secret", null)
        if (secret == null) {
            val code = ask("Paylaşan telefondaki 6 haneli kod", "123456", true) ?: run { quit(); return }
            out.writeByte(Proto.MODE_PAIR); out.writeUTF(code); out.flush()
            if (!inp.readBoolean()) { setStatus("Kod yanlış veya süresi dolmuş"); return }
            prefs.edit().putString("secret", inp.readUTF()).putString("host", host).apply()
            return // döngü yeniden bağlanıp doğrulama yapacak
        }

        out.writeByte(Proto.MODE_AUTH); out.flush()
        val nonce = ByteArray(16); inp.readFully(nonce)
        out.write(Proto.hmac(secret, nonce)); out.flush()
        if (!inp.readBoolean()) {
            prefs.edit().remove("secret").apply()
            setStatus("Eşleşme geçersiz, yeniden eşleştirin")
            return
        }
        prefs.edit().putString("host", host).apply()
        setStatus("Paylaşan telefonda bildirime dokunup izin verin…")

        val w = inp.readInt(); val h = inp.readInt()
        setStatus("")
        ui.post {
            val scale = minOf(root.width.toFloat() / w, root.height.toFloat() / h)
            sv.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
        }
        decode(inp, w, h, surface)
    }

    private fun decode(inp: DataInputStream, w: Int, h: Int, surface: Surface) {
        val dec = MediaCodec.createDecoderByType("video/avc")
        dec.configure(MediaFormat.createVideoFormat("video/avc", w, h), surface, null, 0)
        dec.start()
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        fun drain() {
            while (true) {
                val o = dec.dequeueOutputBuffer(info, 0)
                if (o >= 0) dec.releaseOutputBuffer(o, true) else break
            }
        }
        try {
            while (alive) {
                val size = inp.readInt(); val flags = inp.readInt()
                val data = ByteArray(size); inp.readFully(data)
                var i = -1
                while (alive && i < 0) { i = dec.dequeueInputBuffer(20_000); drain() }
                if (i >= 0) {
                    val b = dec.getInputBuffer(i)!!
                    b.clear(); b.put(data)
                    val f = flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    dec.queueInputBuffer(i, 0, size, pts, f)
                    pts += 33_000
                }
                drain()
            }
        } finally {
            runCatching { dec.stop(); dec.release() }
        }
    }

    // ---------- paylaşan telefonu bulma ----------
    private fun reachable(host: String) = try {
        Socket().use { it.connect(InetSocketAddress(host, Proto.PORT), 1500) }; true
    } catch (_: Exception) { false }

    private fun findHost(): String? {
        val saved = Proto.prefs(this).getString("host", null)
        if (saved != null && reachable(saved)) return saved

        val latch = CountDownLatch(1)
        var found: String? = null
        ui.post { startDiscovery { h -> found = h; latch.countDown() } }
        latch.await(10, TimeUnit.SECONDS)
        ui.post { stopDiscovery() }
        if (found != null) return found
        if (saved != null) return null
        return ask("Paylaşan telefonun IP adresi", "192.168.1.x", false)
    }

    private fun startDiscovery(onFound: (String) -> Unit) {
        val m = getSystemService(NsdManager::class.java)
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo?) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {}
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val a = r.host
                        if (a is Inet4Address) a.hostAddress?.let(onFound)
                    }
                })
            }
        }
        disc = l
        m.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun stopDiscovery() {
        runCatching { disc?.let { nsd?.stopServiceDiscovery(it) } }
        disc = null
    }

    private fun ask(title: String, hint: String, numeric: Boolean): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        ui.post {
            val et = EditText(this).apply {
                this.hint = hint
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(this).setTitle(title).setView(et).setCancelable(false)
                .setPositiveButton("Tamam") { _, _ -> result = et.text.toString().trim(); latch.countDown() }
                .setNegativeButton("İptal") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        return result?.ifEmpty { null }
    }
}

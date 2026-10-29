package com.example.screenwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val info = TextView(this).apply { textSize = 18f; setPadding(0, 32, 0, 32) }

        val share = Button(this).apply {
            text = "Bu telefonun ekranı izlensin"
            setOnClickListener {
                val code = ShareService.newCode()
                startForegroundService(Intent(this@MainActivity, ShareService::class.java))
                info.text = "Eşleştirme kodu: $code\nIP: ${Proto.localIp()}\n\n" +
                    "İlk eşleştirmede izleyen telefona bu kodu girin. Sonrasında gerekmez."
            }
        }
        val stop = Button(this).apply {
            text = "Paylaşımı kapat"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ShareService::class.java))
                info.text = "Paylaşım kapalı."
            }
        }
        val watch = Button(this).apply {
            text = "Diğer telefonu izle"
            setOnClickListener { startActivity(Intent(this@MainActivity, ViewerActivity::class.java)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(share); addView(stop); addView(watch); addView(info)
        })
    }
}

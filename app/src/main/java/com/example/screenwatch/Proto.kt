package com.example.screenwatch

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Proto {
    const val PORT = 8554
    const val SERVICE_TYPE = "_screenwatch._tcp."
    const val MODE_PAIR = 0
    const val MODE_AUTH = 1

    fun hmac(secret: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun prefs(c: Context) = c.getSharedPreferences("sw", Context.MODE_PRIVATE)

    fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: "?"
}

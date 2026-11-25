package com.padelgrabber.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /** IP local (wifi) de este móvil, o null si no hay red. */
    fun localIp(): String? {
        try {
            var fallback: String? = null
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifaces.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val name = ni.name.lowercase()
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) return ip
                        if (addr.isSiteLocalAddress && fallback == null) fallback = ip
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            return null
        }
    }

    fun qr(text: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = m.width
            val h = m.height
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                px[y * w + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }
}

package com.padelgrabber.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Lee el MJPEG de un móvil-cámara y mete sus fotogramas en el FrameHub. */
class MjpegClient(
    val id: String,
    @Volatile var name: String,
    @Volatile var url: String
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "pg-mjpeg-$id", isDaemon = true) {
            var backoff = 500L
            while (running) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 8000
                    conn.connect()
                    val input = BufferedInputStream(conn.inputStream, 64 * 1024)
                    backoff = 500L
                    readStream(input)
                } catch (e: Exception) {
                    if (running) Log.d("PG-Mjpeg", "Reconectando con $url: ${e.message}")
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
                if (!running) break
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(5000)
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
    }

    private fun readStream(input: InputStream) {
        while (running) {
            // Cabeceras de la parte
            var len = -1
            var gotHeader = false
            while (running) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) {
                    if (gotHeader) break else continue
                }
                gotHeader = true
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) len = lower.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            if (len <= 0 || len > 5_000_000) continue
            val data = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(data, off, len - off)
                if (r < 0) return
                off += r
            }
            FrameHub.push(id, name, data)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(96)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 4096) return ""
        }
        return String(buf.toByteArray())
    }
}

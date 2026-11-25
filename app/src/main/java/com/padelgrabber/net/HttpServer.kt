package com.padelgrabber.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String
) {
    fun q(name: String): String? = query[name]
}

class HttpResponse(private val socket: Socket) {
    val out: OutputStream = socket.getOutputStream()
    var streaming = false
        private set

    fun send(code: Int, contentType: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    fun text(code: Int, s: String, type: String = "text/plain; charset=utf-8") = send(code, type, s.toByteArray())
    fun json(s: String) = send(200, "application/json; charset=utf-8", s.toByteArray())
    fun html(s: String) = send(200, "text/html; charset=utf-8", s.toByteArray())

    /** Empieza una respuesta MJPEG (multipart/x-mixed-replace). */
    fun startMjpeg() {
        streaming = true
        socket.tcpNoDelay = true
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=pgframe\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store, no-cache\r\n" +
            "Pragma: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.flush()
    }

    fun writeJpeg(jpeg: ByteArray) {
        val head = "--pgframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
        out.write(head.toByteArray())
        out.write(jpeg)
        out.write("\r\n".toByteArray())
        out.flush()
    }
}

/** Servidor HTTP mínimo: un hilo por conexión. Suficiente para la red del club. */
class HttpServer(private val port: Int, private val handler: (HttpRequest, HttpResponse) -> Unit) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "pg-http-$port", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                while (running) {
                    val s = try { ss.accept() } catch (e: Exception) { if (running) continue else break }
                    pool.execute { handle(s) }
                }
            } catch (e: Exception) {
                Log.e("PG-Http", "Servidor parado", e)
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len in 1..1_000_000) readN(input, len) else ByteArray(0)
            val qIdx = target.indexOf('?')
            val path = if (qIdx >= 0) target.substring(0, qIdx) else target
            val query = HashMap<String, String>()
            if (qIdx >= 0) parseQuery(target.substring(qIdx + 1), query)
            if (body.isNotEmpty() && headers["content-type"]?.contains("x-www-form-urlencoded") == true) {
                parseQuery(String(body), query)
            }
            val req = HttpRequest(method, path, query, headers, body, s.inetAddress?.hostAddress ?: "")
            val resp = HttpResponse(s)
            if (method == "OPTIONS") {
                val head = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\nConnection: close\r\n\r\n"
                resp.out.write(head.toByteArray())
                resp.out.flush()
                return
            }
            s.soTimeout = 0
            handler(req, resp)
        } catch (e: Exception) {
            // Cliente desconectado: normal en el directo.
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun parseQuery(q: String, into: MutableMap<String, String>) {
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            try {
                into[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return String(buf.toByteArray())
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) out else out.copyOf(off)
    }
}

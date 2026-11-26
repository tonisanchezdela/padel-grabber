package com.padelgrabber.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Servidor del móvil principal (puerto 8080):
 *  /              página para ver el directo en cualquier navegador del club
 *  /stream        vídeo en directo (MJPEG) de la cámara en antena
 *  /cam?id=X      vídeo de una cámara concreta
 *  /snapshot      última imagen
 *  /api/state     marcador y estado en JSON
 *  /api/register  alta de un móvil-cámara
 *  /api/cmd       órdenes: point, undo, pause, resume, review, onair (con PIN)
 */
class LiveServer(
    private val port: Int,
    private val viewerHtml: String,
    private val pin: () -> String,
    private val host: Host
) {
    interface Host {
        fun stateJson(): JSONObject
        /** Se ejecuta en el hilo principal. Devuelve null si fue bien o un mensaje de error. */
        fun onCommand(cmd: String, args: Map<String, String>, fromCamera: String?): String?
        fun onCameraRegistered(id: String, name: String) {}
    }

    private val server = HttpServer(port) { req, resp -> route(req, resp) }
    private val clients = ConcurrentHashMap<String, MjpegClient>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var viewers = 0
        private set

    fun start() = server.start()

    fun stop() {
        server.stop()
        clients.values.forEach { it.stop() }
        clients.clear()
    }

    fun cameraClients(): Collection<MjpegClient> = clients.values

    private fun route(req: HttpRequest, resp: HttpResponse) {
        when (req.path) {
            "/", "/index.html" -> resp.html(viewerHtml)
            "/stream" -> streamProgram(resp)
            "/cam" -> streamSource(resp, req.q("id") ?: FrameHub.MAIN)
            "/snapshot" -> {
                val f = FrameHub.programFrame()
                if (f == null) resp.text(404, "sin imagen") else resp.send(200, "image/jpeg", f.jpeg)
            }
            "/api/state" -> resp.json(host.stateJson().toString())
            "/api/register" -> register(req, resp)
            "/api/cmd" -> command(req, resp)
            else -> resp.text(404, "no encontrado")
        }
    }

    private fun register(req: HttpRequest, resp: HttpResponse) {
        val id = req.q("id")?.take(40)
        val camPort = req.q("port")?.toIntOrNull() ?: 8081
        if (id.isNullOrBlank() || req.remoteIp.isBlank()) {
            resp.text(400, "faltan datos")
            return
        }
        val name = req.q("name")?.take(30)?.ifBlank { null } ?: "Cámara"
        val url = "http://${req.remoteIp}:$camPort/stream"
        val existing = clients[id]
        var isNew = false
        if (existing == null) {
            val c = MjpegClient(id, name, url)
            clients[id] = c
            c.start()
            isNew = true
        } else {
            existing.name = name
            if (existing.url != url) {
                existing.stop()
                val c = MjpegClient(id, name, url)
                clients[id] = c
                c.start()
                isNew = true
            }
        }
        if (isNew) main.post { host.onCameraRegistered(id, name) }
        resp.json(host.stateJson().toString())
    }

    private fun command(req: HttpRequest, resp: HttpResponse) {
        val cmd = req.q("c") ?: ""
        val camId = req.q("cam")
        val fromCamera = if (camId != null && clients.containsKey(camId)) camId else null
        // Las cámaras registradas pueden pedir videoarbitraje sin PIN; lo demás pide PIN.
        val needsPin = !(cmd == "review" && fromCamera != null)
        if (needsPin && req.q("pin") != pin()) {
            resp.send(403, "application/json", JSONObject().put("ok", false).put("error", "PIN incorrecto").toString().toByteArray())
            return
        }
        var error: String? = "sin respuesta"
        val latch = CountDownLatch(1)
        main.post {
            error = try { host.onCommand(cmd, req.query, fromCamera) } catch (e: Exception) { e.message ?: "error" }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        val out = JSONObject().put("ok", error == null)
        error?.let { out.put("error", it) }
        resp.json(out.toString())
    }

    private fun streamProgram(resp: HttpResponse) {
        viewers++
        try {
            resp.startMjpeg()
            var seq = -1L
            var last: JFrame? = null
            var lastWrite = 0L
            while (true) {
                seq = FrameHub.waitNext(seq, 1000)
                val f = FrameHub.programFrame() ?: continue
                if (f === last) continue
                val now = SystemClock.uptimeMillis()
                val wait = 60 - (now - lastWrite)
                if (wait > 0) Thread.sleep(wait)
                resp.writeJpeg(f.jpeg)
                last = f
                lastWrite = SystemClock.uptimeMillis()
            }
        } catch (_: Exception) {
        } finally {
            viewers--
        }
    }

    private fun streamSource(resp: HttpResponse, id: String) {
        try {
            resp.startMjpeg()
            var last: JFrame? = null
            while (true) {
                val f = FrameHub.sources[id]?.latest
                if (f != null && f !== last) {
                    resp.writeJpeg(f.jpeg)
                    last = f
                }
                Thread.sleep(80)
            }
        } catch (_: Exception) {
        }
    }
}

/** Servidor de un móvil-cámara (puerto 8081): solo su vídeo. */
class CameraServer(private val port: Int, private val sourceId: String) {
    private val server = HttpServer(port) { req, resp ->
        when (req.path) {
            "/stream" -> {
                try {
                    resp.startMjpeg()
                    var last: JFrame? = null
                    while (true) {
                        val f = FrameHub.sources[sourceId]?.latest
                        if (f != null && f !== last) {
                            resp.writeJpeg(f.jpeg)
                            last = f
                        }
                        Thread.sleep(60)
                    }
                } catch (_: Exception) {
                }
            }
            "/snapshot" -> {
                val f = FrameHub.sources[sourceId]?.latest
                if (f == null) resp.text(404, "sin imagen") else resp.send(200, "image/jpeg", f.jpeg)
            }
            else -> resp.text(200, "Padel Grabber - cámara")
        }
    }

    fun start() = server.start()
    fun stop() = server.stop()
}

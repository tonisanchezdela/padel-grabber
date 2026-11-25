package com.padelgrabber.net

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Un fotograma JPEG con su hora (uptime ms). */
class JFrame(val jpeg: ByteArray, val t: Long)

/** Una fuente de vídeo: la cámara del propio móvil o la de otro móvil por wifi. */
class Source(val id: String, @Volatile var name: String) {
    @Volatile var latest: JFrame? = null
    @Volatile var lastSeen: Long = 0
    val ring = ArrayDeque<JFrame>()
    val live: Boolean get() = SystemClock.uptimeMillis() - lastSeen < 3000
}

/**
 * Centraliza los fotogramas de todas las cámaras:
 * - guarda los últimos segundos de cada una (para videoarbitraje y repeticiones)
 * - sabe qué cámara está "en antena" para el directo
 * - puede emitir una repetición en el directo
 */
object FrameHub {
    const val MAIN = "main"
    private const val RING_MS = 16_000L

    val sources = ConcurrentHashMap<String, Source>()
    @Volatile var onAir: String = MAIN
    @Volatile var replayLabel: String? = null
        private set
    @Volatile private var replayFrame: JFrame? = null
    @Volatile private var replayToken = 0

    private val lock = Object()
    private var seq = 0L

    fun reset() {
        sources.clear()
        onAir = MAIN
        stopReplay()
    }

    fun push(id: String, name: String, jpeg: ByteArray) {
        val now = SystemClock.uptimeMillis()
        val src = sources.getOrPut(id) { Source(id, name) }
        if (src.name != name && name.isNotBlank()) src.name = name
        val f = JFrame(jpeg, now)
        src.latest = f
        src.lastSeen = now
        synchronized(src.ring) {
            src.ring.addLast(f)
            while (src.ring.isNotEmpty() && now - src.ring.first().t > RING_MS) src.ring.removeFirst()
        }
        if (id == onAir && replayFrame == null) signal()
    }

    private fun signal() {
        synchronized(lock) {
            seq++
            lock.notifyAll()
        }
    }

    /** Espera a que haya un fotograma nuevo del programa. Devuelve la nueva secuencia. */
    fun waitNext(lastSeq: Long, timeoutMs: Long): Long {
        synchronized(lock) {
            if (seq == lastSeq) lock.wait(timeoutMs)
            return seq
        }
    }

    /** Lo que se emite ahora mismo en el directo. */
    fun programFrame(): JFrame? = replayFrame ?: sources[onAir]?.latest ?: sources[MAIN]?.latest

    /** Copia de los fotogramas de una fuente entre dos horas (uptime ms). */
    fun snapshot(id: String, fromMs: Long, toMs: Long): List<JFrame> {
        val src = sources[id] ?: return emptyList()
        synchronized(src.ring) {
            return src.ring.filter { it.t in fromMs..toMs }
        }
    }

    fun liveSources(): List<Source> = sources.values.filter { it.live }.sortedBy { if (it.id == MAIN) "" else it.name }

    /** Emite en el directo una lista de fotogramas a su ritmo original (repetición). */
    fun startReplay(frames: List<JFrame>, label: String, slow: Float = 1f) {
        if (frames.size < 3) return
        val token = ++replayToken
        replayLabel = label
        thread(name = "pg-replay", isDaemon = true) {
            try {
                for (i in frames.indices) {
                    if (replayToken != token) return@thread
                    replayFrame = frames[i]
                    signal()
                    if (i + 1 < frames.size) {
                        val gap = ((frames[i + 1].t - frames[i].t) / slow).toLong().coerceIn(10, 400)
                        Thread.sleep(gap)
                    }
                }
                Thread.sleep(400)
            } catch (_: InterruptedException) {
            } finally {
                if (replayToken == token) {
                    replayFrame = null
                    replayLabel = null
                    signal()
                }
            }
        }
    }

    fun stopReplay() {
        replayToken++
        replayFrame = null
        replayLabel = null
        signal()
    }
}

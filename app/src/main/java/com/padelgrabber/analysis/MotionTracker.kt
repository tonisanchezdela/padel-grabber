package com.padelgrabber.analysis

import java.util.ArrayDeque

/**
 * Guarda el nivel de movimiento de los últimos minutos y decide si hay juego.
 * El umbral se adapta a cada pista: se compara con el movimiento "de fondo".
 */
class MotionTracker(sensitivity: Int = 3) {

    private class Sample(val wall: Long, val video: Long, val level: Float)

    private val samples = ArrayDeque<Sample>()
    private val keepMs = 180_000L
    @Volatile var smoothed = 0f
        private set
    private var activeSince = -1L
    private var quietSince = -1L
    private var lastWall = 0L

    /** Multiplicador sobre el fondo: más sensibilidad = umbral más bajo. */
    private val factor: Float = when (sensitivity) {
        1 -> 3.6f
        2 -> 3.0f
        3 -> 2.5f
        4 -> 2.1f
        else -> 1.8f
    }
    private val floor: Float = when (sensitivity) {
        1 -> 0.03f
        2 -> 0.022f
        3 -> 0.016f
        4 -> 0.012f
        else -> 0.009f
    }

    @Synchronized
    fun add(level: Float, wall: Long, video: Long) {
        smoothed = if (samples.isEmpty()) level else smoothed * 0.6f + level * 0.4f
        samples.addLast(Sample(wall, video, smoothed))
        while (samples.isNotEmpty() && wall - samples.peekFirst()!!.wall > keepMs) samples.removeFirst()
        val active = smoothed > threshold()
        if (active) {
            if (activeSince < 0) activeSince = wall
            quietSince = -1
        } else {
            if (quietSince < 0) quietSince = wall
            activeSince = -1
        }
        lastWall = wall
    }

    private var cachedThreshold = 0.02f
    private var thresholdAt = 0L

    /** Umbral de "hay juego": percentil 25 del último minuto × factor. */
    @Synchronized
    fun threshold(): Float {
        if (lastWall - thresholdAt < 2000 && thresholdAt != 0L) return cachedThreshold
        thresholdAt = lastWall
        val recent = samples.filter { lastWall - it.wall < 60_000 }.map { it.level }.sorted()
        val base = if (recent.size < 20) 0f else recent[recent.size / 4]
        cachedThreshold = maxOf(floor, base * factor)
        return cachedThreshold
    }

    /** Milisegundos seguidos con movimiento de juego (0 si ahora no lo hay). */
    @Synchronized
    fun activeFor(now: Long): Long = if (activeSince < 0) 0 else now - activeSince

    /** Milisegundos seguidos sin movimiento de juego. */
    @Synchronized
    fun quietFor(now: Long): Long = if (quietSince < 0) 0 else now - quietSince

    @Synchronized
    fun reset() {
        samples.clear()
        activeSince = -1
        quietSince = -1
    }

    /**
     * Estima en qué momento del vídeo empezó el punto que acaba de apuntarse:
     * el final del último tramo tranquilo (≥ 1,5 s) entre el punto anterior y este.
     */
    @Synchronized
    fun rallyStart(afterVideo: Long, tapVideo: Long): Long {
        val thr = threshold()
        val from = afterVideo + 2000
        val to = tapVideo - 1000
        var quietStartVideo = -1L
        var lastQuietEnd = -1L
        for (s in samples) {
            if (s.video < from || s.video > to) continue
            if (s.level <= thr) {
                if (quietStartVideo < 0) quietStartVideo = s.video
            } else {
                if (quietStartVideo >= 0 && s.video - quietStartVideo >= 1500) lastQuietEnd = s.video
                quietStartVideo = -1
            }
        }
        val fallback = maxOf(afterVideo + 3000, tapVideo - 25_000)
        val start = if (lastQuietEnd > 0) lastQuietEnd else fallback
        return start.coerceIn(0, maxOf(0, tapVideo - 3000))
    }
}

/** Reloj del vídeo grabado (descuenta las pausas). */
class VideoClock {
    private var accumulated = 0L
    private var runningSince = -1L
    var started = false
        private set

    @Synchronized fun start(now: Long) { accumulated = 0; runningSince = now; started = true }
    @Synchronized fun pause(now: Long) {
        if (runningSince >= 0) { accumulated += now - runningSince; runningSince = -1 }
    }
    @Synchronized fun resume(now: Long) { if (runningSince < 0 && started) runningSince = now }
    @Synchronized fun now(now: Long): Long = accumulated + if (runningSince >= 0) now - runningSince else 0
    val running: Boolean @Synchronized get() = runningSince >= 0
}

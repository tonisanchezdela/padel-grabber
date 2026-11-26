package com.padelgrabber.process

import com.padelgrabber.match.Marker
import com.padelgrabber.match.Match
import com.padelgrabber.match.PointRec
import com.padelgrabber.score.Ev
import com.padelgrabber.score.ScoreEngine

/** Un trozo del vídeo final. Tiempos en ms del vídeo original. */
data class Piece(
    val startMs: Long,
    val endMs: Long,
    val kind: Int,
    val title: String = "",
    val subtitle: String = "",
    val intro: Boolean = false,
    val outro: Boolean = false
) {
    val durationMs: Long get() = endMs - startMs

    companion object {
        const val MAIN = 0
        const val REPLAY = 1
        const val REVIEW = 2
        const val HIGHLIGHT = 3
    }
}

/** Decide qué trozos van en cada vídeo. */
object Highlights {

    private const val TAP_DELAY = 800L

    private fun secs(ms: Long) = ((ms + 500) / 1000).toInt()

    /** Final del punto más largo de cada juego (8-9 s antes de apuntarlo). */
    fun replayWindow(p: PointRec): Pair<Long, Long> {
        val end = (p.tMs - TAP_DELAY).coerceAtLeast(0)
        val start = maxOf(end - 9000, p.startMs - 500, 0)
        return (if (end - start < 5000) (end - 5000).coerceAtLeast(0) else start) to end
    }

    /** Vídeo del partido con repeticiones intercaladas tras cada juego y en cada videoarbitraje. */
    fun withReplays(m: Match, videoDurationMs: Long): List<Piece> {
        data class Insert(val at: Long, val piece: Piece)
        val inserts = ArrayList<Insert>()

        val byGame = m.points.groupBy { it.game }
        for ((_, pts) in byGame) {
            val last = pts.maxByOrNull { it.tMs } ?: continue
            val endsGame = Ev.has(last.flags, Ev.GAME)
            if (!endsGame) continue
            val best = pts.maxByOrNull { it.durationMs } ?: continue
            if (best.durationMs < 3000) continue
            val (s, e) = replayWindow(best)
            val at = (last.tMs + 2500).coerceAtMost(videoDurationMs)
            inserts.add(
                Insert(
                    at, Piece(
                        s, e, Piece.REPLAY, "REPETICIÓN",
                        "Set ${best.set} · Juego ${best.gameInSet} · punto de ${secs(best.durationMs)} s"
                    )
                )
            )
        }
        for (mk in m.markers.filter { it.type == Marker.REVIEW }) {
            val s = (mk.tMs - 13_000).coerceAtLeast(0)
            val e = (mk.tMs - 2500).coerceAtLeast(s + 3000)
            inserts.add(Insert(mk.tMs.coerceAtMost(videoDurationMs), Piece(s, e, Piece.REVIEW, "VIDEOARBITRAJE", mk.text.replaceFirstChar { it.uppercase() })))
        }
        inserts.sortBy { it.at }

        val out = ArrayList<Piece>()
        var cursor = 0L
        for (ins in inserts) {
            if (ins.at - cursor >= 1000) out.add(Piece(cursor, ins.at, Piece.MAIN))
            if (ins.piece.endMs - ins.piece.startMs >= 2000 && ins.piece.endMs <= videoDurationMs) out.add(ins.piece)
            cursor = maxOf(cursor, ins.at)
        }
        if (videoDurationMs - cursor >= 500) out.add(Piece(cursor, videoDurationMs, Piece.MAIN))
        return out
    }

    /** Resumen: bolas de set y partido + los puntos más largos, hasta ~2,5 minutos. */
    fun summary(m: Match, videoDurationMs: Long, maxTotalMs: Long = 150_000): List<Piece> {
        // Sin puntos apuntados usamos los que detectó la app sola por el movimiento
        val points = if (m.points.isNotEmpty()) m.points else autoPoints(m)
        if (points.isEmpty()) return emptyList()
        fun window(p: PointRec): Pair<Long, Long> {
            val end = (p.tMs - TAP_DELAY).coerceIn(0, videoDurationMs)
            val start = maxOf(p.startMs - 1000, end - 20_000, 0)
            return (if (end - start < 6000) (end - 6000).coerceAtLeast(0) else start) to end
        }
        fun describe(p: PointRec): Pair<String, String> {
            val title = when {
                Ev.has(p.flags, Ev.MATCH) -> "PUNTO DE PARTIDO"
                Ev.has(p.flags, Ev.SET) -> "SET ${p.set} PARA ${if (p.team == 0) m.teamA else m.teamB}".uppercase()
                else -> "MEJOR PUNTO"
            }
            if (p.team < 0) return title to "Punto de ${secs(p.durationMs)} s"
            return title to "Set ${p.set} · Juego ${p.gameInSet} · ${secs(p.durationMs)} s de punto"
        }

        val forced = points.filter { Ev.has(it.flags, Ev.SET) || Ev.has(it.flags, Ev.MATCH) }
        val rest = points.filter { it !in forced }.sortedByDescending { it.durationMs }
        val chosen = ArrayList<PointRec>()
        var total = 0L
        for (p in forced + rest) {
            val (s, e) = window(p)
            if (total + (e - s) > maxTotalMs && chosen.isNotEmpty()) {
                if (p in forced) { chosen.add(p); total += e - s }
                continue
            }
            chosen.add(p)
            total += e - s
        }
        chosen.sortBy { it.tMs }

        // Trozos sin solaparse
        val pieces = ArrayList<Piece>()
        for (p in chosen) {
            var (s, e) = window(p)
            val prev = pieces.lastOrNull()
            if (prev != null && s < prev.endMs) s = prev.endMs
            if (e - s < 2500) continue
            val (t, sub) = describe(p)
            pieces.add(Piece(s, e, Piece.HIGHLIGHT, t, sub))
        }
        if (pieces.isEmpty()) return emptyList()
        val result = ArrayList<Piece>()
        pieces.forEachIndexed { i, p ->
            result.add(p.copy(intro = i == 0, outro = i == pieces.size - 1))
        }
        return result
    }

    /** Puntos detectados automáticamente (marcas "rally"), como si se hubieran apuntado. */
    fun autoPoints(m: Match): List<PointRec> =
        m.markers.filter { it.type == Marker.RALLY }.mapIndexedNotNull { i, mk ->
            val start = mk.text.toLongOrNull() ?: return@mapIndexedNotNull null
            PointRec(i, -1, mk.tMs + TAP_DELAY, start, 1, 0, 0, 0, "")
        }

    fun resultLine(m: Match): String {
        val sets = ScoreEngine.setsSummary(m.sets)
        val winner = when (m.winner) { 0 -> m.teamA; 1 -> m.teamB; else -> null }
        return if (winner != null) "Ganan $winner · $sets" else sets
    }
}

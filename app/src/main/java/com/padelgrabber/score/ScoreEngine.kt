package com.padelgrabber.score

import org.json.JSONArray
import org.json.JSONObject

/** Reglas del partido. */
data class MatchConfig(
    val bestOf: Int = 3,              // 1 o 3 sets
    val goldenPoint: Boolean = true,  // punto de oro en 40-40
    val superTiebreak: Boolean = false, // set decisivo como super tie-break a 10
    val firstServer: Int = 0          // 0 = equipo A, 1 = equipo B
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bestOf", bestOf)
        .put("goldenPoint", goldenPoint)
        .put("superTiebreak", superTiebreak)
        .put("firstServer", firstServer)

    companion object {
        fun fromJson(o: JSONObject?): MatchConfig {
            if (o == null) return MatchConfig()
            return MatchConfig(
                bestOf = o.optInt("bestOf", 3),
                goldenPoint = o.optBoolean("goldenPoint", true),
                superTiebreak = o.optBoolean("superTiebreak", false),
                firstServer = o.optInt("firstServer", 0)
            )
        }
    }
}

/** Resultado de un set terminado. tbA/tbB = puntos del tie-break (o -1 si no hubo). */
data class SetScore(val a: Int, val b: Int, val tbA: Int = -1, val tbB: Int = -1, val superTb: Boolean = false) {
    fun winner(): Int = if (a > b) 0 else 1
}

/** Estado inmutable del marcador. */
data class ScoreState(
    val sets: List<SetScore> = emptyList(),
    val gamesA: Int = 0,
    val gamesB: Int = 0,
    val pointsA: Int = 0,
    val pointsB: Int = 0,
    val tiebreak: Boolean = false,
    val superTb: Boolean = false,
    val server: Int = 0,
    val tbFirstServer: Int = 0,
    val finished: Boolean = false,
    val winner: Int = -1,
    val gameNumber: Int = 1
) {
    val setsA: Int get() = sets.count { it.a > it.b }
    val setsB: Int get() = sets.count { it.b > it.a }
    val currentSet: Int get() = sets.size + 1
    val gameInSet: Int get() = gamesA + gamesB + 1
}

/** Banderas de lo que ha pasado con un punto. */
object Ev {
    const val GAME = 1
    const val SET = 2
    const val MATCH = 4
    const val SIDE_CHANGE = 8
    const val TIEBREAK_START = 16
    const val BREAK_SHORT = 32   // descanso de 90 s en cambio de lado
    const val BREAK_SET = 64     // descanso de 120 s entre sets

    fun has(flags: Int, f: Int) = (flags and f) != 0
}

class Outcome(val state: ScoreState, val flags: Int)

class ScoreEngine(val cfg: MatchConfig) {

    fun initial(): ScoreState = ScoreState(server = cfg.firstServer, tbFirstServer = cfg.firstServer)

    fun point(s: ScoreState, team: Int): Outcome {
        if (s.finished) return Outcome(s, 0)
        val pa = s.pointsA + if (team == 0) 1 else 0
        val pb = s.pointsB + if (team == 1) 1 else 0

        if (s.tiebreak) {
            val target = if (s.superTb) 10 else 7
            val total = pa + pb
            val wonBy = when {
                pa >= target && pa - pb >= 2 -> 0
                pb >= target && pb - pa >= 2 -> 1
                else -> -1
            }
            if (wonBy < 0) {
                var flags = 0
                if (total % 6 == 0) flags = flags or Ev.SIDE_CHANGE
                return Outcome(
                    s.copy(pointsA = pa, pointsB = pb, server = tbServer(s.tbFirstServer, total)),
                    flags
                )
            }
            val set = if (s.superTb) {
                SetScore(if (wonBy == 0) 1 else 0, if (wonBy == 1) 1 else 0, pa, pb, superTb = true)
            } else {
                SetScore(
                    s.gamesA + (if (wonBy == 0) 1 else 0),
                    s.gamesB + (if (wonBy == 1) 1 else 0),
                    pa, pb
                )
            }
            return finishSet(s, set, 1 - s.tbFirstServer)
        }

        val wonBy = gameWinner(pa, pb)
        if (wonBy < 0) return Outcome(s.copy(pointsA = pa, pointsB = pb), 0)

        val ga = s.gamesA + if (wonBy == 0) 1 else 0
        val gb = s.gamesB + if (wonBy == 1) 1 else 0
        val nextServer = 1 - s.server
        val setWon = (ga >= 6 && ga - gb >= 2) || (gb >= 6 && gb - ga >= 2)
        if (setWon) return finishSet(s, SetScore(ga, gb), nextServer)

        var flags = Ev.GAME
        val totalGames = ga + gb
        if (totalGames % 2 == 1) {
            flags = flags or Ev.SIDE_CHANGE
            if (totalGames > 1) flags = flags or Ev.BREAK_SHORT
        }
        if (ga == 6 && gb == 6) {
            flags = flags or Ev.TIEBREAK_START
            return Outcome(
                s.copy(
                    gamesA = ga, gamesB = gb, pointsA = 0, pointsB = 0,
                    tiebreak = true, superTb = false,
                    server = nextServer, tbFirstServer = nextServer,
                    gameNumber = s.gameNumber + 1
                ), flags
            )
        }
        return Outcome(
            s.copy(
                gamesA = ga, gamesB = gb, pointsA = 0, pointsB = 0,
                server = nextServer, gameNumber = s.gameNumber + 1
            ), flags
        )
    }

    private fun finishSet(s: ScoreState, set: SetScore, nextServer: Int): Outcome {
        val sets = s.sets + set
        val sa = sets.count { it.a > it.b }
        val sb = sets.count { it.b > it.a }
        val need = cfg.bestOf / 2 + 1
        var flags = Ev.GAME or Ev.SET
        if (sa >= need || sb >= need) {
            flags = flags or Ev.MATCH
            return Outcome(
                ScoreState(
                    sets = sets, server = nextServer, tbFirstServer = nextServer,
                    finished = true, winner = if (sa > sb) 0 else 1,
                    gameNumber = s.gameNumber + 1
                ), flags
            )
        }
        val totalGames = if (set.superTb) 1 else set.a + set.b
        if (totalGames % 2 == 1) flags = flags or Ev.SIDE_CHANGE
        flags = flags or Ev.BREAK_SET
        val decider = sets.size == cfg.bestOf - 1 && sa == sb
        val superTb = decider && cfg.superTiebreak
        if (superTb) flags = flags or Ev.TIEBREAK_START
        return Outcome(
            ScoreState(
                sets = sets, server = nextServer, tbFirstServer = nextServer,
                tiebreak = superTb, superTb = superTb, gameNumber = s.gameNumber + 1
            ), flags
        )
    }

    private fun gameWinner(pa: Int, pb: Int): Int = if (cfg.goldenPoint) {
        when {
            pa >= 4 && pa > pb -> 0
            pb >= 4 && pb > pa -> 1
            else -> -1
        }
    } else {
        when {
            pa >= 4 && pa - pb >= 2 -> 0
            pb >= 4 && pb - pa >= 2 -> 1
            else -> -1
        }
    }

    /** Quién saca el siguiente punto del tie-break tras [played] puntos jugados. */
    private fun tbServer(first: Int, played: Int): Int {
        if (played == 0) return first
        return if (((played - 1) / 2) % 2 == 0) 1 - first else first
    }

    // ---------- Textos ----------

    fun pointLabels(s: ScoreState): Pair<String, String> {
        if (s.finished) return "" to ""
        if (s.tiebreak) return s.pointsA.toString() to s.pointsB.toString()
        val a = s.pointsA
        val b = s.pointsB
        if (a >= 3 && b >= 3) {
            if (a == b) return "40" to "40"
            return if (a > b) "AD" to "40" else "40" to "AD"
        }
        val m = arrayOf("0", "15", "30", "40")
        return m[minOf(a, 3)] to m[minOf(b, 3)]
    }

    fun isGoldenPointNow(s: ScoreState): Boolean =
        cfg.goldenPoint && !s.finished && !s.tiebreak && s.pointsA == 3 && s.pointsB == 3

    /** Rótulo de presión: punto de oro, bola de set, bola de partido o tie-break. */
    fun pressureBadge(s: ScoreState): String? {
        if (s.finished) return null
        var match = false
        var set = false
        for (t in 0..1) {
            val f = point(s, t).flags
            if (Ev.has(f, Ev.MATCH)) match = true else if (Ev.has(f, Ev.SET)) set = true
        }
        return when {
            match -> "BOLA DE PARTIDO"
            set -> "BOLA DE SET"
            isGoldenPointNow(s) -> "PUNTO DE ORO"
            s.superTb -> "SUPER TIE-BREAK"
            s.tiebreak -> "TIE-BREAK"
            else -> null
        }
    }

    companion object {
        fun setsSummary(sets: List<SetScore>): String = sets.joinToString("  ") { st ->
            when {
                st.superTb -> "[${st.tbA}-${st.tbB}]"
                st.tbA >= 0 -> "${st.a}-${st.b}(${minOf(st.tbA, st.tbB)})"
                else -> "${st.a}-${st.b}"
            }
        }

        fun setsToJson(sets: List<SetScore>): JSONArray {
            val arr = JSONArray()
            for (st in sets) {
                arr.put(
                    JSONObject().put("a", st.a).put("b", st.b)
                        .put("tbA", st.tbA).put("tbB", st.tbB).put("super", st.superTb)
                )
            }
            return arr
        }

        fun setsFromJson(arr: JSONArray?): List<SetScore> {
            if (arr == null) return emptyList()
            val out = ArrayList<SetScore>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SetScore(
                        o.optInt("a"), o.optInt("b"), o.optInt("tbA", -1), o.optInt("tbB", -1),
                        o.optBoolean("super", false)
                    )
                )
            }
            return out
        }
    }
}

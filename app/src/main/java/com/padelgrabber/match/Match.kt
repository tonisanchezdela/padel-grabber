package com.padelgrabber.match

import android.content.Context
import android.os.Environment
import com.padelgrabber.score.MatchConfig
import com.padelgrabber.score.ScoreEngine
import com.padelgrabber.score.SetScore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Un punto marcado. Los tiempos son milisegundos dentro del vídeo grabado. */
data class PointRec(
    val idx: Int,
    val team: Int,
    val tMs: Long,       // momento en que se apuntó el punto
    val startMs: Long,   // inicio estimado del punto
    val set: Int,
    val game: Int,       // número de juego dentro del partido
    val gameInSet: Int,
    val flags: Int,      // banderas Ev.* que provocó
    val label: String    // marcador tras el punto
) {
    val durationMs: Long get() = (tMs - 800 - startMs).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject()
        .put("idx", idx).put("team", team).put("t", tMs).put("start", startMs)
        .put("set", set).put("game", game).put("gameInSet", gameInSet)
        .put("flags", flags).put("label", label)

    companion object {
        fun fromJson(o: JSONObject) = PointRec(
            o.optInt("idx"), o.optInt("team"), o.optLong("t"), o.optLong("start"),
            o.optInt("set"), o.optInt("game"), o.optInt("gameInSet"), o.optInt("flags"),
            o.optString("label")
        )
    }
}

/** Marcas de la línea de tiempo: videoarbitraje, pausas, etc. */
data class Marker(val type: String, val tMs: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("t", tMs).put("text", text)

    companion object {
        const val REVIEW = "review"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val RALLY = "rally"   // punto detectado solo: t = fin, text = inicio
        fun fromJson(o: JSONObject) = Marker(o.optString("type"), o.optLong("t"), o.optString("text"))
    }
}

class Match(
    val id: String,
    val createdAt: Long,
    var role: String = ROLE_MAIN,          // principal o cámara extra
    var linkedMatchId: String = id,        // id del partido en la plataforma
    var cameraName: String = "Principal",
    var club: String = "",
    var court: String = "",
    var teamA: String = "Equipo A",
    var teamB: String = "Equipo B",
    var config: MatchConfig = MatchConfig(),
    val points: MutableList<PointRec> = ArrayList(),
    val markers: MutableList<Marker> = ArrayList(),
    var sets: List<SetScore> = emptyList(),
    var winner: Int = -1,
    var durationMs: Long = 0,
    var videoFile: String? = null,
    val outputs: MutableMap<String, String> = LinkedHashMap(),   // tipo -> archivo
    val uploaded: MutableMap<String, String> = LinkedHashMap(),  // tipo -> url
    var status: String = STATUS_NEW
) {
    val title: String get() = "$teamA vs $teamB"
    val resultText: String get() = ScoreEngine.setsSummary(sets)

    fun toJson(): JSONObject {
        val pts = JSONArray(); points.forEach { pts.put(it.toJson()) }
        val mks = JSONArray(); markers.forEach { mks.put(it.toJson()) }
        val outs = JSONObject(); outputs.forEach { (k, v) -> outs.put(k, v) }
        val ups = JSONObject(); uploaded.forEach { (k, v) -> ups.put(k, v) }
        return JSONObject()
            .put("id", id).put("createdAt", createdAt).put("role", role)
            .put("linkedMatchId", linkedMatchId).put("cameraName", cameraName)
            .put("club", club).put("court", court).put("teamA", teamA).put("teamB", teamB)
            .put("config", config.toJson()).put("points", pts).put("markers", mks)
            .put("sets", ScoreEngine.setsToJson(sets)).put("winner", winner)
            .put("durationMs", durationMs).put("videoFile", videoFile ?: JSONObject.NULL)
            .put("outputs", outs).put("uploaded", ups).put("status", status)
    }

    companion object {
        const val ROLE_MAIN = "principal"
        const val ROLE_CAMERA = "camara"

        const val STATUS_NEW = "nuevo"
        const val STATUS_RECORDING = "grabando"
        const val STATUS_RECORDED = "grabado"
        const val STATUS_PROCESSED = "procesado"
        const val STATUS_UPLOADED = "subido"

        // Tipos de vídeo
        const val OUT_FULL = "completo"
        const val OUT_REPLAYS = "repeticiones"
        const val OUT_SUMMARY = "resumen"

        fun newId(): String {
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val suffix = (1..3).map { chars[Random.nextInt(chars.length)] }.joinToString("")
            return "$fmt-$suffix"
        }

        fun fromJson(o: JSONObject): Match {
            val m = Match(
                id = o.getString("id"),
                createdAt = o.optLong("createdAt"),
                role = o.optString("role", ROLE_MAIN),
                linkedMatchId = o.optString("linkedMatchId", o.getString("id")),
                cameraName = o.optString("cameraName", "Principal"),
                club = o.optString("club"),
                court = o.optString("court"),
                teamA = o.optString("teamA", "Equipo A"),
                teamB = o.optString("teamB", "Equipo B"),
                config = MatchConfig.fromJson(o.optJSONObject("config")),
                sets = ScoreEngine.setsFromJson(o.optJSONArray("sets")),
                winner = o.optInt("winner", -1),
                durationMs = o.optLong("durationMs"),
                videoFile = if (o.isNull("videoFile")) null else o.optString("videoFile"),
                status = o.optString("status", STATUS_NEW)
            )
            o.optJSONArray("points")?.let { a ->
                for (i in 0 until a.length()) m.points.add(PointRec.fromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("markers")?.let { a ->
                for (i in 0 until a.length()) m.markers.add(Marker.fromJson(a.getJSONObject(i)))
            }
            o.optJSONObject("outputs")?.let { obj ->
                obj.keys().forEach { k -> m.outputs[k] = obj.getString(k) }
            }
            o.optJSONObject("uploaded")?.let { obj ->
                obj.keys().forEach { k -> m.uploaded[k] = obj.getString(k) }
            }
            return m
        }
    }
}

/** Guarda cada partido en su carpeta: .../Movies/PadelGrabber/<id>/match.json + vídeos. */
object MatchStore {

    fun baseDir(ctx: Context): File {
        val root = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        val dir = File(root, "PadelGrabber")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun dir(ctx: Context, id: String): File {
        val d = File(baseDir(ctx), id)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun file(ctx: Context, m: Match, name: String): File = File(dir(ctx, m.id), name)

    @Synchronized
    fun save(ctx: Context, m: Match) {
        val d = dir(ctx, m.id)
        val tmp = File(d, "match.json.tmp")
        tmp.writeText(m.toJson().toString(2))
        val dst = File(d, "match.json")
        if (!tmp.renameTo(dst)) {
            dst.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(ctx: Context, id: String): Match? {
        val f = File(File(baseDir(ctx), id), "match.json")
        if (!f.exists()) return null
        return try {
            Match.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<Match> {
        val dirs = baseDir(ctx).listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.mapNotNull { load(ctx, it.name) }.sortedByDescending { it.createdAt }
    }

    fun delete(ctx: Context, id: String) {
        File(baseDir(ctx), id).deleteRecursively()
    }
}

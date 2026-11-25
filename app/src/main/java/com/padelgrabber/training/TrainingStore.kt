package com.padelgrabber.training

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.padelgrabber.util.Ui
import com.padelgrabber.util.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sesiones de entrenamiento guardadas en el móvil. */
object TrainingStore {

    class Session(val start: Long, val source: String, val strokes: List<JSONObject>)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "training").also { it.mkdirs() }

    fun rightHanded(ctx: Context): Boolean =
        ctx.getSharedPreferences("padel_grabber", Context.MODE_PRIVATE).getBoolean("right_handed", true)

    fun setRightHanded(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("padel_grabber", Context.MODE_PRIVATE).edit().putBoolean("right_handed", v).apply()
    }

    fun save(ctx: Context, start: Long, source: String, results: List<StrokeResult>) {
        if (results.isEmpty()) return
        val arr = JSONArray()
        results.forEach { arr.put(it.toJson()) }
        val o = JSONObject().put("start", start).put("source", source).put("strokes", arr)
        File(dir(ctx), "$start.json").writeText(o.toString())
    }

    fun list(ctx: Context): List<Session> {
        val files = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val a = o.optJSONArray("strokes") ?: JSONArray()
                Session(o.optLong("start"), o.optString("source"), (0 until a.length()).map { a.getJSONObject(it) })
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.start }
    }
}

/** Tarjeta con el resultado de un golpe. */
object ResultCard {

    fun view(ctx: Context, r: StrokeResult, compact: Boolean = false): View {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(0xE60B1D33.toInt(), ctx.dp(14).toFloat(), Ui.NAVY3, ctx.dp(1))
        card.setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(12))

        val head = TextView(ctx)
        head.text = "${r.stroke.title}${if (r.auto) " (automático)" else ""}  ·  ${r.score}/100"
        head.setTextColor(scoreColor(r.score))
        head.typeface = Ui.BOLD
        head.textSize = if (compact) 18f else 22f
        card.addView(head)

        for ((metric, range) in r.stroke.ranges) {
            val v = r.metrics[metric] ?: continue
            val ok = range.contains(v)
            val t = TextView(ctx)
            val value = if (metric.unit == "°") "${v.toInt()}°" else String.format("%.2f", v)
            t.text = "${if (ok) "✓" else "✗"} ${metric.label}: $value  (recomendado ${range.text(metric.unit)})"
            t.setTextColor(if (ok) Ui.WHITE else 0xFFFFB4A8.toInt())
            t.textSize = if (compact) 13f else 14f
            card.addView(t)
        }
        r.metrics[Metric.SPEED]?.let {
            val t = TextView(ctx)
            t.text = "Velocidad del brazo: ${String.format("%.1f", it)} troncos/s"
            t.setTextColor(Ui.GREY)
            t.textSize = 13f
            card.addView(t)
        }
        for (tip in r.tips.take(if (compact) 2 else 5)) {
            val t = TextView(ctx)
            t.text = "• $tip"
            t.setTextColor(Ui.LIME)
            t.setTypeface(null, Typeface.BOLD)
            t.textSize = if (compact) 13f else 15f
            t.setPadding(0, ctx.dp(4), 0, 0)
            card.addView(t)
        }
        return card
    }

    fun scoreColor(score: Int): Int = when {
        score >= 80 -> Ui.LIME
        score >= 60 -> 0xFFFFD60A.toInt()
        else -> 0xFFFF7A6B.toInt()
    }
}

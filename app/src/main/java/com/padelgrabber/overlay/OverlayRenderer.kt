package com.padelgrabber.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.padelgrabber.score.MatchConfig
import com.padelgrabber.score.ScoreEngine
import com.padelgrabber.score.ScoreState
import kotlin.math.max
import kotlin.math.min

/** Cortinilla animada "Padel Grabber". */
data class Cortinilla(
    val title: String,
    val subtitle: String,
    val startMs: Long,      // SystemClock.uptimeMillis() del inicio
    val durationMs: Long,
    val style: Int = FULL
) {
    companion object {
        const val FULL = 0   // tapa la pantalla, muestra textos y se va
        const val QUICK = 1  // barrido rápido con el logo
    }
}

/** Todo lo que se pinta encima del vídeo. Inmutable para poder leerlo desde otro hilo. */
data class RenderState(
    val teamA: String,
    val teamB: String,
    val score: ScoreState,
    val cfg: MatchConfig,
    val club: String = "",
    val showScore: Boolean = true,
    val live: Boolean = false,
    val badge: String? = null,
    val status: String? = null,
    val camLabel: String? = null,
    val cortinilla: Cortinilla? = null
)

/**
 * Dibuja marcador, rótulos y cortinillas sobre un Canvas en coordenadas "derechas"
 * (0..w, 0..h). Cada hilo debe usar su propia instancia.
 */
class OverlayRenderer {

    companion object {
        val NAVY = 0xFF0B1D33.toInt()
        val NAVY2 = 0xFF132B4A.toInt()
        val LIME = 0xFFC6FF00.toInt()
        val GREY = 0xFF9FB3C8.toInt()
        val RED = 0xFFFF3B30.toInt()
        val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val REGULAR: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

        fun easeOut(t: Float): Float { val x = t.coerceIn(0f, 1f); return 1f - (1f - x) * (1f - x) * (1f - x) }
        fun easeIn(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * x }
        fun easeInOut(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
        }
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = BOLD }
    private val path = Path()
    private val rect = RectF()
    private val engines = HashMap<MatchConfig, ScoreEngine>()

    private fun engine(cfg: MatchConfig) = engines.getOrPut(cfg) { ScoreEngine(cfg) }

    fun draw(c: Canvas, w: Int, h: Int, rs: RenderState, now: Long) {
        val s = h / 720f
        if (rs.showScore) drawScoreboard(c, s, rs)
        drawTopRight(c, w, s, rs)
        drawWatermark(c, w, h, s)
        val ct = rs.cortinilla
        if (ct != null) {
            val p = (now - ct.startMs).toFloat() / ct.durationMs.coerceAtLeast(1)
            if (p in 0f..1f) drawCortinilla(c, w, h, s, ct.title, ct.subtitle, ct.style, p)
        }
    }

    // ------------------------------------------------------------------ Marcador

    private fun fit(str: String, maxW: Float): String {
        if (text.measureText(str) <= maxW) return str
        var t = str
        while (t.isNotEmpty() && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    fun drawScoreboard(c: Canvas, s: Float, rs: RenderState) {
        val st = rs.score
        val eng = engine(rs.cfg)
        val x0 = 22f * s
        val y0 = 22f * s
        val headerH = 26f * s
        val rowH = 42f * s
        val dotW = 24f * s
        val setW = 36f * s
        val gameW = 42f * s
        val ptsW = 60f * s
        val nameSize = 23f * s

        text.typeface = BOLD
        text.textSize = nameSize
        val nameW = max(text.measureText(rs.teamA), text.measureText(rs.teamB))
            .coerceIn(130f * s, 320f * s) + 12f * s
        val nSets = st.sets.size
        val totalW = dotW + nameW + nSets * setW + (if (st.finished) 0f else gameW + ptsW)
        val r = 8f * s

        // Cabecera
        fill.color = LIME
        rect.set(x0, y0, x0 + totalW, y0 + headerH + r)
        c.drawRoundRect(rect, r, r, fill)
        text.color = NAVY
        text.textSize = 16f * s
        text.textAlign = Paint.Align.LEFT
        val head = "PADEL GRABBER"
        c.drawText(head, x0 + 10f * s, y0 + headerH * 0.72f, text)
        val sub = listOf(rs.club, "SET ${st.currentSet}".takeIf { !st.finished } ?: "FINAL")
            .filter { it.isNotBlank() }.joinToString(" · ")
        text.typeface = REGULAR
        text.textSize = 14f * s
        text.textAlign = Paint.Align.RIGHT
        val headW = run { text.typeface = BOLD; text.textSize = 16f * s; text.measureText(head) }
        text.typeface = REGULAR
        text.textSize = 14f * s
        c.drawText(fit(sub, totalW - headW - 30f * s), x0 + totalW - 10f * s, y0 + headerH * 0.72f, text)

        // Cuerpo
        val bodyTop = y0 + headerH
        fill.color = (0xE6 shl 24) or (NAVY and 0x00FFFFFF)
        rect.set(x0, bodyTop, x0 + totalW, bodyTop + rowH * 2)
        c.drawRoundRect(rect, r, r, fill)
        c.drawRect(x0, bodyTop, x0 + totalW, bodyTop + r, fill)

        val (pa, pb) = eng.pointLabels(st)
        for (i in 0..1) {
            val y = bodyTop + i * rowH
            val cy = y + rowH / 2f
            val baseline = cy + nameSize * 0.36f
            // Saque
            if (!st.finished && st.server == i) {
                fill.color = LIME
                c.drawCircle(x0 + dotW * 0.55f, cy, 6.5f * s, fill)
            }
            if (st.finished && st.winner == i) {
                fill.color = LIME
                c.drawRect(x0 + 6f * s, y + 8f * s, x0 + 11f * s, y + rowH - 8f * s, fill)
            }
            // Nombre
            text.typeface = BOLD
            text.textSize = nameSize
            text.textAlign = Paint.Align.LEFT
            text.color = Color.WHITE
            c.drawText(fit(if (i == 0) rs.teamA else rs.teamB, nameW - 12f * s), x0 + dotW, baseline, text)
            // Sets terminados
            var x = x0 + dotW + nameW
            text.textAlign = Paint.Align.CENTER
            for (set in st.sets) {
                val mine = if (i == 0) set.a else set.b
                val other = if (i == 0) set.b else set.a
                text.color = if (mine > other) Color.WHITE else GREY
                val v = if (set.superTb) (if (i == 0) set.tbA else set.tbB).toString() else mine.toString()
                c.drawText(v, x + setW / 2f, baseline, text)
                x += setW
            }
            if (!st.finished) {
                // Juegos del set actual
                fill.color = NAVY2
                c.drawRect(x, y + 3f * s, x + gameW, y + rowH - 3f * s, fill)
                text.color = Color.WHITE
                text.textSize = 25f * s
                val games = if (st.superTb) "-" else (if (i == 0) st.gamesA else st.gamesB).toString()
                c.drawText(games, x + gameW / 2f, cy + 25f * s * 0.36f, text)
                x += gameW
                // Puntos
                fill.color = LIME
                c.drawRect(x, y + 3f * s, x + ptsW, y + rowH - 3f * s, fill)
                text.color = NAVY
                c.drawText(if (i == 0) pa else pb, x + ptsW / 2f, cy + 25f * s * 0.36f, text)
            }
            if (i == 0) {
                stroke.color = (0x55 shl 24) or 0xFFFFFF
                stroke.strokeWidth = max(1f, 1f * s)
                c.drawLine(x0 + 8f * s, y + rowH, x0 + totalW - 8f * s, y + rowH, stroke)
            }
        }

        // Rótulo de presión (punto de oro, bola de set...)
        val badge = rs.badge
        if (badge != null) {
            text.typeface = BOLD
            text.textSize = 17f * s
            text.textAlign = Paint.Align.LEFT
            val bw = text.measureText(badge) + 28f * s
            val by = bodyTop + rowH * 2 + 8f * s
            fill.color = if (badge.startsWith("BOLA")) RED else LIME
            rect.set(x0, by, x0 + bw, by + 30f * s)
            c.drawRoundRect(rect, 15f * s, 15f * s, fill)
            text.color = if (badge.startsWith("BOLA")) Color.WHITE else NAVY
            c.drawText(badge, x0 + 14f * s, by + 21f * s, text)
        }
    }

    // ------------------------------------------------------------------ Esquinas

    private fun pill(c: Canvas, right: Float, top: Float, s: Float, label: String, bg: Int, fg: Int, dot: Boolean): Float {
        text.typeface = BOLD
        text.textSize = 17f * s
        text.textAlign = Paint.Align.LEFT
        val tw = text.measureText(label)
        val w = tw + 28f * s + if (dot) 18f * s else 0f
        rect.set(right - w, top, right, top + 32f * s)
        fill.color = bg
        c.drawRoundRect(rect, 16f * s, 16f * s, fill)
        var tx = right - w + 14f * s
        if (dot) {
            fill.color = if (bg == RED) Color.WHITE else RED
            c.drawCircle(tx + 5f * s, top + 16f * s, 5f * s, fill)
            tx += 18f * s
        }
        text.color = fg
        c.drawText(label, tx, top + 22f * s, text)
        return top + 40f * s
    }

    private fun drawTopRight(c: Canvas, w: Int, s: Float, rs: RenderState) {
        val right = w - 22f * s
        var top = 22f * s
        if (rs.live) top = pill(c, right, top, s, "EN DIRECTO", (0xCC shl 24) or (NAVY and 0xFFFFFF), Color.WHITE, true)
        val st = rs.status
        if (st != null) top = pill(c, right, top, s, st, RED, Color.WHITE, false)
        val cam = rs.camLabel
        if (cam != null) pill(c, right, top, s, cam, (0xCC shl 24) or (NAVY and 0xFFFFFF), LIME, false)
    }

    private fun drawWatermark(c: Canvas, w: Int, h: Int, s: Float) {
        val size = 30f * s
        val totalW = logoWidth(size)
        val alphaWas = fill.alpha
        drawLogo(c, w - 24f * s - totalW / 2f, h - 26f * s - size / 2f, size, onDark = true, alpha = 200)
        fill.alpha = alphaWas
    }

    // ------------------------------------------------------------------ Logo

    fun logoWidth(size: Float): Float {
        text.typeface = BOLD
        text.textSize = size * 0.62f
        return size + size * 0.25f + text.measureText("PADEL ") + text.measureText("GRABBER")
    }

    /** Logo: pelota + "PADEL GRABBER", centrado en (cx, cy). */
    fun drawLogo(c: Canvas, cx: Float, cy: Float, size: Float, onDark: Boolean, alpha: Int = 255) {
        val total = logoWidth(size)
        var x = cx - total / 2f
        val r = size / 2f
        // Pelota
        fill.color = if (onDark) LIME else NAVY
        fill.alpha = alpha
        c.drawCircle(x + r, cy, r, fill)
        stroke.color = if (onDark) Color.WHITE else LIME
        stroke.alpha = alpha
        stroke.strokeWidth = size * 0.07f
        rect.set(x - r * 0.9f, cy - r * 0.95f, x + r * 0.75f, cy + r * 0.95f)
        c.drawArc(rect, -60f, 120f, false, stroke)
        rect.set(x + r * 1.25f, cy - r * 0.95f, x + r * 2.9f, cy + r * 0.95f)
        c.drawArc(rect, 120f, 120f, false, stroke)
        x += size + size * 0.25f
        // Texto
        text.typeface = BOLD
        text.textSize = size * 0.62f
        text.textAlign = Paint.Align.LEFT
        val baseline = cy + text.textSize * 0.36f
        text.color = if (onDark) Color.WHITE else NAVY
        text.alpha = alpha
        c.drawText("PADEL ", x, baseline, text)
        x += text.measureText("PADEL ")
        text.color = if (onDark) LIME else Color.WHITE
        text.alpha = alpha
        c.drawText("GRABBER", x, baseline, text)
        text.alpha = 255
        fill.alpha = 255
        stroke.alpha = 255
    }

    // ------------------------------------------------------------------ Cortinillas

    private fun band(c: Canvas, left: Float, width: Float, h: Float, skew: Float, color: Int) {
        path.reset()
        path.moveTo(left + skew, 0f)
        path.lineTo(left + width + skew, 0f)
        path.lineTo(left + width, h)
        path.lineTo(left, h)
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    /**
     * Cortinilla con progreso p (0..1).
     * FULL: entra un panel azul en diagonal, enseña logo + textos y sale por la derecha.
     * QUICK: una franja lima cruza la pantalla con el logo.
     */
    fun drawCortinilla(c: Canvas, w: Int, h: Int, s: Float, title: String, subtitle: String, style: Int, p: Float) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val skew = hf * 0.35f

        if (style == Cortinilla.QUICK) {
            val e = easeInOut(p)
            val bw = wf * 0.75f
            val left = lerp(-bw - skew - wf * 0.1f, wf + wf * 0.1f, e)
            band(c, left - wf * 0.08f, wf * 0.08f, hf, skew, NAVY)
            band(c, left, bw, hf, skew, LIME)
            band(c, left + bw, wf * 0.03f, hf, skew, Color.WHITE)
            drawLogo(c, left + bw / 2f + skew / 2f, hf / 2f, 70f * s, onDark = false)
            return
        }

        val inP = (p / 0.16f).coerceIn(0f, 1f)
        val outP = ((p - 0.84f) / 0.16f).coerceIn(0f, 1f)
        val bw = wf + skew + 4f
        val left = if (outP <= 0f) lerp(-bw - skew, -skew, easeOut(inP)) else lerp(-skew, wf, easeIn(outP))
        // Franjas lima en los bordes del panel
        band(c, left - wf * 0.05f, wf * 0.05f, hf, skew, LIME)
        band(c, left + bw, wf * 0.05f, hf, skew, LIME)
        band(c, left, bw, hf, skew, NAVY)

        c.save()
        path.reset()
        path.moveTo(left + skew, 0f)
        path.lineTo(left + bw + skew, 0f)
        path.lineTo(left + bw, hf)
        path.lineTo(left, hf)
        path.close()
        c.clipPath(path)

        // Pista de pádel de fondo, muy suave
        stroke.color = Color.WHITE
        stroke.alpha = 22
        stroke.strokeWidth = 3f * s
        val cw = wf * 0.62f
        val ch = hf * 0.62f
        val cl = (wf - cw) / 2f
        val ctop = (hf - ch) / 2f
        rect.set(cl, ctop, cl + cw, ctop + ch)
        c.drawRect(rect, stroke)
        c.drawLine(wf / 2f, ctop, wf / 2f, ctop + ch, stroke)
        c.drawLine(cl + cw * 0.15f, ctop, cl + cw * 0.15f, ctop + ch, stroke)
        c.drawLine(cl + cw * 0.85f, ctop, cl + cw * 0.85f, ctop + ch, stroke)
        c.drawLine(cl + cw * 0.15f, hf / 2f, cl + cw * 0.85f, hf / 2f, stroke)
        stroke.alpha = 255

        // Textos, con un pequeño desplazamiento al entrar
        val contentAlpha = (255 * min(easeOut((p - 0.08f) / 0.12f), 1f - easeIn(outP))).toInt().coerceIn(0, 255)
        val dx = (1f - easeOut((p - 0.08f) / 0.2f)) * 60f * s
        if (contentAlpha > 0) {
            drawLogo(c, wf / 2f + dx, hf * 0.36f, 86f * s, onDark = true, alpha = contentAlpha)
            text.typeface = BOLD
            text.textAlign = Paint.Align.CENTER
            if (title.isNotBlank()) {
                text.textSize = 58f * s
                text.color = Color.WHITE
                text.alpha = contentAlpha
                c.drawText(fitCentered(title, wf * 0.86f), wf / 2f - dx, hf * 0.6f, text)
            }
            if (subtitle.isNotBlank()) {
                text.textSize = 32f * s
                text.color = LIME
                text.alpha = contentAlpha
                c.drawText(fitCentered(subtitle, wf * 0.86f), wf / 2f + dx, hf * 0.7f, text)
            }
            text.alpha = 255
            // Línea lima bajo el logo
            fill.color = LIME
            fill.alpha = contentAlpha
            val lw = 160f * s * easeOut((p - 0.1f) / 0.2f)
            c.drawRect(wf / 2f - lw / 2f, hf * 0.45f, wf / 2f + lw / 2f, hf * 0.45f + 4f * s, fill)
            fill.alpha = 255
        }
        c.restore()
    }

    private fun fitCentered(str: String, maxW: Float): String {
        val a = text.textAlign
        text.textAlign = Paint.Align.LEFT
        val out = fit(str, maxW)
        text.textAlign = a
        return out
    }

    // ------------------------------------------------------------------ Piezas para el montaje

    /** Rótulo de repetición arriba a la derecha. */
    fun drawReplayBadge(c: Canvas, w: Int, s: Float, label: String) {
        pill(c, w - 22f * s, 22f * s, s, label, LIME, NAVY, false)
    }

    /** Rótulo inferior con título y texto. */
    fun drawLowerThird(c: Canvas, w: Int, h: Int, s: Float, title: String, subtitle: String, alpha: Int) {
        val x = 40f * s
        val bottom = h - 70f * s
        text.typeface = BOLD
        text.textSize = 30f * s
        text.textAlign = Paint.Align.LEFT
        val tw = text.measureText(title)
        text.textSize = 22f * s
        val sw = text.measureText(subtitle)
        val boxW = max(tw, sw) + 40f * s
        fill.color = NAVY
        fill.alpha = (alpha * 0.92f).toInt()
        rect.set(x, bottom - 86f * s, x + boxW, bottom)
        c.drawRect(rect, fill)
        fill.color = LIME
        fill.alpha = alpha
        c.drawRect(x, bottom - 86f * s, x + 8f * s, bottom, fill)
        text.textSize = 30f * s
        text.color = Color.WHITE
        text.alpha = alpha
        c.drawText(title, x + 24f * s, bottom - 50f * s, text)
        text.textSize = 22f * s
        text.color = LIME
        text.alpha = alpha
        c.drawText(subtitle, x + 24f * s, bottom - 18f * s, text)
        text.alpha = 255
        fill.alpha = 255
    }
}

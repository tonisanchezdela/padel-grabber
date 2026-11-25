package com.padelgrabber.training

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.padelgrabber.util.Ui
import kotlin.math.min

/** Dibuja el esqueleto del jugador encima de la imagen (la imagen se ve en modo "encajar"). */
class SkeletonView(ctx: Context) : View(ctx) {

    @Volatile var frame: PoseFrame? = null
        set(v) { field = v; postInvalidate() }
    var mirror = false
    var rightHanded = true
    var showAngles = true

    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.BOLD
        color = Ui.NAVY
    }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LIME }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        if (f.width <= 0 || f.height <= 0) return
        val scale = min(width.toFloat() / f.width, height.toFloat() / f.height)
        val ox = (width - f.width * scale) / 2f
        val oy = (height - f.height * scale) / 2f
        fun x(p: Pt) = if (mirror) ox + (f.width - p.x) * scale else ox + p.x * scale
        fun y(p: Pt) = oy + p.y * scale

        val dp = resources.displayMetrics.density
        bone.strokeWidth = 4f * dp
        val domArm = if (rightHanded) setOf(J.R_SH, J.R_EL, J.R_WR) else setOf(J.L_SH, J.L_EL, J.L_WR)
        for (b in J.BONES) {
            val a = f[b[0]] ?: continue
            val c = f[b[1]] ?: continue
            bone.color = if (b[0] in domArm && b[1] in domArm) Ui.LIME else 0xDDFFFFFF.toInt()
            canvas.drawLine(x(a), y(a), x(c), y(c), bone)
        }
        for (i in 0 until J.COUNT) {
            val p = f[i] ?: continue
            joint.color = if (i in domArm) Ui.LIME else Color.WHITE
            canvas.drawCircle(x(p), y(p), 5f * dp, joint)
        }
        if (!showAngles) return
        text.textSize = 13f * dp
        fun label(at: Pt?, value: Float?) {
            if (at == null || value == null) return
            val s = "${value.toInt()}°"
            val tw = text.measureText(s)
            val lx = x(at) + 8f * dp
            val ly = y(at) - 8f * dp
            canvas.drawRoundRect(lx - 4f * dp, ly - 14f * dp, lx + tw + 4f * dp, ly + 4f * dp, 6f * dp, 6f * dp, tag)
            canvas.drawText(s, lx, ly, text)
        }
        val sh = if (rightHanded) J.R_SH else J.L_SH
        val el = if (rightHanded) J.R_EL else J.L_EL
        val wr = if (rightHanded) J.R_WR else J.L_WR
        label(f[el], Geo.angle(f[sh], f[el], f[wr]))
        label(f[J.L_KN], Geo.angle(f[J.L_HIP], f[J.L_KN], f[J.L_AN]))
        label(f[J.R_KN], Geo.angle(f[J.R_HIP], f[J.R_KN], f[J.R_AN]))
    }
}

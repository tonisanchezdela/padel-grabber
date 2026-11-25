package com.padelgrabber.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.padelgrabber.net.JFrame
import com.padelgrabber.util.Ui
import com.padelgrabber.util.dp

/**
 * Pantalla de videoarbitraje: repite los últimos segundos desde todas las cámaras a la vez,
 * con cámara lenta, pausa y barra para moverse.
 */
class ReviewPanel(ctx: Context, private val onClose: () -> Unit) : FrameLayout(ctx) {

    class Angle(val name: String, val frames: List<JFrame>)

    private val grid = GridLayout(ctx)
    private val seek = SeekBar(ctx)
    private val title = TextView(ctx)
    private val timeLabel = TextView(ctx)
    private val playBtn = Button(ctx)
    private val speedBtn = Button(ctx)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var angles: List<Angle> = emptyList()
    private val views = ArrayList<ImageView>()
    private val shown = ArrayList<JFrame?>()
    private var t0 = 0L
    private var t1 = 0L
    private var cursor = 0L
    private var playing = true
    private var speed = 0.5f
    private var lastTick = 0L
    private var focused = -1

    init {
        setBackgroundColor(0xF00B1D33.toInt())
        isClickable = true
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        title.text = "VIDEOARBITRAJE"
        title.setTextColor(Ui.RED)
        title.typeface = Ui.BOLD
        title.textSize = 22f
        col.addView(title)

        grid.alignmentMode = GridLayout.ALIGN_BOUNDS
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun style(b: Button, txt: String, primary: Boolean) {
            b.text = txt
            b.isAllCaps = false
            b.typeface = Ui.BOLD
            b.textSize = 16f
            b.setTextColor(if (primary) Ui.NAVY else Ui.WHITE)
            b.background = Ui.rounded(if (primary) Ui.LIME else Ui.NAVY3, ctx.dp(12).toFloat())
            b.setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        }
        style(playBtn, "Pausa", false)
        style(speedBtn, "Lento ×0,5", false)
        val close = Button(ctx)
        style(close, "Seguir el partido", true)
        playBtn.setOnClickListener { playing = !playing; playBtn.text = if (playing) "Pausa" else "Play" }
        speedBtn.setOnClickListener {
            speed = when (speed) { 0.5f -> 0.25f; 0.25f -> 1f; else -> 0.5f }
            speedBtn.text = when (speed) { 0.25f -> "Lento ×0,25"; 1f -> "Normal"; else -> "Lento ×0,5" }
        }
        close.setOnClickListener { hide() }
        timeLabel.setTextColor(Ui.WHITE)
        timeLabel.textSize = 15f
        timeLabel.setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) { cursor = t0 + (t1 - t0) * p / 1000; render() }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { playing = false; playBtn.text = "Play" }
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        val bh = ctx.dp(48)
        bar.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bh))
        bar.addView(speedBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bh).apply { leftMargin = ctx.dp(8) })
        bar.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(timeLabel)
        bar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bh))
        visibility = View.GONE
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

    fun show(list: List<Angle>, reason: String) {
        angles = list.filter { it.frames.size >= 2 }
        if (angles.isEmpty()) return
        title.text = "VIDEOARBITRAJE · $reason"
        t0 = angles.minOf { it.frames.first().t }
        t1 = angles.maxOf { it.frames.last().t }
        cursor = t0
        playing = true
        speed = 0.5f
        speedBtn.text = "Lento ×0,5"
        playBtn.text = "Pausa"
        focused = -1
        buildGrid()
        visibility = View.VISIBLE
        lastTick = SystemClock.uptimeMillis()
        uiHandler.removeCallbacks(tick)
        uiHandler.post(tick)
    }

    fun hide() {
        if (!isOpen) return
        uiHandler.removeCallbacks(tick)
        visibility = View.GONE
        views.forEach { it.setImageDrawable(null) }
        angles = emptyList()
        onClose()
    }

    private fun buildGrid() {
        grid.removeAllViews()
        views.clear()
        shown.clear()
        val visible = if (focused >= 0) listOf(focused) else angles.indices.toList()
        val cols = if (visible.size <= 1) 1 else 2
        val rows = (visible.size + cols - 1) / cols
        grid.columnCount = cols
        grid.rowCount = rows
        for ((pos, idx) in visible.withIndex()) {
            val cell = FrameLayout(context)
            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setBackgroundColor(0xFF000000.toInt())
            cell.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val lbl = TextView(context)
            lbl.text = angles[idx].name
            lbl.setTextColor(Ui.NAVY)
            lbl.typeface = Ui.BOLD
            lbl.background = Ui.rounded(Ui.LIME, context.dp(8).toFloat())
            lbl.setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
            cell.addView(lbl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dp(6); topMargin = context.dp(6)
            })
            cell.setOnClickListener {
                focused = if (focused >= 0) -1 else idx
                buildGrid()
                render()
            }
            val p = GridLayout.LayoutParams(
                GridLayout.spec(pos / cols, 1, 1f),
                GridLayout.spec(pos % cols, 1, 1f)
            )
            p.width = 0
            p.height = 0
            p.setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            grid.addView(cell, p)
            views.add(iv)
            shown.add(null)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = now - lastTick
            lastTick = now
            if (playing) {
                cursor += (dt * speed).toLong()
                if (cursor > t1) cursor = t0
                seek.progress = if (t1 > t0) ((cursor - t0) * 1000 / (t1 - t0)).toInt() else 0
            }
            render()
            uiHandler.postDelayed(this, 40)
        }
    }

    private fun render() {
        val visible = if (focused >= 0) listOf(focused) else angles.indices.toList()
        val sample = if (visible.size > 1) 2 else 1
        for ((pos, idx) in visible.withIndex()) {
            if (pos >= views.size) break
            val f = frameAt(angles[idx].frames, cursor) ?: continue
            if (shown[pos] === f) continue
            shown[pos] = f
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp: Bitmap? = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size, opts)
            if (bmp != null) views[pos].setImageBitmap(bmp)
        }
        val sec = (cursor - t0) / 1000.0
        val total = (t1 - t0) / 1000.0
        timeLabel.text = String.format("%.1f / %.1f s", sec, total)
    }

    private fun frameAt(frames: List<JFrame>, t: Long): JFrame? {
        if (frames.isEmpty()) return null
        var lo = 0
        var hi = frames.size - 1
        if (t <= frames[0].t) return frames[0]
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (frames[mid].t <= t) lo = mid else hi = mid - 1
        }
        return frames[lo]
    }
}

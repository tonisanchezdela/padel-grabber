package com.padelgrabber.training

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.choice
import com.padelgrabber.util.dp
import com.padelgrabber.util.label
import com.padelgrabber.util.lp
import com.padelgrabber.util.screen
import com.padelgrabber.util.title
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Analiza un vídeo de la galería fotograma a fotograma. */
class VideoAnalysisActivity : ComponentActivity() {

    private class Item(val t: Long, val jpeg: ByteArray, val pose: PoseFrame)

    private var stroke: Stroke? = null
    private val items = ArrayList<Item>()
    private val results = ArrayList<StrokeResult>()
    @Volatile private var cancelled = false
    private val handler = Handler(Looper.getMainLooper())
    private var playing = false

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyze(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showStart()
    }

    override fun onDestroy() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showStart() {
        screen {
            title("Analizar un vídeo")
            label("Elige un vídeo corto (hasta 40 s) en el que se vea al jugador de cuerpo entero. Mejor grabado de lado.")
            label("¿Qué golpe es?")
            val names = listOf("Automático") + Stroke.values().map { it.title }
            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            addView(col, lp(0))
            // Dos filas de opciones para que quepan
            val first = names.take(4)
            val second = names.drop(4)
            var firstRow: LinearLayout? = null
            var secondRow: LinearLayout? = null
            fun pick(i: Int) { stroke = if (i == 0) null else Stroke.values()[i - 1] }
            firstRow = col.choice(first, 0) { i -> pick(i); secondRow?.let { repaint(it, -1) } }
            secondRow = col.choice(second, -1) { i -> pick(i + 4); firstRow?.let { repaint(it, -1) } }
            button("Elegir vídeo", primary = true) { picker.launch("video/*") }
        }
    }

    /** Quita la selección visual de una fila de opciones. */
    private fun repaint(row: LinearLayout, sel: Int) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i) as? TextView ?: continue
            v.background = Ui.rounded(if (i == sel) Ui.LIME else Ui.NAVY2, dp(10).toFloat())
            v.setTextColor(if (i == sel) Ui.NAVY else Ui.WHITE)
        }
    }

    private fun analyze(uri: Uri) {
        var status: TextView? = null
        var bar: ProgressBar? = null
        screen {
            title("Analizando…")
            status = label("Leyendo el vídeo", 16f, Ui.LIME)
            val pb = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 100
            addView(pb, lp(8))
            bar = pb
            button("Cancelar") { cancelled = true; finish() }
        }
        items.clear()
        results.clear()
        val right = TrainingStore.rightHanded(this)
        val swing = SwingAnalyzer(right, stroke) { r -> results.add(r) }
        thread(name = "pg-video-analysis") {
            val mmr = MediaMetadataRetriever()
            val detector = PoseDetection.getClient(
                PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
            )
            var error: String? = null
            try {
                mmr.setDataSource(this, uri)
                val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val total = dur.coerceAtMost(40_000)
                val step = 66L
                var t = 0L
                val out = ByteArrayOutputStream()
                while (t <= total && !cancelled) {
                    val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                        mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                    } else {
                        mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { full ->
                            val s = 720f / maxOf(full.width, full.height)
                            if (s < 1f) Bitmap.createScaledBitmap(full, (full.width * s).toInt(), (full.height * s).toInt(), true) else full
                        }
                    }
                    if (bmp != null) {
                        val pose = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
                        val pf = PoseFrame.from(pose, t, bmp.width, bmp.height)
                        out.reset()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        items.add(Item(t, out.toByteArray(), pf))
                        swing.add(pf)
                        bmp.recycle()
                    }
                    val pct = if (total > 0) (t * 100 / total).toInt() else 100
                    val tt = t
                    runOnUiThread {
                        bar?.progress = pct
                        status?.text = "Analizando ${tt / 1000} s de ${total / 1000} s · ${results.size} golpes"
                    }
                    t += step
                }
            } catch (e: Exception) {
                error = e.message ?: "error"
            } finally {
                try { mmr.release() } catch (_: Exception) {}
                detector.close()
            }
            val err = error
            runOnUiThread {
                if (cancelled) return@runOnUiThread
                if (items.isEmpty()) {
                    Ui.toast(this, "No se pudo leer el vídeo${if (err != null) ": $err" else ""}")
                    showStart()
                } else {
                    TrainingStore.save(this, System.currentTimeMillis(), "video", results)
                    showResults()
                }
            }
        }
    }

    private fun showResults() {
        val first = items.first()
        val ratio = first.pose.height.toFloat() / first.pose.width.coerceAtLeast(1)
        lateinit var image: ImageView
        lateinit var skel: SkeletonView
        lateinit var seek: SeekBar
        lateinit var timeLbl: TextView
        fun show(i: Int) {
            val it = items[i.coerceIn(0, items.size - 1)]
            BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size)?.let { b -> image.setImageBitmap(b) }
            skel.frame = it.pose
            timeLbl.text = String.format("%.2f s", it.t / 1000.0)
        }
        screen {
            title("Resultado")
            val stage = FrameLayout(context)
            image = ImageView(context)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setBackgroundColor(0xFF000000.toInt())
            stage.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            skel = SkeletonView(context)
            skel.rightHanded = TrainingStore.rightHanded(context)
            stage.addView(skel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val w = resources.displayMetrics.widthPixels - context.dp(40)
            addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (w * ratio).toInt().coerceIn(context.dp(180), context.dp(560))))
            seek = SeekBar(context)
            seek.max = items.size - 1
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { show(p) }
                override fun onStartTrackingTouch(s: SeekBar) { playing = false }
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            addView(seek, lp(8))
            timeLbl = label("", 14f, Ui.GREY)
            button("▶ Reproducir a cámara lenta") {
                playing = !playing
                if (playing) handler.post(object : Runnable {
                    override fun run() {
                        if (!playing) return
                        val next = seek.progress + 1
                        if (next >= items.size) { playing = false; return }
                        seek.progress = next
                        handler.postDelayed(this, 180)
                    }
                })
            }

            if (results.isEmpty()) {
                label("No se ha detectado ningún golpe. Prueba con un vídeo en el que se vea entero el brazo que golpea.", 15f, Ui.WHITE)
            } else {
                val avg = results.map { it.score }.average().toInt()
                title("${results.size} golpes · media $avg/100", 20f, ResultCard.scoreColor(avg))
                results.forEachIndexed { n, r ->
                    val card = ResultCard.view(context, r)
                    card.setOnClickListener {
                        val idx = items.indexOfFirst { it.t >= r.t }.coerceAtLeast(0)
                        seek.progress = idx
                        show(idx)
                    }
                    addView(card, lp(10))
                    label("Golpe ${n + 1} a los ${String.format("%.1f", r.t / 1000.0)} s · toca la tarjeta para verlo", 12f, Ui.GREY)
                }
            }
            button("Analizar otro vídeo") { showStart() }
        }
        val firstHit = results.firstOrNull()?.let { r -> items.indexOfFirst { it.t >= r.t } } ?: 0
        seek.progress = firstHit.coerceAtLeast(0)
        show(firstHit.coerceAtLeast(0))
    }
}

package com.padelgrabber.training

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.pose.Pose
import com.padelgrabber.analysis.FrameAnalyzer
import com.padelgrabber.util.Ui
import com.padelgrabber.util.dp
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Análisis de golpes en directo: el móvil mira al jugador, dibuja su esqueleto
 * y puntúa cada golpe que detecta.
 */
class LiveTrainingActivity : ComponentActivity(), FrameAnalyzer.Listener {

    companion object {
        const val EXTRA_STROKE = "stroke"
    }

    private lateinit var preview: PreviewView
    private lateinit var skeleton: SkeletonView
    private lateinit var hint: TextView
    private lateinit var counter: TextView
    private lateinit var resultBox: FrameLayout
    private lateinit var chipsRow: LinearLayout
    private val analyzer = FrameAnalyzer(this)
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var front = false
    private val results = ArrayList<StrokeResult>()
    private val sessionStart = System.currentTimeMillis()
    private var lastBodySeen = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var selected: Stroke? = null

    private val swing by lazy {
        SwingAnalyzer(TrainingStore.rightHanded(this), selected) { r -> runOnUiThread { onStroke(r) } }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) bind() else { Ui.toast(this, "Hace falta la cámara"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = Stroke.byName(intent.getStringExtra(EXTRA_STROKE))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analyzer.jpegEnabled = false
        analyzer.poseEnabled = true
        analyzer.poseEvery = 1
        analyzer.minIntervalMs = 33
        buildUi()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.setLanguage(Locale("es", "ES"))
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bind()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        TrainingStore.save(this, sessionStart, "directo", results)
        provider?.unbindAll()
        analyzer.close()
        executor.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        preview = PreviewView(this)
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        skeleton = SkeletonView(this)
        skeleton.rightHanded = TrainingStore.rightHanded(this)
        root.addView(skeleton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(dp(10), dp(28), dp(10), dp(6))
        top.setBackgroundColor(0x990B1D33.toInt())
        val scroll = HorizontalScrollView(this)
        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        scroll.addView(chipsRow)
        top.addView(scroll)
        counter = TextView(this)
        counter.setTextColor(Ui.WHITE)
        counter.typeface = Ui.BOLD
        counter.textSize = 15f
        counter.setPadding(0, dp(6), 0, 0)
        top.addView(counter)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        buildChips()

        hint = TextView(this)
        hint.setTextColor(Ui.NAVY)
        hint.typeface = Ui.BOLD
        hint.textSize = 16f
        hint.gravity = Gravity.CENTER
        hint.background = Ui.rounded(Ui.LIME, dp(12).toFloat())
        hint.setPadding(dp(14), dp(10), dp(14), dp(10))
        hint.text = "Colócate de cuerpo entero dentro de la imagen"
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        resultBox = FrameLayout(this)
        root.addView(resultBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(80)
        })

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        fun b(text: String, primary: Boolean, onClick: () -> Unit) {
            val btn = Button(this)
            btn.text = text
            btn.isAllCaps = false
            btn.typeface = Ui.BOLD
            btn.setTextColor(if (primary) Ui.NAVY else Ui.WHITE)
            btn.background = Ui.rounded(if (primary) Ui.LIME else 0xCC132B4A.toInt(), dp(12).toFloat())
            btn.setPadding(dp(16), 0, dp(16), 0)
            btn.setOnClickListener { onClick() }
            bar.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { leftMargin = dp(6); rightMargin = dp(6) })
        }
        b("Girar cámara", false) { front = !front; bind() }
        b("Terminar", true) { finish() }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(16)
        })
        setContentView(root)
        updateCounter()
    }

    private fun buildChips() {
        chipsRow.removeAllViews()
        val options = listOf<Stroke?>(null) + Stroke.values().toList()
        for (s in options) {
            val t = TextView(this)
            t.text = s?.title ?: "Automático"
            t.typeface = Ui.BOLD
            t.textSize = 14f
            val sel = s == selected
            t.setTextColor(if (sel) Ui.NAVY else Ui.WHITE)
            t.background = Ui.rounded(if (sel) Ui.LIME else Ui.NAVY3, dp(16).toFloat())
            t.setPadding(dp(12), dp(6), dp(12), dp(6))
            t.setOnClickListener {
                selected = s
                swing.fixedStroke = s
                buildChips()
            }
            chipsRow.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
    }

    private fun bind() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val rot = preview.display?.rotation ?: 0
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
                val pv = Preview.Builder().setResolutionSelector(selector).setTargetRotation(rot).build()
                pv.setSurfaceProvider(preview.surfaceProvider)
                val an = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(rot)
                    .build()
                an.setAnalyzer(executor, analyzer)
                p.unbindAll()
                p.bindToLifecycle(
                    this,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    pv, an
                )
                skeleton.mirror = front
                swing.reset()
            } catch (e: Exception) {
                Ui.toast(this, "No se pudo abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onPose(pose: Pose, width: Int, height: Int, wallMs: Long) {
        val f = PoseFrame.from(pose, wallMs, width, height)
        skeleton.frame = f
        if (f.hasBody) lastBodySeen = SystemClock.uptimeMillis()
        swing.add(f)
        runOnUiThread {
            hint.visibility = if (SystemClock.uptimeMillis() - lastBodySeen > 1500) View.VISIBLE else View.GONE
        }
    }

    private fun onStroke(r: StrokeResult) {
        results.add(r)
        resultBox.removeAllViews()
        resultBox.addView(ResultCard.view(this, r, compact = true))
        updateCounter()
        if (ttsReady) {
            val tip = r.tips.firstOrNull() ?: ""
            tts?.speak("${r.stroke.title}, ${r.score}. $tip", TextToSpeech.QUEUE_FLUSH, null, "pg")
        }
    }

    private fun updateCounter() {
        if (results.isEmpty()) {
            counter.text = "Golpea normal: la app detecta cada golpe sola."
            return
        }
        val avg = results.map { it.score }.average().toInt()
        counter.text = "Golpes: ${results.size} · media $avg/100 · mejor ${results.maxOf { it.score }}/100"
    }
}

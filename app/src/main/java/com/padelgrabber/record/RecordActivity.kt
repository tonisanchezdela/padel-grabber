package com.padelgrabber.record

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.pose.Pose
import com.padelgrabber.analysis.ArmGestureDetector
import com.padelgrabber.analysis.FrameAnalyzer
import com.padelgrabber.analysis.MotionTracker
import com.padelgrabber.analysis.VideoClock
import com.padelgrabber.match.Marker
import com.padelgrabber.match.Match
import com.padelgrabber.match.MatchStore
import com.padelgrabber.match.PointRec
import com.padelgrabber.net.Discovery
import com.padelgrabber.net.FrameHub
import com.padelgrabber.net.JFrame
import com.padelgrabber.net.LiveServer
import com.padelgrabber.overlay.Cortinilla
import com.padelgrabber.overlay.RenderState
import com.padelgrabber.review.ReviewPanel
import com.padelgrabber.score.Ev
import com.padelgrabber.score.ScoreEngine
import com.padelgrabber.score.ScoreState
import com.padelgrabber.score.SetScore
import com.padelgrabber.ui.MatchDetailActivity
import com.padelgrabber.util.NetUtil
import com.padelgrabber.util.Prefs
import com.padelgrabber.util.Ui
import com.padelgrabber.util.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de grabación del móvil principal: cámara, marcador, autopausa,
 * videoarbitraje y realización del directo por wifi.
 */
class RecordActivity : ComponentActivity(), FrameAnalyzer.Listener, LiveServer.Host {

    companion object {
        const val EXTRA_ID = "match_id"
        const val LIVE_PORT = 8080
        private const val VIDEO_NAME = "partido.mp4"
    }

    private enum class Phase { READY, PLAYING, PAUSED, FINISHED }

    private lateinit var prefs: Prefs
    private lateinit var match: Match
    private lateinit var engine: ScoreEngine
    @Volatile private var score: ScoreState = ScoreState()
    private val history = ArrayList<ScoreState>()

    private lateinit var previewView: PreviewView
    private var camera: CameraController? = null
    private val analyzer = FrameAnalyzer(this)
    private lateinit var motion: MotionTracker
    private val clock = VideoClock()
    private val gesture = ArmGestureDetector { runOnUiThread { requestReview("gesto en cámara principal") } }
    private var live: LiveServer? = null
    private var discovery: Discovery? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var phase = Phase.READY
    @Volatile private var renderState: RenderState? = null
    @Volatile private var cortinilla: Cortinilla? = null
    @Volatile private var statusText: String? = null
    private var autoResume = false
    private var pausedAt = 0L
    private var minRestMs = 0L
    private var maxRestMs = Long.MAX_VALUE
    private var pendingPause: Runnable? = null
    private var pendingStop: Runnable? = null
    private var lastPointVideo = 0L
    private var bestClip: List<JFrame> = emptyList()
    private var bestClipDur = 0L
    private var lastKeyAt = 0L
    private var lastRallyStart = -1L
    private var lastRallyEnd = -1L
    private var rallyCount = 0
    private var lastPointCortAt = 0L
    @Volatile private var cortSeq = 0
    private var zoom = 1f

    // Vistas
    private lateinit var recLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var netLabel: TextView
    private lateinit var gestureLabel: TextView
    private lateinit var startBtn: Button
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var pauseBtn: Button
    private lateinit var controls: LinearLayout
    private lateinit var camStrip: LinearLayout
    private lateinit var camScroll: HorizontalScrollView
    private lateinit var review: ReviewPanel
    private val thumbViews = HashMap<String, ImageView>()
    private val thumbShown = HashMap<String, JFrame>()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) startCamera() else {
            Ui.toast(this, "Sin permiso de cámara no se puede grabar")
            finish()
        }
    }

    // ------------------------------------------------------------------ Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val m = if (id != null) MatchStore.load(this, id) else null
        if (m == null) {
            Ui.toast(this, "No se encontró el partido")
            finish()
            return
        }
        match = m
        engine = ScoreEngine(match.config)
        score = engine.initial()
        motion = MotionTracker(prefs.motionSensitivity)
        motion.onRally = { s, e -> handler.post { onRallyDetected(s, e) } }
        analyzer.jpegEnabled = true // también hace falta para el videoarbitraje
        analyzer.poseEnabled = prefs.gestureReview
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        buildUi()
        FrameHub.reset()
        if (prefs.liveWifi) startLive()
        updateRender()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else permissions.launch(need)
        handler.post(ticker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        camera?.release()
        camera = null
        live?.stop()
        discovery?.close()
        analyzer.close()
        FrameHub.stopReplay()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startCamera() {
        val cam = CameraController(this, this, previewView, prefs.fullHd, { renderState }, analyzer)
        camera = cam
        cam.start { ok, msg ->
            if (!ok) {
                Ui.toast(this, msg)
                return@start
            }
            if (msg != "Cámara lista") Ui.toast(this, msg)
            startBtn.isEnabled = true
            startBtn.text = "EMPEZAR A GRABAR"
        }
    }

    private fun startLive() {
        val html = try {
            assets.open("viewer.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<h1>Padel Grabber</h1>"
        }
        val server = LiveServer(LIVE_PORT, html, { prefs.pin }, this)
        server.start()
        live = server
        val d = Discovery(this)
        d.register("PadelGrabber ${match.teamA} vs ${match.teamB}".take(60), LIVE_PORT)
        discovery = d
    }

    // ------------------------------------------------------------------ Interfaz

    private fun ctrl(text: String, bg: Int, fg: Int = Ui.WHITE, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.typeface = Ui.BOLD
        b.textSize = 15f
        b.setTextColor(fg)
        b.background = Ui.rounded(bg, dp(12).toFloat())
        b.setPadding(dp(14), 0, dp(14), 0)
        b.minWidth = dp(64)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        previewView = PreviewView(this)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Información arriba a la derecha (no se graba: solo se ve en pantalla)
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.END
        recLabel = TextView(this).apply { setTextColor(Ui.WHITE); typeface = Ui.BOLD; textSize = 16f }
        statusLabel = TextView(this).apply { setTextColor(Ui.LIME); typeface = Ui.BOLD; textSize = 14f }
        netLabel = TextView(this).apply { setTextColor(Ui.GREY); textSize = 12f; gravity = Gravity.END }
        gestureLabel = TextView(this).apply { setTextColor(Ui.RED); typeface = Ui.BOLD; textSize = 15f }
        info.addView(recLabel)
        info.addView(statusLabel)
        info.addView(netLabel)
        info.addView(gestureLabel)
        info.setPadding(dp(10), dp(8), dp(10), dp(8))
        info.background = Ui.rounded(0x99000000.toInt(), dp(10).toFloat())
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(96); rightMargin = dp(12)
        })

        // Tira de cámaras (realización)
        camScroll = HorizontalScrollView(this)
        camStrip = LinearLayout(this)
        camStrip.orientation = LinearLayout.HORIZONTAL
        camScroll.addView(camStrip)
        camScroll.visibility = View.GONE
        root.addView(camScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8)
        })

        // Zoom
        val zoomCol = LinearLayout(this)
        zoomCol.orientation = LinearLayout.VERTICAL
        zoomCol.addView(ctrl("+", 0xAA132B4A.toInt()) { changeZoom(0.25f) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        zoomCol.addView(ctrl("−", 0xAA132B4A.toInt()) { changeZoom(-0.25f) }, LinearLayout.LayoutParams(dp(52), dp(48)).apply { topMargin = dp(8) })
        root.addView(zoomCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            rightMargin = dp(12)
        })

        // Botones de punto
        btnA = ctrl("+1\n${match.teamA}", 0x99C6FF00.toInt(), Ui.NAVY) { addPoint(0) }
        btnB = ctrl("+1\n${match.teamB}", 0x99C6FF00.toInt(), Ui.NAVY) { addPoint(1) }
        btnA.textSize = 20f
        btnB.textSize = 20f
        root.addView(btnA, FrameLayout.LayoutParams(dp(150), dp(120), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16); bottomMargin = dp(16)
        })
        root.addView(btnB, FrameLayout.LayoutParams(dp(150), dp(120), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16); bottomMargin = dp(16)
        })

        // Controles centrales
        controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        val h = dp(48)
        fun add(b: Button) {
            controls.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h).apply { leftMargin = dp(6) })
        }
        add(ctrl("↶", 0xCC132B4A.toInt()) { undo() })
        pauseBtn = ctrl("Pausa", 0xCC132B4A.toInt()) { togglePause() }
        add(pauseBtn)
        add(ctrl("VAR", 0xCCFF3B30.toInt()) { requestReview("pedido en el móvil principal") })
        add(ctrl("Cámaras", 0xCC132B4A.toInt()) { toggleCams() })
        add(ctrl("Directo", 0xCC132B4A.toInt()) { showLiveInfo() })
        add(ctrl("Fin", 0xCC1D3A60.toInt()) { confirmStop() })
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16)
        })

        // Botón de empezar
        startBtn = ctrl("Abriendo cámara…", Ui.LIME, Ui.NAVY) { startRecording() }
        startBtn.textSize = 22f
        startBtn.isEnabled = false
        root.addView(startBtn, FrameLayout.LayoutParams(dp(300), dp(72), Gravity.CENTER))

        review = ReviewPanel(this) { onReviewClosed() }
        root.addView(review, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        updateButtons()
    }

    private fun updateButtons() {
        val recording = phase == Phase.PLAYING || phase == Phase.PAUSED
        startBtn.visibility = if (phase == Phase.READY) View.VISIBLE else View.GONE
        btnA.visibility = if (recording) View.VISIBLE else View.GONE
        btnB.visibility = if (recording) View.VISIBLE else View.GONE
        pauseBtn.text = if (phase == Phase.PAUSED) "Seguir" else "Pausa"
        controls.alpha = if (recording) 1f else 0.6f
    }

    private fun changeZoom(delta: Float) {
        val cam = camera ?: return
        zoom = (zoom + delta).coerceIn(1f, cam.maxZoom().coerceAtLeast(1f))
        cam.setZoom(zoom)
        Ui.toast(this, String.format(Locale.US, "Zoom ×%.2f", zoom))
    }

    private fun toggleCams() {
        camScroll.visibility = if (camScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshThumbs(force = true)
    }

    private fun showLiveInfo() {
        val ip = NetUtil.localIp()
        if (live == null || ip == null) {
            Ui.toast(this, if (live == null) "El directo por wifi está apagado en Ajustes" else "Conéctate a la wifi del club")
            return
        }
        val url = "http://$ip:$LIVE_PORT/"
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(20), dp(16), dp(20), dp(8))
        NetUtil.qr(url, 600)?.let { bmp ->
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            col.addView(iv, LinearLayout.LayoutParams(dp(220), dp(220)))
        }
        val t = TextView(this)
        t.text = "Escanea para ver el directo:\n$url\n\nPIN para apuntar puntos: ${prefs.pin}\n" +
            "Los móviles con «Cámara extra» se conectan solos."
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        col.addView(t)
        AlertDialog.Builder(this).setTitle("Directo en la wifi del club").setView(col)
            .setPositiveButton("Cerrar", null).show()
    }

    private fun refreshThumbs(force: Boolean = false) {
        if (camScroll.visibility != View.VISIBLE) return
        val srcs = FrameHub.sources.values.sortedBy { if (it.id == FrameHub.MAIN) "" else it.name }
        val ids = srcs.map { it.id }.toSet()
        if (force || ids != thumbViews.keys) {
            camStrip.removeAllViews()
            thumbViews.clear()
            thumbShown.clear()
            for (s in srcs) {
                val cell = FrameLayout(this)
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                cell.addView(iv, FrameLayout.LayoutParams(dp(160), dp(90)))
                val lbl = TextView(this)
                lbl.text = if (s.id == FrameHub.MAIN) "Principal" else s.name
                lbl.setTextColor(Ui.WHITE)
                lbl.typeface = Ui.BOLD
                lbl.textSize = 12f
                lbl.setBackgroundColor(0x99000000.toInt())
                lbl.setPadding(dp(4), dp(1), dp(4), dp(1))
                cell.addView(lbl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                cell.setPadding(dp(3), dp(3), dp(3), dp(3))
                cell.setOnClickListener {
                    FrameHub.onAir = s.id
                    Ui.toast(this, "En antena: ${lbl.text}")
                    refreshThumbs(force = true)
                }
                camStrip.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
                thumbViews[s.id] = iv
            }
        }
        for (s in srcs) {
            val iv = thumbViews[s.id] ?: continue
            (iv.parent as? View)?.background = if (FrameHub.onAir == s.id) Ui.rounded(Ui.LIME, dp(6).toFloat()) else null
            iv.alpha = if (s.live) 1f else 0.35f
            val f = s.latest ?: continue
            if (thumbShown[s.id] === f) continue
            thumbShown[s.id] = f
            val o = BitmapFactory.Options()
            o.inSampleSize = 4
            BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size, o)?.let { iv.setImageBitmap(it) }
        }
    }

    // ------------------------------------------------------------------ Bucle de 0,5 s

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 500)
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val ct = cortinilla
        if (ct != null && now > ct.startMs + ct.durationMs + 200) {
            cortinilla = null
            updateRender()
        }
        val sec = clock.now(now) / 1000
        recLabel.text = when (phase) {
            Phase.READY -> "PREPARADO"
            Phase.FINISHED -> "TERMINADO"
            else -> (if (phase == Phase.PLAYING) "● REC " else "❚❚ ") + fmt(sec)
        }
        recLabel.setTextColor(if (phase == Phase.PLAYING) Ui.RED else Ui.WHITE)
        statusLabel.text = statusText ?: if (phase == Phase.PAUSED) "EN PAUSA" else ""
        val ip = NetUtil.localIp()
        val cams = FrameHub.liveSources().count { it.id != FrameHub.MAIN }
        netLabel.text = if (live != null) {
            "Directo: ${ip ?: "sin wifi"}:$LIVE_PORT · PIN ${prefs.pin}\n$cams cámaras extra · ${live?.viewers ?: 0} viendo"
        } else ""
        val gp = gesture.progress
        gestureLabel.text = if (gp > 0.05f) "✋ VAR ${(gp * 3).toInt() + 1} s" else ""
        checkAutoPause(now)
        refreshThumbs()
    }

    private fun fmt(sec: Long): String = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)

    // ------------------------------------------------------------------ Render del marcador

    private fun updateRender() {
        val clubLine = listOf(match.club, match.court.let { if (it.isBlank()) "" else "Pista $it" })
            .filter { it.isNotBlank() }.joinToString(" · ")
        renderState = RenderState(
            teamA = match.teamA,
            teamB = match.teamB,
            score = score,
            cfg = match.config,
            club = clubLine,
            showScore = true,
            live = live != null && phase == Phase.PLAYING,
            badge = engine.pressureBadge(score),
            status = statusText,
            cortinilla = cortinilla
        )
    }

    private fun showCortinilla(title: String, subtitle: String, durationMs: Long, style: Int = Cortinilla.FULL) {
        cortinilla = Cortinilla(title, subtitle, SystemClock.uptimeMillis(), durationMs, style)
        cortSeq++
        updateRender()
    }

    private fun cortinillaActive(now: Long): Boolean {
        val c = cortinilla ?: return false
        return now < c.startMs + c.durationMs
    }

    /** Barrido rápido entre puntos (una sola vez por punto). */
    private fun pointCortinilla() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPointCortAt < 6000 || cortinillaActive(now)) return
        lastPointCortAt = now
        showCortinilla("", "", 1300, Cortinilla.QUICK)
    }

    /** El detector de movimiento dice que el juego se ha parado: fin de un punto. */
    private fun onRallyDetected(startVideo: Long, endVideo: Long) {
        if (phase != Phase.PLAYING || review.isOpen) return
        if (endVideo <= lastPointVideo) return
        lastRallyStart = startVideo
        lastRallyEnd = endVideo
        rallyCount++
        match.markers.add(Marker(Marker.RALLY, endVideo, startVideo.toString()))
        save()
        if (prefs.autoRally) pointCortinilla()
    }

    private fun scoreLine(): String {
        val sets = ScoreEngine.setsSummary(score.sets)
        val cur = if (score.finished) "" else "${score.gamesA}-${score.gamesB}"
        return listOf(sets, cur).filter { it.isNotBlank() }.joinToString("  ")
    }

    // ------------------------------------------------------------------ Grabación

    private fun startRecording() {
        val cam = camera ?: return
        if (phase != Phase.READY) return
        val file = MatchStore.file(this, match, VIDEO_NAME)
        val ok = cam.startRecording(file, true) { ev -> onRecordEvent(ev) }
        if (!ok) {
            Ui.toast(this, "No se pudo empezar a grabar")
            return
        }
        phase = Phase.PLAYING
        match.status = Match.STATUS_RECORDING
        match.videoFile = VIDEO_NAME
        save()
        updateButtons()
        updateRender()
    }

    private fun onRecordEvent(ev: VideoRecordEvent) {
        val now = SystemClock.uptimeMillis()
        when (ev) {
            is VideoRecordEvent.Start -> {
                clock.start(now)
                val date = SimpleDateFormat("d MMM yyyy", Locale("es", "ES")).format(Date(match.createdAt))
                val sub = listOf(match.club, match.court.let { if (it.isBlank()) "" else "Pista $it" }, date)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                showCortinilla("${match.teamA}  vs  ${match.teamB}", sub, 4800)
            }
            is VideoRecordEvent.Pause -> clock.pause(now)
            is VideoRecordEvent.Resume -> {
                clock.resume(now)
                showCortinilla("", "", 1300, Cortinilla.QUICK)
            }
            is VideoRecordEvent.Finalize -> onFinalize(ev)
            else -> {}
        }
    }

    private fun onFinalize(ev: VideoRecordEvent.Finalize) {
        clock.pause(SystemClock.uptimeMillis())
        val file = MatchStore.file(this, match, VIDEO_NAME)
        val usable = file.exists() && file.length() > 0
        match.durationMs = clock.now(SystemClock.uptimeMillis())
        match.sets = currentSets()
        match.winner = score.winner
        if (usable) {
            match.outputs[Match.OUT_FULL] = VIDEO_NAME
            match.status = Match.STATUS_RECORDED
        }
        save()
        if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_NONE && !usable) {
            Ui.toast(this, "La grabación falló (código ${ev.error})")
        }
        if (isFinishing || isDestroyed) return
        phase = Phase.FINISHED
        val i = Intent(this, MatchDetailActivity::class.java)
        i.putExtra(MatchDetailActivity.EXTRA_ID, match.id)
        i.putExtra(MatchDetailActivity.EXTRA_AUTO, usable)
        startActivity(i)
        finish()
    }

    private fun currentSets(): List<SetScore> {
        if (score.finished || (score.gamesA == 0 && score.gamesB == 0 && score.pointsA == 0 && score.pointsB == 0)) return score.sets
        return if (score.superTb) score.sets + SetScore(0, 0, score.pointsA, score.pointsB, superTb = true)
        else score.sets + SetScore(score.gamesA, score.gamesB)
    }

    private fun save() {
        match.sets = currentSets()
        match.winner = score.winner
        try { MatchStore.save(this, match) } catch (_: Exception) {}
    }

    private fun confirmStop() {
        if (phase == Phase.READY) { finish(); return }
        if (phase == Phase.FINISHED) return
        AlertDialog.Builder(this)
            .setTitle("¿Terminar la grabación?")
            .setMessage("Se guardará el vídeo y se crearán el resumen y las repeticiones.")
            .setPositiveButton("Terminar") { _, _ -> finishRecording() }
            .setNegativeButton("Seguir grabando", null)
            .show()
    }

    /** Termina con la cortinilla de final de partido grabada en el vídeo. */
    private fun finishRecording(outro: Boolean = true) {
        if (phase == Phase.FINISHED) return
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null
        if (phase == Phase.READY) { finish(); return }
        if (phase == Phase.PAUSED) resumeRecording()
        review.hide()
        phase = Phase.FINISHED
        statusText = null
        val winner = when (score.winner) { 0 -> match.teamA; 1 -> match.teamB; else -> null }
        val result = ScoreEngine.setsSummary(currentSets())
        val sub = when {
            winner != null && result.isNotBlank() -> "Ganan $winner · $result"
            result.isNotBlank() -> "${match.teamA}  $result  ${match.teamB}"
            else -> "${match.teamA}  vs  ${match.teamB}"
        }
        updateButtons()
        if (!outro) { stopNow(); return }
        showCortinilla("FINAL DEL PARTIDO", sub, 5200)
        handler.postDelayed({ stopNow() }, 5600)
    }

    private fun stopNow() {
        statusText = "GUARDANDO…"
        updateRender()
        camera?.stopRecording()
    }

    private fun onBack() {
        when {
            review.isOpen -> review.hide()
            phase == Phase.READY -> finish()
            else -> confirmStop()
        }
    }

    // ------------------------------------------------------------------ Puntos

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val team = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 0
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_ENTER -> 1
            else -> -1
        }
        if (team >= 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeyAt > 700 && (event?.repeatCount ?: 0) == 0) {
                lastKeyAt = now
                if (phase == Phase.READY) startRecording() else addPoint(team)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun addPoint(team: Int) {
        if (phase == Phase.READY) { Ui.toast(this, "Primero pulsa «Empezar a grabar»"); return }
        if (phase == Phase.FINISHED || score.finished) return
        if (review.isOpen) review.hide()
        if (phase == Phase.PAUSED) resumeRecording()
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null

        val nowUp = SystemClock.uptimeMillis()
        val nowV = clock.now(nowUp)
        // Si el detector vio el punto, usamos su inicio; si no, lo estimamos
        val start = if (lastRallyStart >= 0 && lastRallyEnd > lastPointVideo && nowV - lastRallyEnd < 20_000) {
            lastRallyStart
        } else motion.rallyStart(lastPointVideo, nowV)
        val before = score
        val out = engine.point(before, team)
        history.add(before)
        score = out.state
        val (pa, pb) = engine.pointLabels(out.state)
        val label = if (Ev.has(out.flags, Ev.GAME)) scoreLine() else "${scoreLine()}  $pa-$pb"
        val rec = PointRec(
            idx = match.points.size, team = team, tMs = nowV, startMs = start,
            set = before.currentSet, game = before.gameNumber, gameInSet = before.gameInSet,
            flags = out.flags, label = label
        )
        match.points.add(rec)
        lastPointVideo = nowV

        // Guardamos el final del punto más largo del juego para repetirlo en el directo
        val dur = rec.durationMs
        if (dur > bestClipDur) {
            bestClipDur = dur
            val window = dur.coerceIn(4000, 10_000)
            bestClip = FrameHub.snapshot(FrameHub.onAir, nowUp - window - 800, nowUp - 800)
                .ifEmpty { FrameHub.snapshot(FrameHub.MAIN, nowUp - window - 800, nowUp - 800) }
        }

        handleFlags(out.flags)
        save()
        updateRender()
        previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun handleFlags(flags: Int) {
        val a = match.teamA
        val b = match.teamB
        if (Ev.has(flags, Ev.GAME)) {
            if (bestClip.size > 5) FrameHub.startReplay(bestClip, "REPETICIÓN · punto más largo del juego", 0.7f)
            bestClip = emptyList()
            bestClipDur = 0
        }
        when {
            Ev.has(flags, Ev.MATCH) -> {
                val winner = if (score.winner == 0) a else b
                showCortinilla("¡GANAN $winner!", ScoreEngine.setsSummary(score.sets), 6500)
                val stop = Runnable { pendingStop = null; finishRecording(outro = false) }
                pendingStop = stop
                handler.postDelayed(stop, 6600)
            }
            Ev.has(flags, Ev.SET) -> {
                val last = score.sets.last()
                val res = if (last.superTb) "[${last.tbA}-${last.tbB}]" else "${last.a}-${last.b}"
                val title = if (Ev.has(flags, Ev.TIEBREAK_START)) "SUPER TIE-BREAK" else "FIN DEL SET ${score.sets.size}"
                showCortinilla(title, "$a  $res  $b", 4800)
                schedulePause(5000, "DESCANSO ENTRE SETS", 60_000, 150_000)
            }
            Ev.has(flags, Ev.TIEBREAK_START) -> showCortinilla("TIE-BREAK", "$a  6-6  $b", 3500)
            Ev.has(flags, Ev.GAME) && Ev.has(flags, Ev.SIDE_CHANGE) -> {
                showCortinilla("CAMBIO DE LADO", "$a  ${score.gamesA}-${score.gamesB}  $b", 3600)
                if (Ev.has(flags, Ev.BREAK_SHORT)) schedulePause(3900, "DESCANSO", 30_000, 120_000)
            }
            Ev.has(flags, Ev.SIDE_CHANGE) -> showCortinilla("CAMBIO DE LADO", "Tie-break ${score.pointsA}-${score.pointsB}", 2600)
            else -> pointCortinilla()
        }
    }

    private fun schedulePause(delay: Long, reason: String, minRest: Long, maxRest: Long) {
        if (!prefs.autoPause) return
        val r = Runnable {
            pendingPause = null
            pauseRecording(reason, true, minRest, maxRest)
        }
        pendingPause = r
        handler.postDelayed(r, delay)
    }

    private fun undo() {
        if (history.isEmpty() || phase == Phase.READY) return
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null
        if (phase == Phase.FINISHED) return
        score = history.removeAt(history.size - 1)
        if (match.points.isNotEmpty()) match.points.removeAt(match.points.size - 1)
        lastPointVideo = match.points.lastOrNull()?.tMs ?: 0
        cortinilla = null
        save()
        updateRender()
        Ui.toast(this, "Punto deshecho")
    }

    // ------------------------------------------------------------------ Pausas

    private fun togglePause() {
        when (phase) {
            Phase.PLAYING -> pauseRecording("PAUSA", false, 0, Long.MAX_VALUE)
            Phase.PAUSED -> resumeRecording()
            else -> {}
        }
    }

    private fun pauseRecording(reason: String, auto: Boolean, minRest: Long, maxRest: Long) {
        if (phase != Phase.PLAYING) return
        camera?.pause()
        phase = Phase.PAUSED
        autoResume = auto
        pausedAt = SystemClock.uptimeMillis()
        minRestMs = minRest
        maxRestMs = maxRest
        statusText = reason
        match.markers.add(Marker(Marker.PAUSE, clock.now(pausedAt), reason))
        save()
        updateButtons()
        updateRender()
    }

    private fun resumeRecording() {
        if (phase != Phase.PAUSED) return
        camera?.resume()
        phase = Phase.PLAYING
        statusText = null
        match.markers.add(Marker(Marker.RESUME, clock.now(SystemClock.uptimeMillis()), ""))
        updateButtons()
        updateRender()
    }

    private fun checkAutoPause(now: Long) {
        val cam = camera ?: return
        if (!prefs.autoPause || !cam.hasAnalysis || review.isOpen) return
        when (phase) {
            Phase.PLAYING -> {
                if (pendingPause == null && pendingStop == null &&
                    motion.quietFor(now) > prefs.inactivitySec * 1000L
                ) pauseRecording("PAUSA AUTOMÁTICA", true, 0, Long.MAX_VALUE)
            }
            Phase.PAUSED -> {
                if (!autoResume) return
                val elapsed = now - pausedAt
                if (elapsed >= maxRestMs || (elapsed >= minRestMs && motion.activeFor(now) >= 2500)) resumeRecording()
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Videoarbitraje

    private fun requestReview(reason: String) {
        if (phase != Phase.PLAYING && phase != Phase.PAUSED) return
        if (review.isOpen) return
        val nowUp = SystemClock.uptimeMillis()
        val angles = FrameHub.sources.values
            .sortedBy { if (it.id == FrameHub.MAIN) "" else it.name }
            .map { ReviewPanel.Angle(if (it.id == FrameHub.MAIN) "Principal" else it.name, FrameHub.snapshot(it.id, nowUp - 14_000, nowUp - 500)) }
            .filter { it.frames.size > 3 }
        match.markers.add(Marker(Marker.REVIEW, clock.now(nowUp), reason))
        save()
        statusText = "VIDEOARBITRAJE"
        showCortinilla("VIDEOARBITRAJE", reason.replaceFirstChar { it.uppercase() }, 2300)
        val onAirClip = FrameHub.snapshot(FrameHub.onAir, nowUp - 10_000, nowUp - 500)
        if (onAirClip.size > 5) FrameHub.startReplay(onAirClip, "VIDEOARBITRAJE", 0.5f)
        handler.postDelayed({
            if (phase == Phase.PLAYING && prefs.autoPause) pauseRecording("VIDEOARBITRAJE", false, 0, Long.MAX_VALUE)
            if (angles.isEmpty()) {
                Ui.toast(this, "No hay imágenes en memoria para repetir. Quedará marcado en el vídeo final.")
                onReviewClosed()
            } else {
                review.show(angles, reason)
            }
        }, 2400)
    }

    private fun onReviewClosed() {
        if (statusText == "VIDEOARBITRAJE") statusText = null
        if (phase == Phase.PAUSED && !autoResume) resumeRecording() else updateRender()
    }

    // ------------------------------------------------------------------ FrameAnalyzer.Listener (hilo de análisis)

    override fun onMotion(level: Float, wallMs: Long) {
        motion.add(level, wallMs, clock.now(wallMs))
    }

    override fun onJpeg(jpeg: ByteArray, width: Int, height: Int) {
        FrameHub.push(FrameHub.MAIN, "Principal", jpeg)
    }

    override fun onPose(pose: Pose, width: Int, height: Int, wallMs: Long) {
        if (phase == Phase.PLAYING || phase == Phase.PAUSED) gesture.onPose(pose, wallMs)
    }

    // ------------------------------------------------------------------ LiveServer.Host

    override fun stateJson(): JSONObject {
        val s = score
        val (pa, pb) = engine.pointLabels(s)
        val sc = JSONObject()
            .put("sets", ScoreEngine.setsToJson(s.sets))
            .put("ga", s.gamesA).put("gb", s.gamesB)
            .put("pa", pa).put("pb", pb)
            .put("server", s.server).put("finished", s.finished).put("winner", s.winner)
            .put("setNo", s.currentSet).put("superTb", s.superTb).put("tiebreak", s.tiebreak)
        val cams = JSONArray()
        for (src in FrameHub.sources.values.sortedBy { if (it.id == FrameHub.MAIN) "" else it.name }) {
            cams.put(JSONObject().put("id", src.id).put("name", if (src.id == FrameHub.MAIN) "Principal" else src.name).put("live", src.live))
        }
        val st = when (phase) {
            Phase.READY -> "ready"
            Phase.PLAYING -> "playing"
            Phase.PAUSED -> "paused"
            Phase.FINISHED -> "finished"
        }
        return JSONObject()
            .put("match", JSONObject().put("id", match.id).put("teamA", match.teamA).put("teamB", match.teamB)
                .put("club", match.club).put("court", match.court))
            .put("score", sc)
            .put("badge", engine.pressureBadge(s) ?: JSONObject.NULL)
            .put("status", st)
            .put("statusText", statusText ?: (if (phase == Phase.PAUSED) "EN PAUSA" else JSONObject.NULL))
            .put("rec", fmt(clock.now(SystemClock.uptimeMillis()) / 1000))
            .put("onAir", FrameHub.onAir)
            .put("replay", FrameHub.replayLabel ?: JSONObject.NULL)
            .put("cams", cams)
            .put("viewers", live?.viewers ?: 0)
            .put("cort", cortinilla?.let { c ->
                JSONObject().put("seq", cortSeq).put("title", c.title).put("sub", c.subtitle)
                    .put("style", c.style).put("dur", c.durationMs)
                    .put("age", SystemClock.uptimeMillis() - c.startMs)
            } ?: JSONObject().put("seq", cortSeq))
    }

    override fun onCommand(cmd: String, args: Map<String, String>, fromCamera: String?): String? {
        when (cmd) {
            "point" -> {
                val t = args["team"]?.toIntOrNull() ?: return "Falta el equipo"
                if (t !in 0..1) return "Equipo no válido"
                if (phase == Phase.READY) return "Aún no se está grabando"
                addPoint(t)
            }
            "undo" -> undo()
            "pause" -> if (phase == Phase.PLAYING) togglePause()
            "resume" -> if (phase == Phase.PAUSED) togglePause()
            "toggle" -> togglePause()
            "review" -> {
                val who = fromCamera?.let { id -> FrameHub.sources[id]?.name } ?: "la mesa"
                requestReview(if (fromCamera != null) "gesto en $who" else "pedido desde $who")
            }
            "onair" -> {
                val id = args["cam"] ?: return "Falta la cámara"
                if (id == "program") return null
                if (!FrameHub.sources.containsKey(id)) return "Cámara desconocida"
                FrameHub.onAir = id
            }
            "start" -> if (phase == Phase.READY) startRecording()
            else -> return "Orden desconocida"
        }
        return null
    }

    override fun onCameraRegistered(id: String, name: String) {
        Ui.toast(this, "Cámara conectada: $name")
    }
}

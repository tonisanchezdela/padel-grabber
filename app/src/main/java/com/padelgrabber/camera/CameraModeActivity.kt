package com.padelgrabber.camera

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import com.padelgrabber.match.Match
import com.padelgrabber.match.MatchStore
import com.padelgrabber.net.CameraServer
import com.padelgrabber.net.Discovery
import com.padelgrabber.net.FrameHub
import com.padelgrabber.overlay.RenderState
import com.padelgrabber.record.CameraController
import com.padelgrabber.record.RecordActivity
import com.padelgrabber.score.MatchConfig
import com.padelgrabber.score.ScoreEngine
import com.padelgrabber.score.ScoreState
import com.padelgrabber.ui.MatchDetailActivity
import com.padelgrabber.util.Prefs
import com.padelgrabber.util.Ui
import com.padelgrabber.util.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Modo "cámara extra": el móvil de un jugador graba otro ángulo, lo emite por la wifi
 * del club al móvil principal y avisa si alguien pide videoarbitraje con el gesto.
 */
class CameraModeActivity : ComponentActivity(), FrameAnalyzer.Listener {

    companion object {
        const val CAM_PORT = 8081
        private const val SELF = "self"
        private const val VIDEO_NAME = "angulo.mp4"
    }

    private lateinit var prefs: Prefs
    private lateinit var previewView: PreviewView
    private lateinit var statusLabel: TextView
    private lateinit var recBtn: Button
    private var camera: CameraController? = null
    private val analyzer = FrameAnalyzer(this)
    private val gesture = ArmGestureDetector { sendCommand("review") }
    private var server: CameraServer? = null
    private var discovery: Discovery? = null
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var mainHost: String? = null
    @Volatile private var mainPort = RecordActivity.LIVE_PORT
    @Volatile private var lastState: JSONObject? = null
    @Volatile private var renderState: RenderState? = null
    @Volatile private var connected = false
    private var localMatch: Match? = null
    private var recording = false
    private var paused = false
    private var manual = false
    private var camName = "Cámara"

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else { Ui.toast(this, "Sin permiso de cámara"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        camName = prefs.cameraName.ifBlank { "Cámara " + prefs.deviceId.takeLast(2) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        analyzer.poseEnabled = prefs.gestureReview
        analyzer.jpegEnabled = true
        buildUi()
        FrameHub.reset()
        FrameHub.onAir = SELF
        server = CameraServer(CAM_PORT, SELF).also { it.start() }
        val d = Discovery(this)
        discovery = d
        d.discover { found ->
            if (mainHost == null && found.name.startsWith("PadelGrabber")) {
                mainHost = found.host
                mainPort = found.port
                Ui.toast(this, "Conectado a ${found.name.removePrefix("PadelGrabber ")}")
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recording) {
                    AlertDialog.Builder(this@CameraModeActivity).setTitle("¿Dejar de grabar?")
                        .setPositiveButton("Parar") { _, _ -> stopLocal() }
                        .setNegativeButton("Seguir", null).show()
                } else finish()
            }
        })
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else permissions.launch(need)
        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        camera?.release()
        server?.stop()
        discovery?.close()
        analyzer.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusLabel = TextView(this)
        statusLabel.setTextColor(Ui.WHITE)
        statusLabel.typeface = Ui.BOLD
        statusLabel.textSize = 15f
        statusLabel.setPadding(dp(10), dp(8), dp(10), dp(8))
        statusLabel.background = Ui.rounded(0x99000000.toInt(), dp(10).toFloat())
        root.addView(statusLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(70); rightMargin = dp(12)
        })

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        fun b(text: String, bg: Int, fg: Int, onClick: () -> Unit): Button {
            val btn = Button(this)
            btn.text = text
            btn.isAllCaps = false
            btn.typeface = Ui.BOLD
            btn.setTextColor(fg)
            btn.background = Ui.rounded(bg, dp(12).toFloat())
            btn.setPadding(dp(16), 0, dp(16), 0)
            btn.setOnClickListener { onClick() }
            bar.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { leftMargin = dp(8) })
            return btn
        }
        recBtn = b("Grabar", Ui.LIME, Ui.NAVY) {
            manual = true
            if (recording) stopLocal() else startLocal()
        }
        b("VAR", 0xCCFF3B30.toInt(), Ui.WHITE) { sendCommand("review") }
        b("Conectar por IP", 0xCC132B4A.toInt(), Ui.WHITE) { askIp() }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16)
        })
        setContentView(root)
    }

    private fun startCamera() {
        val cam = CameraController(this, this, previewView, prefs.fullHd, { renderState }, analyzer)
        camera = cam
        cam.start { ok, msg -> if (!ok || msg != "Cámara lista") Ui.toast(this, msg) }
    }

    private fun askIp() {
        val e = EditText(this)
        e.hint = "192.168.1.20"
        e.setText(mainHost ?: "")
        AlertDialog.Builder(this).setTitle("IP del móvil que graba")
            .setMessage("Aparece en su pantalla, junto a «Directo».")
            .setView(e)
            .setPositiveButton("Conectar") { _, _ ->
                val txt = e.text.toString().trim()
                val host = txt.substringBefore(':')
                val port = txt.substringAfter(':', "").toIntOrNull() ?: RecordActivity.LIVE_PORT
                if (host.isNotBlank()) { mainHost = host; mainPort = port }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------------ Conexión con el principal

    private val poller = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, 1500)
        }
    }

    private fun poll() {
        val host = mainHost
        if (host == null) {
            statusLabel.text = "$camName\nBuscando el móvil principal en la wifi…"
            return
        }
        io.execute {
            val q = "id=${enc(prefs.deviceId)}&name=${enc(camName)}&port=$CAM_PORT"
            val st = request("http://$host:$mainPort/api/register?$q", "POST")
            runOnUiThread { onState(st) }
        }
    }

    private fun onState(st: JSONObject?) {
        connected = st != null
        if (st == null) {
            statusLabel.text = "$camName\nSin conexión con $mainHost"
            return
        }
        lastState = st
        val m = st.optJSONObject("match")
        val status = st.optString("status")
        statusLabel.text = "$camName · conectado\n${m?.optString("teamA")} vs ${m?.optString("teamB")}" +
            "\n" + when (status) {
                "playing" -> if (recording) "● Grabando" else "Partido en juego"
                "paused" -> "En pausa"
                "finished" -> "Partido terminado"
                else -> "Esperando a que empiece"
            } + if (gesture.progress > 0.05f) "\n✋ VAR…" else ""
        renderState = buildRender(st)
        // Seguir al principal
        if (!manual) {
            when (status) {
                "playing" -> if (!recording) startLocal() else if (paused) { camera?.resume(); paused = false }
                "paused" -> if (recording && !paused) { camera?.pause(); paused = true }
                "finished" -> if (recording) stopLocal()
            }
        }
    }

    private fun buildRender(st: JSONObject): RenderState {
        val m = st.optJSONObject("match") ?: JSONObject()
        val sc = st.optJSONObject("score") ?: JSONObject()
        val tiebreak = sc.optBoolean("tiebreak")
        fun pts(label: String): Int = if (tiebreak) label.toIntOrNull() ?: 0 else when (label) {
            "15" -> 1; "30" -> 2; "40" -> 3; "AD" -> 4; else -> 0
        }
        var pa = pts(sc.optString("pa"))
        var pb = pts(sc.optString("pb"))
        if (!tiebreak && (pa == 4 || pb == 4)) { if (pa == 4) pb = 3 else pa = 3 }
        val score = ScoreState(
            sets = ScoreEngine.setsFromJson(sc.optJSONArray("sets")),
            gamesA = sc.optInt("ga"), gamesB = sc.optInt("gb"),
            pointsA = pa, pointsB = pb,
            tiebreak = tiebreak, superTb = sc.optBoolean("superTb"),
            server = sc.optInt("server"), finished = sc.optBoolean("finished"), winner = sc.optInt("winner", -1)
        )
        return RenderState(
            teamA = m.optString("teamA", "Equipo A"),
            teamB = m.optString("teamB", "Equipo B"),
            score = score,
            cfg = MatchConfig(goldenPoint = false),
            club = m.optString("club"),
            badge = if (st.isNull("badge")) null else st.optString("badge"),
            status = if (st.isNull("statusText")) null else st.optString("statusText"),
            camLabel = camName.uppercase()
        )
    }

    private fun sendCommand(cmd: String) {
        val host = mainHost ?: run { runOnUiThread { Ui.toast(this, "No hay móvil principal conectado") }; return }
        io.execute {
            val r = request("http://$host:$mainPort/api/cmd?c=$cmd&cam=${enc(prefs.deviceId)}", "POST")
            runOnUiThread {
                Ui.toast(this, if (r?.optBoolean("ok") == true) "Videoarbitraje pedido" else "No se pudo pedir: ${r?.optString("error") ?: "sin conexión"}")
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(url: String, method: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
            }
            val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------ Grabación local

    private fun startLocal() {
        val cam = camera ?: return
        if (recording) return
        val st = lastState
        val mm = st?.optJSONObject("match")
        val m = Match(id = Match.newId(), createdAt = System.currentTimeMillis())
        m.role = Match.ROLE_CAMERA
        m.cameraName = camName
        m.linkedMatchId = mm?.optString("id")?.ifBlank { null } ?: m.id
        m.teamA = mm?.optString("teamA", "Equipo A") ?: "Equipo A"
        m.teamB = mm?.optString("teamB", "Equipo B") ?: "Equipo B"
        m.club = mm?.optString("club") ?: ""
        m.court = mm?.optString("court") ?: ""
        m.status = Match.STATUS_RECORDING
        m.videoFile = VIDEO_NAME
        MatchStore.save(this, m)
        localMatch = m
        val file = MatchStore.file(this, m, VIDEO_NAME)
        val started = SystemClock.uptimeMillis()
        cam.startRecording(file, true) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                m.durationMs = SystemClock.uptimeMillis() - started
                if (file.exists() && file.length() > 0) {
                    m.outputs["angulo"] = VIDEO_NAME
                    m.status = Match.STATUS_RECORDED
                }
                lastState?.optJSONObject("score")?.let { m.sets = ScoreEngine.setsFromJson(it.optJSONArray("sets")) }
                MatchStore.save(this, m)
                val i = Intent(this, MatchDetailActivity::class.java)
                i.putExtra(MatchDetailActivity.EXTRA_ID, m.id)
                i.putExtra(MatchDetailActivity.EXTRA_AUTO, true)
                startActivity(i)
                finish()
            }
        }
        recording = true
        paused = false
        recBtn.text = "Parar"
    }

    private fun stopLocal() {
        if (!recording) { finish(); return }
        recording = false
        recBtn.text = "Guardando…"
        camera?.stopRecording()
    }

    // ------------------------------------------------------------------ Análisis

    override fun onJpeg(jpeg: ByteArray, width: Int, height: Int) {
        FrameHub.push(SELF, camName, jpeg)
    }

    override fun onPose(pose: Pose, width: Int, height: Int, wallMs: Long) {
        if (connected) gesture.onPose(pose, wallMs)
    }
}

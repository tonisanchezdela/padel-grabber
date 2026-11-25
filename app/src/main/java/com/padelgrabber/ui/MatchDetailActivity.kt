package com.padelgrabber.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import com.padelgrabber.match.Match
import com.padelgrabber.match.MatchStore
import com.padelgrabber.process.Highlights
import com.padelgrabber.process.VideoBuilder
import com.padelgrabber.upload.Uploader
import com.padelgrabber.util.Prefs
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.dp
import com.padelgrabber.util.label
import com.padelgrabber.util.lp
import com.padelgrabber.util.screen
import com.padelgrabber.util.title
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Ficha de un partido: crear resumen y repeticiones, ver, guardar, compartir y subir. */
@OptIn(UnstableApi::class)
class MatchDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "match_id"
        const val EXTRA_AUTO = "auto"
        const val SUMMARY_NAME = "resumen.mp4"
        const val REPLAYS_NAME = "partido_repeticiones.mp4"

        fun typeLabel(type: String): String = when {
            type == Match.OUT_SUMMARY -> "Resumen (mejores puntos)"
            type == Match.OUT_REPLAYS -> "Partido con repeticiones"
            type == Match.OUT_FULL -> "Partido completo"
            type.startsWith("angulo") -> "Otro ángulo"
            else -> type
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var match: Match
    private var busy = false
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var builder: VideoBuilder? = null
    private var pendingGallery: Runnable? = null

    private val legacyPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) pendingGallery?.run() else Ui.toast(this, "Sin permiso no se puede guardar en la galería") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val m = intent.getStringExtra(EXTRA_ID)?.let { MatchStore.load(this, it) }
        if (m == null) { Ui.toast(this, "Partido no encontrado"); finish(); return }
        match = m
        build()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO, false)) autoPipeline()
    }

    override fun onDestroy() {
        builder?.cancel()
        super.onDestroy()
    }

    private fun file(name: String) = MatchStore.file(this, match, name)

    private fun build() {
        val fmt = SimpleDateFormat("EEEE d 'de' MMMM yyyy · HH:mm", Locale("es", "ES"))
        screen {
            title(match.title, 24f)
            if (match.role == Match.ROLE_CAMERA) label("Ángulo grabado por «${match.cameraName}»", 15f, Ui.LIME)
            label(fmt.format(Date(match.createdAt)))
            val res = Highlights.resultLine(match)
            if (res.isNotBlank()) label(res, 18f, Ui.WHITE)
            val mins = match.durationMs / 60000
            label("${mins} min grabados · ${match.points.size} puntos apuntados · " +
                "${match.markers.count { it.type == "review" }} videoarbitrajes")
            listOf(match.club, match.court.let { if (it.isBlank()) "" else "Pista $it" })
                .filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isNotBlank()) label(it) }

            val pt = label("", 15f, Ui.LIME)
            progressText = pt
            val pb = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 100
            pb.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
            addView(pb, lp(4))
            progressBar = pb

            title("Vídeos", 20f)
            val outs = match.outputs.filter { file(it.value).exists() }
            if (outs.isEmpty()) label("No hay vídeos todavía.")
            for ((type, name) in outs) {
                val f = file(name)
                val mb = f.length() / (1024 * 1024)
                button("▶  ${typeLabel(type)} · $mb MB", primary = type == Match.OUT_SUMMARY) { play(f) }
            }

            if (match.role == Match.ROLE_MAIN && match.outputs.containsKey(Match.OUT_FULL)) {
                title("Montaje", 20f)
                button(if (match.outputs.containsKey(Match.OUT_SUMMARY)) "Volver a crear el resumen" else "Crear resumen con los mejores puntos") {
                    if (!busy) makeSummary { refresh() }
                }
                button(if (match.outputs.containsKey(Match.OUT_REPLAYS)) "Volver a crear el partido con repeticiones" else "Crear partido con repeticiones") {
                    if (!busy) makeReplays { refresh() }
                }
            }

            title("Compartir", 20f)
            button("Subir a la plataforma") { if (!busy) upload { refresh() } }
            button("Guardar en la galería") { if (!busy) saveToGallery() }
            button("Compartir…") { share() }

            if (match.uploaded.isNotEmpty()) {
                title("En la plataforma", 20f)
                for ((type, url) in match.uploaded) {
                    val t = label("${typeLabel(type)}\n$url", 13f, Ui.GREY)
                    t.setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Padel Grabber", url))
                        Ui.toast(this@MatchDetailActivity, "Enlace copiado")
                    }
                }
                if (prefs.webUrl.startsWith("http")) {
                    button("Abrir la plataforma") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.webUrl))) }
                }
            }

            title("", 8f)
            button("Borrar este partido del móvil") { confirmDelete() }
        }
    }

    private fun refresh() {
        MatchStore.load(this, match.id)?.let { match = it }
        val txt = progressText?.text?.toString() ?: ""
        build()
        progressText?.text = txt
    }

    private fun setBusy(b: Boolean, text: String = "") {
        busy = b
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressText?.text = text
        progressBar?.visibility = if (b) android.view.View.VISIBLE else android.view.View.GONE
        progressBar?.isIndeterminate = b
    }

    private fun progress(text: String, pct: Int) {
        progressText?.text = text
        progressBar?.let { it.isIndeterminate = false; it.progress = pct }
    }

    // ------------------------------------------------------------------ Automático

    private fun autoPipeline() {
        val steps = ArrayList<(() -> Unit) -> Unit>()
        if (match.role == Match.ROLE_MAIN && prefs.autoProcess && match.points.isNotEmpty()) {
            steps.add { next -> makeSummary(next) }
            steps.add { next -> makeReplays(next) }
        }
        if (prefs.autoUpload && prefs.uploadConfigured) steps.add { next -> upload(next) }
        fun runFrom(i: Int) {
            if (i >= steps.size) { refresh(); return }
            steps[i] { runOnUiThread { runFrom(i + 1) } }
        }
        if (steps.isEmpty()) {
            if (!prefs.uploadConfigured) progressText?.text = "Vídeo guardado. Configura Supabase en Ajustes para subirlo a la plataforma."
            return
        }
        runFrom(0)
    }

    // ------------------------------------------------------------------ Montaje

    private fun sourceVideo(): File? = match.outputs[Match.OUT_FULL]?.let { file(it) }?.takeIf { it.exists() }

    private fun makeSummary(next: () -> Unit) {
        val src = sourceVideo() ?: run { Ui.toast(this, "No está el vídeo del partido"); next(); return }
        setBusy(true, "Creando el resumen…")
        val b = VideoBuilder(this, match, src)
        builder = b
        b.buildSummary(file(SUMMARY_NAME), object : VideoBuilder.Callback {
            override fun onProgress(percent: Int) = progress("Creando el resumen… $percent %", percent)
            override fun onDone(output: File) {
                match.outputs[Match.OUT_SUMMARY] = SUMMARY_NAME
                match.status = Match.STATUS_PROCESSED
                MatchStore.save(this@MatchDetailActivity, match)
                setBusy(false, "Resumen listo")
                refresh()
                next()
            }
            override fun onError(message: String) {
                setBusy(false, message)
                next()
            }
        })
    }

    private fun makeReplays(next: () -> Unit) {
        val src = sourceVideo() ?: run { next(); return }
        setBusy(true, "Montando el partido con repeticiones… (tarda un rato)")
        val b = VideoBuilder(this, match, src)
        builder = b
        b.buildReplays(file(REPLAYS_NAME), object : VideoBuilder.Callback {
            override fun onProgress(percent: Int) = progress("Montando el partido con repeticiones… $percent %", percent)
            override fun onDone(output: File) {
                match.outputs[Match.OUT_REPLAYS] = REPLAYS_NAME
                match.status = Match.STATUS_PROCESSED
                MatchStore.save(this@MatchDetailActivity, match)
                setBusy(false, "Partido con repeticiones listo")
                refresh()
                next()
            }
            override fun onError(message: String) {
                setBusy(false, message)
                next()
            }
        })
    }

    // ------------------------------------------------------------------ Subida

    private fun upload(next: () -> Unit) {
        if (!prefs.uploadConfigured) {
            Ui.toast(this, "Configura Supabase en Ajustes")
            startActivity(Intent(this, SettingsActivity::class.java))
            next()
            return
        }
        val order = listOf(Match.OUT_SUMMARY, Match.OUT_REPLAYS, Match.OUT_FULL, "angulo")
        val todo = order.mapNotNull { t -> match.outputs[t]?.let { t to it } }
            .filter { (t, n) -> file(n).exists() && !match.uploaded.containsKey(t) }
        if (todo.isEmpty()) {
            Ui.toast(this, if (match.uploaded.isEmpty()) "No hay vídeos para subir" else "Ya está todo subido")
            next()
            return
        }
        setBusy(true, "Subiendo…")
        val up = Uploader(prefs.supabaseUrl, prefs.supabaseKey, prefs.bucket)
        val m = match
        thread(name = "pg-upload") {
            val errors = ArrayList<String>()
            try {
                if (m.role == Match.ROLE_MAIN) up.saveMatch(m)
            } catch (e: Exception) {
                errors.add(e.message ?: "error guardando el partido")
            }
            for ((type, name) in todo) {
                val f = file(name)
                val safeCam = m.cameraName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "camara" }
                val obj = if (type == "angulo") "${m.linkedMatchId}/angulo-$safeCam-${m.id}.mp4" else "${m.linkedMatchId}/$name"
                val tipo = if (type == "angulo") "angulo:${m.cameraName}" else type
                try {
                    val url = up.upload(f, obj, "video/mp4") { done, total ->
                        val pct = (done * 100 / total).toInt()
                        runOnUiThread { progress("Subiendo ${typeLabel(type).lowercase()}… $pct %", pct) }
                    }
                    up.saveVideo(m.linkedMatchId, tipo, obj, url, f.length())
                    m.uploaded[type] = url
                    MatchStore.save(this, m)
                } catch (e: Exception) {
                    errors.add("${typeLabel(type)}: ${e.message}")
                }
            }
            if (errors.isEmpty() && m.uploaded.isNotEmpty()) {
                m.status = Match.STATUS_UPLOADED
                MatchStore.save(this, m)
            }
            runOnUiThread {
                setBusy(false, if (errors.isEmpty()) "Subido a la plataforma" else "Subida con problemas:\n" + errors.joinToString("\n"))
                refresh()
                next()
            }
        }
    }

    // ------------------------------------------------------------------ Ver, guardar, compartir

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun play(f: File) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uriFor(f), "video/mp4")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) { Ui.toast(this, "No hay reproductor de vídeo") }
    }

    private fun share() {
        val files = match.outputs.values.map { file(it) }.filter { it.exists() }
        if (files.isEmpty()) { Ui.toast(this, "No hay vídeos"); return }
        val first = match.outputs[Match.OUT_SUMMARY]?.let { file(it) }?.takeIf { it.exists() } ?: files.first()
        val i = Intent(Intent.ACTION_SEND)
        i.type = "video/mp4"
        i.putExtra(Intent.EXTRA_STREAM, uriFor(first))
        val link = match.uploaded[Match.OUT_SUMMARY] ?: match.uploaded.values.firstOrNull()
        i.putExtra(Intent.EXTRA_TEXT, "${match.title} · ${Highlights.resultLine(match)}" + (link?.let { "\n$it" } ?: ""))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Compartir partido"))
    }

    private fun saveToGallery() {
        val files = match.outputs.entries.filter { file(it.value).exists() }
        if (files.isEmpty()) { Ui.toast(this, "No hay vídeos"); return }
        val job = Runnable {
            setBusy(true, "Guardando en la galería…")
            thread {
                var ok = 0
                for ((type, name) in files) {
                    val display = "PadelGrabber_${match.id}_${type.replace(':', '-')}.mp4"
                    if (copyToGallery(file(name), display)) ok++
                }
                runOnUiThread { setBusy(false, "$ok vídeos guardados en Galería › Movies › PadelGrabber") }
            }
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGallery = job
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else job.run()
    }

    private fun copyToGallery(src: File, display: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.DISPLAY_NAME, display)
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PadelGrabber")
                values.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PadelGrabber")
                dir.mkdirs()
                val dst = File(dir, display)
                src.copyTo(dst, overwrite = true)
                MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar el partido?")
            .setMessage("Se borran del móvil el vídeo y los montajes. Lo que ya esté en la plataforma se queda allí.")
            .setPositiveButton("Borrar") { _, _ ->
                MatchStore.delete(this, match.id)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @Suppress("unused")
    private fun spacer(col: LinearLayout) = col.addView(TextView(this), LinearLayout.LayoutParams(1, dp(16)))
}

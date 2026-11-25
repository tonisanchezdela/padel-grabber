package com.padelgrabber.process

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.padelgrabber.match.Match
import com.padelgrabber.overlay.Cortinilla
import com.padelgrabber.overlay.OverlayRenderer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.media3.effect.OverlayEffect as Media3OverlayEffect

/** Tamaño de la imagen tal y como se ve (ya girada). */
data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

/** Lienzo compartido por todos los rótulos del montaje (se procesan de uno en uno). */
private class SharedCanvas(val width: Int, val height: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val renderer = OverlayRenderer()
    var key: String = ""
}

/**
 * Rótulos animados de un trozo: barrido "Padel Grabber" al entrar, rótulo de repetición,
 * tarjeta de presentación (primer trozo del resumen) y resultado final (último trozo).
 */
@UnstableApi
private class PieceOverlay(
    private val shared: SharedCanvas,
    private val piece: Piece,
    private val id: Int,
    private val introTitle: String,
    private val introSub: String,
    private val outroTitle: String,
    private val outroSub: String
) : BitmapOverlay() {

    private var firstUs = -1L

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstUs < 0) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1000
        val dur = piece.durationMs
        val w = shared.width
        val h = shared.height
        val s = h / 720f

        // Qué toca dibujar en este instante
        val introP = if (piece.intro && t < 3800) t / 3800f else -1f
        val outroStart = dur - 4200
        val outroP = if (piece.outro && t >= outroStart) ((t - outroStart) / 4200f).coerceAtMost(1f) else -1f
        val wipeP = if (!piece.intro && t < 900) t / 900f else -1f
        val lowerAlpha = when {
            piece.title.isBlank() -> 0
            t < 400 -> 0
            t < 700 -> ((t - 400) * 255 / 300).toInt()
            t < 3800 -> 255
            t < 4200 -> ((4200 - t) * 255 / 400).toInt()
            else -> 0
        }
        val animating = introP >= 0 || outroP >= 0 || wipeP >= 0 || (lowerAlpha in 1..254)
        val key = if (animating) "$id:$t" else "$id:static:${lowerAlpha > 0}"
        if (key == shared.key) return shared.bitmap
        shared.key = key

        val c = shared.canvas
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val r = shared.renderer
        if (piece.kind == Piece.REPLAY || piece.kind == Piece.REVIEW) {
            r.drawReplayBadge(c, w, s, if (piece.kind == Piece.REVIEW) "↺ VIDEOARBITRAJE" else "↺ REPETICIÓN")
        }
        if (lowerAlpha > 0 && introP < 0) r.drawLowerThird(c, w, h, s, piece.title, piece.subtitle, lowerAlpha)
        if (wipeP >= 0) r.drawCortinilla(c, w, h, s, "", "", Cortinilla.QUICK, wipeP)
        if (introP >= 0) r.drawCortinilla(c, w, h, s, introTitle, introSub, Cortinilla.FULL, introP)
        if (outroP >= 0) r.drawCortinilla(c, w, h, s, outroTitle, outroSub, Cortinilla.FULL, outroP.coerceAtMost(0.83f))
        return shared.bitmap
    }
}

/**
 * Monta los vídeos finales con Media3 Transformer:
 *  - partido con repeticiones (tras cada juego y en cada videoarbitraje)
 *  - resumen con los mejores puntos
 */
@UnstableApi
class VideoBuilder(private val ctx: Context, private val match: Match, private val source: File) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(output: File)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null

    fun info(): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(source.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot % 180 == 0) VideoInfo(w, h, d) else VideoInfo(h, w, d)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun buildReplays(output: File, cb: Callback) {
        val vi = info() ?: return cb.onError("No se puede leer el vídeo del partido")
        val pieces = Highlights.withReplays(match, vi.durationMs)
        if (pieces.none { it.kind != Piece.MAIN }) return cb.onError("No hay puntos largos ni videoarbitrajes que repetir")
        run(pieces, vi, output, bitrate = if (vi.height >= 1000) 9_000_000 else 5_000_000, cb = cb)
    }

    fun buildSummary(output: File, cb: Callback) {
        val vi = info() ?: return cb.onError("No se puede leer el vídeo del partido")
        val pieces = Highlights.summary(match, vi.durationMs)
        if (pieces.isEmpty()) return cb.onError("No hay puntos apuntados para hacer el resumen")
        // Pensado para caber en el límite de 50 MB del plan gratuito de Supabase
        val totalSec = (pieces.sumOf { it.durationMs } / 1000).coerceAtLeast(1)
        val bitrate = (45L * 8 * 1024 * 1024 / totalSec).coerceIn(1_500_000, 4_000_000).toInt()
        run(pieces, vi, output, bitrate, cb)
    }

    fun cancel() {
        try { transformer?.cancel() } catch (_: Exception) {}
    }

    private fun run(pieces: List<Piece>, vi: VideoInfo, output: File, bitrate: Int, cb: Callback) {
        val shared = SharedCanvas(vi.width, vi.height)
        val date = SimpleDateFormat("d MMM yyyy", Locale("es", "ES")).format(Date(match.createdAt))
        val introTitle = "${match.teamA}  vs  ${match.teamB}"
        val introSub = listOf("RESUMEN", match.club, date).filter { it.isNotBlank() }.joinToString(" · ")
        val outroTitle = when (match.winner) {
            0 -> "¡GANAN ${match.teamA}!"
            1 -> "¡GANAN ${match.teamB}!"
            else -> "RESULTADO"
        }
        val outroSub = match.resultText.ifBlank { "Padel Grabber" }
        val uri = Uri.fromFile(source)

        val items = pieces.mapIndexed { i, p ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(p.startMs)
                .setEndPositionMs(p.endMs)
                .build()
            val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
            val b = EditedMediaItem.Builder(mi)
            if (p.kind != Piece.MAIN) {
                val overlay = PieceOverlay(shared, p, i, introTitle, introSub, outroTitle, outroSub)
                val effect = Media3OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))
                b.setEffects(Effects(emptyList(), listOf(effect)))
            }
            b.build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()

        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()

        val tmp = File(output.parentFile, output.name + ".tmp.mp4")
        tmp.delete()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacks(progressTask)
                    output.delete()
                    if (!tmp.renameTo(output)) tmp.copyTo(output, overwrite = true)
                    tmp.delete()
                    cb.onDone(output)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    main.removeCallbacks(progressTask)
                    tmp.delete()
                    cb.onError("Error montando el vídeo: ${exportException.message}")
                }
            })
            .build()
        transformer = t
        holderTransformer = t
        progressCb = cb
        t.start(composition, tmp.absolutePath)
        main.postDelayed(progressTask, 1000)
    }

    private var holderTransformer: Transformer? = null
    private var progressCb: Callback? = null
    private val holder = ProgressHolder()
    private val progressTask = object : Runnable {
        override fun run() {
            val t = holderTransformer ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progressCb?.onProgress(holder.progress)
            main.postDelayed(this, 1000)
        }
    }
}

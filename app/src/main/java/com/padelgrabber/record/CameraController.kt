package com.padelgrabber.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.padelgrabber.overlay.OverlayRenderer
import com.padelgrabber.overlay.RenderState
import java.io.File
import java.util.concurrent.Executors

/**
 * Cámara + grabación + marcador incrustado (OverlayEffect) + análisis de fotogramas.
 * Lo usan el móvil principal y los móviles de cámara extra.
 */
class CameraController(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val fullHd: Boolean,
    private val renderState: () -> RenderState?,
    private val analyzer: ImageAnalysis.Analyzer?
) {
    companion object {
        private const val TAG = "PG-Camera"

        /** Matriz que lleva coordenadas "derechas" del recorte al búfer de la cámara. */
        fun uprightToBuffer(crop: Rect, rotation: Int): Matrix {
            val l = crop.left.toFloat()
            val t = crop.top.toFloat()
            val cw = crop.width().toFloat()
            val ch = crop.height().toFloat()
            val v = when ((rotation % 360 + 360) % 360) {
                90 -> floatArrayOf(0f, 1f, l, -1f, 0f, t + ch, 0f, 0f, 1f)
                180 -> floatArrayOf(-1f, 0f, l + cw, 0f, -1f, t + ch, 0f, 0f, 1f)
                270 -> floatArrayOf(0f, -1f, l + cw, 1f, 0f, t, 0f, 0f, 1f)
                else -> floatArrayOf(1f, 0f, l, 0f, 1f, t, 0f, 0f, 1f)
            }
            val m = Matrix()
            m.setValues(v)
            return m
        }
    }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null
    private val overlayThread = HandlerThread("pg-overlay").apply { start() }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val renderer = OverlayRenderer()
    private var overlayEffect: OverlayEffect? = null

    var hasAnalysis = false
        private set
    var hasOverlay = false
        private set
    val isRecording: Boolean get() = recording != null

    /** Arranca la cámara. Prueba primero con todo y va quitando piezas si el móvil no puede. */
    fun start(onReady: (ok: Boolean, message: String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                provider = future.get()
                val msg = bind()
                onReady(true, msg)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir la cámara", e)
                onReady(false, "No se pudo abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = previewView.display?.rotation
        ?: (ctx as? android.app.Activity)?.windowManager?.defaultDisplay?.rotation
        ?: Surface.ROTATION_90

    private fun makeOverlay(): OverlayEffect {
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            0,
            Handler(overlayThread.looper)
        ) { t -> Log.e(TAG, "Error en el marcador", t) }
        effect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val rs = renderState()
            if (rs != null) {
                val crop = frame.cropRect
                val rot = frame.rotationDegrees
                val upright = rot % 180 == 0
                val w = if (upright) crop.width() else crop.height()
                val h = if (upright) crop.height() else crop.width()
                canvas.save()
                canvas.concat(uprightToBuffer(crop, rot))
                canvas.clipRect(0, 0, w, h)
                try {
                    renderer.draw(canvas, w, h, rs, SystemClock.uptimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "draw", e)
                }
                canvas.restore()
            }
            true
        }
        return effect
    }

    private fun bind(): String {
        val p = provider ?: return "Sin cámara"
        val rot = rotation()

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .setTargetRotation(rot)
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val quality = if (fullHd) Quality.FHD else Quality.HD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .setTargetVideoEncodingBitRate(if (fullHd) 10_000_000 else 6_000_000)
            .build()
        val vc = VideoCapture.withOutput(recorder)
        vc.targetRotation = rot
        videoCapture = vc

        val an = if (analyzer != null) {
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(rot)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
        } else null

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val effect = overlayEffect ?: makeOverlay().also { overlayEffect = it }

        // 1) Todo junto
        val attempts = ArrayList<Triple<Boolean, Boolean, String>>()
        if (an != null) attempts.add(Triple(true, true, "Cámara lista"))
        attempts.add(Triple(false, true, if (an != null) "Cámara lista (sin análisis: este móvil no permite autopausa ni gestos)" else "Cámara lista"))
        attempts.add(Triple(false, false, "Cámara lista (este móvil no permite incrustar el marcador en directo)"))

        var lastError: Exception? = null
        for ((withAnalysis, withOverlay, msg) in attempts) {
            try {
                p.unbindAll()
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(vc)
                if (withAnalysis && an != null) group.addUseCase(an)
                if (withOverlay) group.addEffect(effect)
                camera = p.bindToLifecycle(owner, selector, group.build())
                analysis = if (withAnalysis) an else null
                hasAnalysis = withAnalysis && an != null
                hasOverlay = withOverlay
                return msg
            } catch (e: Exception) {
                Log.w(TAG, "Combinación no soportada (análisis=$withAnalysis, marcador=$withOverlay)", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No se pudo configurar la cámara")
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun maxZoom(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    @SuppressLint("MissingPermission")
    fun startRecording(file: File, withAudio: Boolean, listener: (VideoRecordEvent) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return true
        val options = FileOutputOptions.Builder(file).build()
        var pending = vc.output.prepareRecording(ctx, options)
        if (withAudio && hasAudioPermission()) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(ctx)) { ev ->
            if (ev is VideoRecordEvent.Finalize) recording = null
            listener(ev)
        }
        return true
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun pause() { recording?.pause() }
    fun resume() { recording?.resume() }
    fun stopRecording() { recording?.stop() }

    fun release() {
        try { recording?.stop() } catch (_: Exception) {}
        recording = null
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { overlayEffect?.close() } catch (_: Exception) {}
        overlayThread.quitSafely()
        analysisExecutor.shutdown()
    }
}

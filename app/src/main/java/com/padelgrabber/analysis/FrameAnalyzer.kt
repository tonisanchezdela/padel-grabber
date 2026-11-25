package com.padelgrabber.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Procesa los fotogramas de la cámara (unos 12 por segundo):
 * - nivel de movimiento (para autopausa y para estimar la duración de los puntos)
 * - JPEG para el directo por wifi
 * - postura del jugador (gesto de videoarbitraje / biomecánica)
 */
class FrameAnalyzer(private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        fun onMotion(level: Float, wallMs: Long) {}
        fun onJpeg(jpeg: ByteArray, width: Int, height: Int) {}
        fun onPose(pose: Pose, width: Int, height: Int, wallMs: Long) {}
    }

    @Volatile var jpegEnabled = true
    @Volatile var poseEnabled = true
    @Volatile var jpegQuality = 55
    /** Cada cuántos fotogramas analizados se busca la postura. */
    @Volatile var poseEvery = 3
    @Volatile var minIntervalMs = 80L

    private var nv21: ByteArray? = null
    private var prevGrid: IntArray? = null
    private var lastMs = 0L
    private var count = 0L
    private val detector: PoseDetector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastMs < minIntervalMs) return
            lastMs = now
            count++
            val w = image.width
            val h = image.height
            val data = toNv21(image)
            val rotation = image.imageInfo.rotationDegrees

            listener.onMotion(motion(data, w, h), now)

            if (jpegEnabled) {
                val jpeg = toJpeg(data, w, h, rotation, jpegQuality)
                if (jpeg != null) {
                    val upright = rotation % 180 == 0
                    listener.onJpeg(jpeg, if (upright) w else h, if (upright) h else w)
                }
            }

            if (poseEnabled && count % poseEvery.coerceAtLeast(1) == 0L) {
                val input = InputImage.fromByteArray(data, w, h, rotation, InputImage.IMAGE_FORMAT_NV21)
                val pose = Tasks.await(detector.process(input), 2, TimeUnit.SECONDS)
                val upright = rotation % 180 == 0
                listener.onPose(pose, if (upright) w else h, if (upright) h else w, now)
            }
        } catch (e: Exception) {
            Log.w("PG-Analyzer", "Fallo analizando fotograma", e)
        } finally {
            image.close()
        }
    }

    fun close() {
        try { detector.close() } catch (_: Exception) {}
    }

    /** Fracción de puntos de una rejilla que han cambiado respecto al fotograma anterior. */
    private fun motion(data: ByteArray, w: Int, h: Int): Float {
        val step = 8
        val gw = w / step
        val gh = h / step
        val n = gw * gh
        var grid = prevGrid
        val first = grid == null || grid.size != n
        if (first) grid = IntArray(n)
        val g = grid!!
        var changed = 0
        var i = 0
        for (gy in 0 until gh) {
            val row = gy * step * w
            for (gx in 0 until gw) {
                val v = data[row + gx * step].toInt() and 0xFF
                if (!first && abs(v - g[i]) > 22) changed++
                g[i] = v
                i++
            }
        }
        prevGrid = g
        return if (first || n == 0) 0f else changed.toFloat() / n
    }

    private fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val size = ySize + ySize / 2
        var out = nv21
        if (out == null || out.size != size) {
            out = ByteArray(size)
            nv21 = out
        }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        var pos = 0
        if (yPix == 1 && yRow == w) {
            yBuf.position(0)
            yBuf.get(out, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until h) {
                val base = row * yRow
                if (yPix == 1) {
                    yBuf.position(base)
                    yBuf.get(out, pos, w)
                    pos += w
                } else {
                    for (col in 0 until w) out[pos++] = yBuf.get(base + col * yPix)
                }
            }
        }
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        val ch = h / 2
        val cw = w / 2
        for (row in 0 until ch) {
            val ub = row * uRow
            val vb = row * vRow
            for (col in 0 until cw) {
                out[pos++] = vBuf.get(vb + col * vPix)
                out[pos++] = uBuf.get(ub + col * uPix)
            }
        }
        return out
    }

    private val jpegStream = ByteArrayOutputStream(64 * 1024)

    private fun toJpeg(nv21: ByteArray, w: Int, h: Int, rotation: Int, quality: Int): ByteArray? {
        jpegStream.reset()
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        if (!yuv.compressToJpeg(Rect(0, 0, w, h), quality, jpegStream)) return null
        val bytes = jpegStream.toByteArray()
        if (rotation % 360 == 0) return bytes
        // Poco habitual en horizontal: giramos la imagen.
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        jpegStream.reset()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, jpegStream)
        bmp.recycle()
        rotated.recycle()
        return jpegStream.toByteArray()
    }
}

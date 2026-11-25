package com.padelgrabber.analysis

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detecta el gesto de pedir videoarbitraje: jugador mirando a la cámara
 * con un brazo levantado por encima de la cabeza durante 3 segundos.
 */
class ArmGestureDetector(
    private val holdMs: Long = 3000,
    private val cooldownMs: Long = 20_000,
    private val onTriggered: () -> Unit
) {
    private var since = 0L
    private var lastSeen = 0L
    private var lastTrigger = 0L

    /** 0..1 mientras el jugador mantiene el brazo arriba. */
    @Volatile var progress = 0f
        private set

    fun onPose(pose: Pose, now: Long) {
        val raised = isRaisedFacing(pose)
        if (raised) {
            if (since == 0L) since = now
            lastSeen = now
            progress = ((now - since).toFloat() / holdMs).coerceAtMost(1f)
            if (now - since >= holdMs && now - lastTrigger > cooldownMs) {
                lastTrigger = now
                since = 0
                progress = 0f
                onTriggered()
            }
        } else if (since != 0L && now - lastSeen > 700) {
            since = 0
            progress = 0f
        }
    }

    private fun isRaisedFacing(p: Pose): Boolean {
        fun lm(type: Int): PoseLandmark? = p.getPoseLandmark(type)?.takeIf { it.inFrameLikelihood > 0.6f }
        val nose = lm(PoseLandmark.NOSE) ?: return false
        val le = lm(PoseLandmark.LEFT_EYE) ?: return false
        val re = lm(PoseLandmark.RIGHT_EYE) ?: return false
        val ls = lm(PoseLandmark.LEFT_SHOULDER) ?: return false
        val rs = lm(PoseLandmark.RIGHT_SHOULDER) ?: return false

        val shoulderW = abs(ls.position.x - rs.position.x)
        val shoulderY = (ls.position.y + rs.position.y) / 2f
        val headH = shoulderY - nose.position.y
        if (headH <= 0f) return false
        // De frente: hombros anchos respecto a la cabeza
        if (shoulderW < headH * 0.9f) return false
        // Mirando a cámara: ojos a ambos lados de la nariz de forma parecida
        val dl = abs(le.position.x - nose.position.x)
        val dr = abs(re.position.x - nose.position.x)
        if (min(dl, dr) / max(max(dl, dr), 1f) < 0.4f) return false

        fun armUp(wristType: Int, elbowType: Int, shoulder: PoseLandmark): Boolean {
            val wrist = lm(wristType) ?: return false
            if (wrist.position.y > nose.position.y - headH * 0.4f) return false
            val elbow = lm(elbowType)
            return elbow == null || elbow.position.y < shoulder.position.y
        }
        return armUp(PoseLandmark.LEFT_WRIST, PoseLandmark.LEFT_ELBOW, ls) ||
            armUp(PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_ELBOW, rs)
    }
}

package com.padelgrabber.training

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.choice
import com.padelgrabber.util.label
import com.padelgrabber.util.screen
import com.padelgrabber.util.title
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menú de entrenamiento: análisis en directo, análisis de vídeo, guía y progreso. */
class TrainingActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        val sessions = TrainingStore.list(this)
        val fmt = SimpleDateFormat("d MMM · HH:mm", Locale("es", "ES"))
        screen {
            title("Entrenamiento")
            label("Analiza la biomecánica de tus golpes con la cámara del móvil.")
            label("Juegas con la mano…")
            choice(listOf("Derecha (diestro)", "Izquierda (zurdo)"), if (TrainingStore.rightHanded(context)) 0 else 1) {
                TrainingStore.setRightHanded(context, it == 0)
            }
            button("Analizar golpes en directo", primary = true) {
                startActivity(Intent(this@TrainingActivity, LiveTrainingActivity::class.java))
            }
            button("Analizar un vídeo de la galería") {
                startActivity(Intent(this@TrainingActivity, VideoAnalysisActivity::class.java))
            }
            button("Guía de biomecánica del pádel") {
                startActivity(Intent(this@TrainingActivity, GuideActivity::class.java))
            }

            title("Tu progreso", 20f)
            if (sessions.isEmpty()) {
                label("Aquí verás tu nota media por golpe cuando hayas analizado alguno.")
            } else {
                val all = sessions.flatMap { it.strokes }
                val byStroke = all.groupBy { it.optString("stroke") }
                for (s in Stroke.values()) {
                    val list = byStroke[s.name] ?: continue
                    val avg = list.map { it.optInt("score") }.average().toInt()
                    val recent = list.take(5).map { it.optInt("score") }.average().toInt()
                    label("${s.title}: media $avg/100 en ${list.size} golpes · últimos 5: $recent", 16f, ResultCard.scoreColor(avg))
                }
                title("Sesiones", 18f)
                for (ses in sessions.take(12)) {
                    val avg = ses.strokes.map { it.optInt("score") }.average().toInt()
                    label("${fmt.format(Date(ses.start))} · ${if (ses.source == "video") "vídeo" else "directo"} · " +
                        "${ses.strokes.size} golpes · media $avg/100", 14f, Ui.GREY)
                }
            }
            label(
                "Las medidas se toman en 2D con la cámara y los rangos son orientativos para jugadores aficionados. " +
                    "Úsalos como guía junto a tu entrenador.", 12f, Ui.GREY
            )
        }
    }
}

package com.padelgrabber.training

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.label
import com.padelgrabber.util.screen
import com.padelgrabber.util.title

/** Guía de biomecánica: lista de golpes y temas, y la ficha de cada uno. */
class GuideActivity : ComponentActivity() {

    companion object {
        const val EXTRA_STROKE = "stroke"
        const val EXTRA_TOPIC = "topic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stroke = Stroke.byName(intent.getStringExtra(EXTRA_STROKE))
        val topic = intent.getIntExtra(EXTRA_TOPIC, -1)
        when {
            stroke != null -> showStroke(stroke)
            topic in GeneralGuide.topics.indices -> showTopic(topic)
            else -> showIndex()
        }
    }

    private fun showIndex() {
        screen {
            title("Biomecánica del pádel")
            label("Cómo se mueve el cuerpo en cada golpe, qué mide la app y qué errores corregir.")
            title("Golpes", 20f)
            for (s in Stroke.values()) {
                button(s.title) {
                    startActivity(Intent(this@GuideActivity, GuideActivity::class.java).putExtra(EXTRA_STROKE, s.name))
                }
            }
            title("Fundamentos", 20f)
            GeneralGuide.topics.forEachIndexed { i, (name, _) ->
                button(name) {
                    startActivity(Intent(this@GuideActivity, GuideActivity::class.java).putExtra(EXTRA_TOPIC, i))
                }
            }
        }
    }

    private fun showStroke(s: Stroke) {
        screen {
            title(s.title)
            label(s.summary, 16f, Ui.WHITE)
            for (sec in s.sections) {
                title(sec.title, 19f, Ui.LIME)
                for (item in sec.items) label("•  $item", 15f, Ui.WHITE)
            }
            title("Qué mide la app", 19f, Ui.LIME)
            for ((metric, range) in s.ranges) {
                label("•  ${metric.label}: ${range.text(metric.unit)}", 15f, Ui.WHITE)
            }
            label("Dónde poner el móvil: ${s.camera}", 15f, Ui.GREY)
            button("Analizar mi ${s.title.lowercase()}", primary = true) {
                startActivity(
                    Intent(this@GuideActivity, LiveTrainingActivity::class.java)
                        .putExtra(LiveTrainingActivity.EXTRA_STROKE, s.name)
                )
            }
        }
    }

    private fun showTopic(i: Int) {
        val (name, sections) = GeneralGuide.topics[i]
        screen {
            title(name)
            for (sec in sections) {
                title(sec.title, 19f, Ui.LIME)
                for (item in sec.items) label("•  $item", 15f, Ui.WHITE)
            }
        }
    }
}

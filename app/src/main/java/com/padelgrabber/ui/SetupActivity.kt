package com.padelgrabber.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.padelgrabber.match.Match
import com.padelgrabber.match.MatchStore
import com.padelgrabber.record.RecordActivity
import com.padelgrabber.score.MatchConfig
import com.padelgrabber.util.Prefs
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.choice
import com.padelgrabber.util.input
import com.padelgrabber.util.label
import com.padelgrabber.util.screen
import com.padelgrabber.util.title
import com.padelgrabber.util.toggle

/** Datos del partido antes de grabar. */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        var bestOf = 3
        var golden = true
        var superTb = false
        var firstServer = 0

        screen {
            title("Nuevo partido")
            label("Pareja A (por ejemplo: Ana / Bea)")
            val teamA = input("Pareja A", prefs.lastTeamA)
            label("Pareja B")
            val teamB = input("Pareja B", prefs.lastTeamB)
            label("Club")
            val club = input("Club", prefs.club)
            label("Pista")
            val court = input("Pista (número o nombre)", prefs.lastCourt)

            title("Reglas", 20f)
            label("Formato")
            choice(listOf("Mejor de 3 sets", "1 set"), 0) { bestOf = if (it == 0) 3 else 1 }
            toggle("Punto de oro en 40-40", golden) { golden = it }
            toggle("Tercer set como super tie-break a 10", superTb) { superTb = it }
            label("Saca primero")
            choice(listOf("Pareja A", "Pareja B"), 0) { firstServer = it }

            title("Durante la grabación", 20f)
            toggle("Autopausa en descansos y cambios de lado", prefs.autoPause) { prefs.autoPause = it }
            toggle("Detectar cada punto y poner cortinilla", prefs.autoRally) { prefs.autoRally = it }
            toggle("Videoarbitraje con el gesto del brazo", prefs.gestureReview) { prefs.gestureReview = it }
            toggle("Emitir en directo por la wifi del club", prefs.liveWifi) { prefs.liveWifi = it }
            toggle("Calidad 1080p (si no, 720p: ocupa menos)", prefs.fullHd) { prefs.fullHd = it }

            label(
                "Consejo: pon el móvil en horizontal, detrás de la pista y a algo de altura, " +
                    "con toda la pista dentro del encuadre. Puedes apuntar puntos con los botones de volumen " +
                    "o con un mando Bluetooth de fotos (subir volumen = A, bajar = B)."
            )

            button("Preparar la cámara", primary = true) {
                val a = teamA.text.toString().trim().ifBlank { "Pareja A" }
                val b = teamB.text.toString().trim().ifBlank { "Pareja B" }
                prefs.lastTeamA = a
                prefs.lastTeamB = b
                prefs.club = club.text.toString().trim()
                prefs.lastCourt = court.text.toString().trim()
                val m = Match(id = Match.newId(), createdAt = System.currentTimeMillis())
                m.teamA = a
                m.teamB = b
                m.club = prefs.club
                m.court = prefs.lastCourt
                m.config = MatchConfig(bestOf = bestOf, goldenPoint = golden, superTiebreak = superTb, firstServer = firstServer)
                try {
                    MatchStore.save(this@SetupActivity, m)
                } catch (e: Exception) {
                    Ui.toast(this@SetupActivity, "No se pudo crear el partido: ${e.message}")
                    return@button
                }
                val i = Intent(this@SetupActivity, RecordActivity::class.java)
                i.putExtra(RecordActivity.EXTRA_ID, m.id)
                startActivity(i)
                finish()
            }
        }
    }
}

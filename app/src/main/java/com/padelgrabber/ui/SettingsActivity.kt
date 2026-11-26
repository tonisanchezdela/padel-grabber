package com.padelgrabber.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.padelgrabber.util.Prefs
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.choice
import com.padelgrabber.util.input
import com.padelgrabber.util.label
import com.padelgrabber.util.screen
import com.padelgrabber.util.title
import com.padelgrabber.util.toggle

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        screen {
            title("Ajustes")

            title("Plataforma (Supabase)", 20f)
            label("Crea un proyecto gratis en supabase.com, ejecuta el archivo supabase/setup.sql y copia aquí la URL y la clave pública «Publishable key» (Settings › API Keys).")
            val url = input("https://xxxx.supabase.co", p.supabaseUrl)
            val key = input("Publishable key (sb_publishable_…)", p.supabaseKey, password = true)
            val bucket = input("Bucket", p.bucket)
            label("Dirección de tu web de vídeos (opcional, la de GitHub Pages)")
            val web = input("https://usuario.github.io/padel-grabber/", p.webUrl)
            toggle("Al terminar, crear resumen y repeticiones solo", p.autoProcess) { p.autoProcess = it }
            toggle("Al terminar, subir los vídeos solo", p.autoUpload) { p.autoUpload = it }

            title("Grabación", 20f)
            toggle("Calidad 1080p (si no, 720p)", p.fullHd) { p.fullHd = it }
            toggle("Autopausa", p.autoPause) { p.autoPause = it }
            label("Segundos sin movimiento para pausar sola")
            val inactivity = input("45", p.inactivitySec.toString(), numeric = true)
            label("Sensibilidad del detector de movimiento")
            choice(listOf("1", "2", "3", "4", "5"), p.motionSensitivity - 1) { p.motionSensitivity = it + 1 }
            toggle("Detectar cada punto y poner cortinilla", p.autoRally) { p.autoRally = it }
            toggle("Videoarbitraje con el gesto del brazo", p.gestureReview) { p.gestureReview = it }

            title("Directo por wifi", 20f)
            toggle("Emitir en la wifi del club", p.liveWifi) { p.liveWifi = it }
            label("PIN para apuntar puntos desde otros móviles")
            val pin = input("PIN", p.pin, numeric = true)
            label("Nombre de este móvil cuando hace de cámara extra")
            val cam = input("Cámara fondo", p.cameraName)

            button("Guardar", primary = true) {
                p.supabaseUrl = url.text.toString()
                p.supabaseKey = key.text.toString()
                p.bucket = bucket.text.toString()
                p.webUrl = web.text.toString()
                p.inactivitySec = inactivity.text.toString().toIntOrNull() ?: 45
                p.setPin(pin.text.toString())
                p.cameraName = cam.text.toString()
                Ui.toast(this@SettingsActivity, "Guardado")
                finish()
            }
        }
    }
}

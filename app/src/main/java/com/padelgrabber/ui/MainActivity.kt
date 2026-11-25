package com.padelgrabber.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.padelgrabber.camera.CameraModeActivity
import com.padelgrabber.overlay.OverlayRenderer
import com.padelgrabber.training.TrainingActivity
import com.padelgrabber.util.Prefs
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.dp
import com.padelgrabber.util.label
import com.padelgrabber.util.screen
import com.padelgrabber.viewer.ViewerActivity

/** Dibuja el logo de la marca. */
class LogoView(ctx: Context) : View(ctx) {
    private val r = OverlayRenderer()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = height * 0.62f
        r.drawLogo(canvas, width / 2f, height / 2f, size, onDark = true)
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val prefs = Prefs(this)
        screen {
            addView(LogoView(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(90)))
            label("Graba, emite y resume tus partidas con el móvil.", 16f, Ui.GREY)
            button("Grabar un partido", primary = true) { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }
            button("Cámara extra (otro ángulo)") { startActivity(Intent(this@MainActivity, CameraModeActivity::class.java)) }
            button("Ver un directo del club") { startActivity(Intent(this@MainActivity, ViewerActivity::class.java)) }
            button("Entrenamiento y biomecánica") { startActivity(Intent(this@MainActivity, TrainingActivity::class.java)) }
            button("Mis partidos") { startActivity(Intent(this@MainActivity, MatchesActivity::class.java)) }
            if (prefs.webUrl.startsWith("http")) {
                button("Plataforma de vídeos") {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.webUrl)))
                }
            }
            button("Ajustes") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            if (!prefs.uploadConfigured) {
                label("Para subir los vídeos a la plataforma, configura Supabase en Ajustes.", 13f, Ui.GREY)
            }
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) { "" }
            label("Padel Grabber $version", 12f, Ui.GREY)
        }
    }
}

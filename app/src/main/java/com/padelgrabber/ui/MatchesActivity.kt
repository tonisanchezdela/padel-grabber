package com.padelgrabber.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.padelgrabber.match.Match
import com.padelgrabber.match.MatchStore
import com.padelgrabber.util.Ui
import com.padelgrabber.util.dp
import com.padelgrabber.util.label
import com.padelgrabber.util.lp
import com.padelgrabber.util.screen
import com.padelgrabber.util.title
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista de partidos grabados en este móvil. */
class MatchesActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        val matches = MatchStore.list(this)
        val fmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale("es", "ES"))
        screen {
            title("Mis partidos")
            if (matches.isEmpty()) label("Todavía no has grabado ningún partido.")
            for (m in matches) {
                val card = LinearLayout(context)
                card.orientation = LinearLayout.VERTICAL
                card.background = Ui.rounded(Ui.NAVY2, context.dp(14).toFloat())
                card.setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
                val t = TextView(context)
                t.text = if (m.role == Match.ROLE_CAMERA) "${m.title} · ${m.cameraName}" else m.title
                t.setTextColor(Ui.WHITE)
                t.typeface = Ui.BOLD
                t.textSize = 18f
                card.addView(t)
                val sub = TextView(context)
                val res = m.resultText.ifBlank { "sin resultado" }
                val state = when (m.status) {
                    Match.STATUS_UPLOADED -> "subido"
                    Match.STATUS_PROCESSED -> "vídeos listos"
                    Match.STATUS_RECORDED -> "grabado"
                    Match.STATUS_RECORDING -> "grabación incompleta"
                    else -> "sin grabar"
                }
                sub.text = "${fmt.format(Date(m.createdAt))}\n$res · $state"
                sub.setTextColor(Ui.GREY)
                sub.textSize = 14f
                card.addView(sub)
                val badge = TextView(context)
                badge.text = when {
                    m.outputs.containsKey(Match.OUT_SUMMARY) -> "RESUMEN"
                    m.role == Match.ROLE_CAMERA -> "ÁNGULO"
                    else -> ""
                }
                badge.setTextColor(Ui.LIME)
                badge.typeface = Ui.BOLD
                badge.gravity = Gravity.END
                card.addView(badge)
                card.setOnClickListener {
                    val i = Intent(this@MatchesActivity, MatchDetailActivity::class.java)
                    i.putExtra(MatchDetailActivity.EXTRA_ID, m.id)
                    startActivity(i)
                }
                addView(card, lp(12))
            }
        }
    }
}

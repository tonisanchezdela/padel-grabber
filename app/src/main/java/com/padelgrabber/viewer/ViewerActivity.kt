package com.padelgrabber.viewer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.padelgrabber.net.Discovery
import com.padelgrabber.record.RecordActivity
import com.padelgrabber.util.Ui
import com.padelgrabber.util.button
import com.padelgrabber.util.label
import com.padelgrabber.util.screen
import com.padelgrabber.util.title

/** Ver el directo de un partido del club (busca los móviles que graban en la wifi). */
class ViewerActivity : ComponentActivity() {

    private var discovery: Discovery? = null
    private var web: WebView? = null
    private val found = LinkedHashMap<String, Discovery.Found>()
    private lateinit var list: LinearLayout
    private lateinit var info: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showList()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web != null) showList() else finish()
            }
        })
    }

    override fun onDestroy() {
        discovery?.close()
        web?.destroy()
        super.onDestroy()
    }

    private fun showList() {
        web?.destroy()
        web = null
        screen {
            title("Ver directo")
            label("Conéctate a la wifi del club. Aquí aparecen los partidos que se están grabando.")
            info = label("Buscando…", 15f, Ui.LIME)
            list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
            button("Escribir la IP a mano") { askIp() }
        }
        renderList()
        val d = discovery ?: Discovery(this).also { discovery = it }
        d.discover { f ->
            if (f.name.startsWith("PadelGrabber")) {
                found[f.host + ":" + f.port] = f
                renderList()
            }
        }
        handler.postDelayed({ if (found.isEmpty() && web == null) info.text = "Todavía no hay partidos en esta wifi. Puedes escribir la IP del móvil que graba." }, 6000)
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        if (found.isNotEmpty()) info.text = "Partidos en directo:"
        for (f in found.values) {
            list.button(f.name.removePrefix("PadelGrabber ").ifBlank { f.host }, primary = true) {
                openUrl("http://${f.host}:${f.port}/")
            }
        }
    }

    private fun askIp() {
        val e = EditText(this)
        e.hint = "192.168.1.20"
        AlertDialog.Builder(this).setTitle("IP del móvil que graba")
            .setView(e)
            .setPositiveButton("Ver") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotBlank()) openUrl(if (t.contains(':')) "http://$t/" else "http://$t:${RecordActivity.LIVE_PORT}/")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openUrl(url: String) {
        discovery?.stopDiscovery()
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        w.setBackgroundColor(Ui.NAVY)
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.loadUrl(url)
        web = w
        setContentView(w)
    }
}

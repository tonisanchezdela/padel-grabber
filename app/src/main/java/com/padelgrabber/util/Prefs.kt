package com.padelgrabber.util

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/** Ajustes de la app. */
class Prefs(ctx: Context) {
    companion object {
        // Plataforma del club (la clave "publishable" es pública por diseño)
        const val DEFAULT_URL = "https://nefpqzpxbiqsjdgazdup.supabase.co"
        const val DEFAULT_KEY = "sb_publishable_MlJ9er0M08CmO33DYbasmA_fw-dDQuz"
        const val DEFAULT_WEB = "https://tonisanchezdela.github.io/padel-grabber/"
    }

    private val sp: SharedPreferences = ctx.getSharedPreferences("padel_grabber", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = sp.getString("sb_url", null)?.ifBlank { null } ?: DEFAULT_URL
        set(v) = sp.edit().putString("sb_url", v.trim().trimEnd('/')).apply()

    var supabaseKey: String
        get() = sp.getString("sb_key", null)?.ifBlank { null } ?: DEFAULT_KEY
        set(v) = sp.edit().putString("sb_key", v.trim()).apply()

    var bucket: String
        get() = sp.getString("sb_bucket", "partidos") ?: "partidos"
        set(v) = sp.edit().putString("sb_bucket", v.trim().ifEmpty { "partidos" }).apply()

    var webUrl: String
        get() = sp.getString("web_url", null)?.ifBlank { null } ?: DEFAULT_WEB
        set(v) = sp.edit().putString("web_url", v.trim()).apply()

    var club: String
        get() = sp.getString("club", "") ?: ""
        set(v) = sp.edit().putString("club", v).apply()

    var lastTeamA: String
        get() = sp.getString("teamA", "") ?: ""
        set(v) = sp.edit().putString("teamA", v).apply()

    var lastTeamB: String
        get() = sp.getString("teamB", "") ?: ""
        set(v) = sp.edit().putString("teamB", v).apply()

    var lastCourt: String
        get() = sp.getString("court", "") ?: ""
        set(v) = sp.edit().putString("court", v).apply()

    /** true = 1080p, false = 720p */
    var fullHd: Boolean
        get() = sp.getBoolean("fullhd", false)
        set(v) = sp.edit().putBoolean("fullhd", v).apply()

    var autoPause: Boolean
        get() = sp.getBoolean("autopause", true)
        set(v) = sp.edit().putBoolean("autopause", v).apply()

    /** Segundos sin movimiento antes de pausar sola la grabación. */
    var inactivitySec: Int
        get() = sp.getInt("inactivity", 45)
        set(v) = sp.edit().putInt("inactivity", v.coerceIn(10, 600)).apply()

    /** Sensibilidad del detector de movimiento 1 (poca) .. 5 (mucha). */
    var motionSensitivity: Int
        get() = sp.getInt("motion_sens", 3)
        set(v) = sp.edit().putInt("motion_sens", v.coerceIn(1, 5)).apply()

    var gestureReview: Boolean
        get() = sp.getBoolean("gesture", true)
        set(v) = sp.edit().putBoolean("gesture", v).apply()

    var liveWifi: Boolean
        get() = sp.getBoolean("live", true)
        set(v) = sp.edit().putBoolean("live", v).apply()

    var autoProcess: Boolean
        get() = sp.getBoolean("auto_process", true)
        set(v) = sp.edit().putBoolean("auto_process", v).apply()

    var autoUpload: Boolean
        get() = sp.getBoolean("auto_upload", true)
        set(v) = sp.edit().putBoolean("auto_upload", v).apply()

    var cameraName: String
        get() = sp.getString("cam_name", "") ?: ""
        set(v) = sp.edit().putString("cam_name", v.trim()).apply()

    /** PIN para apuntar puntos desde otros móviles. */
    val pin: String
        get() {
            var p = sp.getString("pin", null)
            if (p == null) {
                p = (1000 + Random.nextInt(9000)).toString()
                sp.edit().putString("pin", p).apply()
            }
            return p
        }

    fun setPin(v: String) {
        val clean = v.filter { it.isDigit() }.take(6)
        if (clean.length >= 4) sp.edit().putString("pin", clean).apply()
    }

    /** Id estable de este móvil como cámara. */
    val deviceId: String
        get() {
            var d = sp.getString("device_id", null)
            if (d == null) {
                d = "cam" + Random.nextInt(100000, 999999)
                sp.edit().putString("device_id", d).apply()
            }
            return d
        }

    val uploadConfigured: Boolean get() = supabaseUrl.startsWith("http") && supabaseKey.isNotBlank()
}

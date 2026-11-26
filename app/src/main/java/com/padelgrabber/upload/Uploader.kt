package com.padelgrabber.upload

import android.util.Base64
import com.padelgrabber.match.Match
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Sube los vídeos a Supabase Storage (subida por trozos TUS, aguanta cortes)
 * y registra el partido en la tabla "partidos" y sus vídeos en "videos".
 */
class FatalUpload(msg: String) : IOException(msg)

class Uploader(baseUrl: String, private val key: String, private val bucket: String) {

    companion object {
        private const val CHUNK = 6 * 1024 * 1024 // Supabase exige trozos de 6 MB
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/offset+octet-stream".toMediaType()
    }

    private val url = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Claves nuevas (sb_publishable_...) van solo en "apikey".
     * Las antiguas (anon, un JWT "eyJ...") también en "Authorization".
     */
    private fun Request.Builder.auth(): Request.Builder {
        header("apikey", key)
        if (key.startsWith("eyJ")) header("Authorization", "Bearer $key")
        return this
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    fun publicUrl(objectName: String) = "$url/storage/v1/object/public/$bucket/$objectName"

    /** Sube un archivo y devuelve su URL pública. progress(bytesSubidos, total). */
    fun upload(file: File, objectName: String, contentType: String, progress: (Long, Long) -> Unit): String {
        val total = file.length()
        if (total <= 0) throw IOException("El archivo está vacío")
        val meta = listOf(
            "bucketName" to bucket,
            "objectName" to objectName,
            "contentType" to contentType,
            "cacheControl" to "3600"
        ).joinToString(",") { "${it.first} ${b64(it.second)}" }

        val create = Request.Builder()
            .url("$url/storage/v1/upload/resumable")
            .auth()
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", total.toString())
            .header("Upload-Metadata", meta)
            .header("x-upsert", "true")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        var location = http.newCall(create).execute().use { r ->
            if (r.code !in 200..299) throw IOException(explain(r.code, r.body?.string()))
            r.header("Location") ?: throw IOException("El servidor no devolvió dónde subir")
        }
        if (location.startsWith("/")) {
            val scheme = url.substringBefore("://")
            val host = url.substringAfter("://").substringBefore("/")
            location = "$scheme://$host$location"
        }

        var offset = 0L
        val buf = ByteArray(CHUNK)
        RandomAccessFile(file, "r").use { raf ->
            var failures = 0
            while (offset < total) {
                val n = minOf(CHUNK.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buf, 0, n)
                val req = Request.Builder()
                    .url(location)
                    .auth()
                            .header("Tus-Resumable", "1.0.0")
                    .header("Upload-Offset", offset.toString())
                    .patch(buf.toRequestBody(OCTET, 0, n))
                    .build()
                try {
                    http.newCall(req).execute().use { r ->
                        when {
                            r.code in 200..299 -> {
                                offset = r.header("Upload-Offset")?.toLongOrNull() ?: (offset + n)
                                failures = 0
                            }
                            r.code == 409 -> offset = currentOffset(location) ?: offset
                            else -> throw FatalUpload(explain(r.code, r.body?.string()))
                        }
                        Unit
                    }
                } catch (e: IOException) {
                    if (e is FatalUpload) throw e
                    failures++
                    if (failures > 5) throw e
                    Thread.sleep(2000L * failures)
                    offset = currentOffset(location) ?: offset
                }
                progress(offset, total)
            }
        }
        return publicUrl(objectName)
    }

    private fun currentOffset(location: String): Long? = try {
        val req = Request.Builder().url(location).head()
            .auth()
            .header("Tus-Resumable", "1.0.0").build()
        http.newCall(req).execute().use { r -> r.header("Upload-Offset")?.toLongOrNull() }
    } catch (e: Exception) {
        null
    }

    private fun explain(code: Int, body: String?): String {
        val b = body ?: ""
        return when {
            code == 413 || b.contains("Maximum size", true) || b.contains("too large", true) ->
                "El vídeo supera el tamaño máximo de tu plan de Supabase (50 MB en el plan gratuito)."
            code == 401 || code == 403 -> "Supabase rechazó la subida: revisa la clave y las reglas (setup.sql). [$code]"
            code == 404 -> "No existe el bucket «$bucket» en Supabase. Ejecuta setup.sql. [$code]"
            else -> "Error $code de Supabase: ${b.take(200)}"
        }
    }

    private fun postRest(table: String, conflict: String, body: String) {
        val req = Request.Builder()
            .url("$url/rest/v1/$table?on_conflict=$conflict")
            .auth()
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code !in 200..299) throw IOException("No se pudo guardar en «$table»: ${r.code} ${r.body?.string()?.take(200)}")
        }
    }

    /** Registra (o actualiza) el partido en la plataforma. */
    fun saveMatch(m: Match) {
        val o = JSONObject()
            .put("id", m.linkedMatchId)
            .put("fecha", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date(m.createdAt)))
            .put("club", m.club)
            .put("pista", m.court)
            .put("equipo_a", m.teamA)
            .put("equipo_b", m.teamB)
            .put("resultado", m.resultText)
            .put("ganador", m.winner)
            .put("duracion_s", m.durationMs / 1000)
            .put("puntos", m.points.size)
        postRest("partidos", "id", JSONArray().put(o).toString())
    }

    fun saveVideo(matchId: String, tipo: String, nombre: String, urlPublica: String, bytes: Long) {
        val o = JSONObject()
            .put("partido_id", matchId)
            .put("tipo", tipo)
            .put("nombre", nombre)
            .put("url", urlPublica)
            .put("bytes", bytes)
        postRest("videos", "partido_id,nombre", JSONArray().put(o).toString())
    }
}

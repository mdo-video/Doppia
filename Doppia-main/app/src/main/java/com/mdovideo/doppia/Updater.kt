package com.mdovideo.doppia

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Aggiornamenti via GitHub: legge l'ultima release del repository e scarica l'APK allegato. */
object Updater {
    /** build = numero progressivo della compilazione (dal tag "build-N"); il versionCode installato e' N + 1. */
    class Release(val tag: String, val build: Int, val name: String, val apkUrl: String, val size: Long, val notes: String)

    /** Ultima release con un APK, oppure null. Lancia un errore con messaggio leggibile se GitHub non risponde. */
    fun latest(repo: String): Release? {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Doppia")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw IllegalStateException("repository non trovato, privato o ancora senza release")
                403, 429 -> throw IllegalStateException("troppe richieste a GitHub, riprova piu' tardi")
                else -> throw IllegalStateException("GitHub ha risposto $code")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name")
            val build = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(tag, build, j.optString("name", tag).ifBlank { tag },
                        a.getString("browser_download_url"), a.optLong("size", 0L), j.optString("body", ""))
                }
            }
            return null
        } catch (e: java.io.IOException) {
            throw IllegalStateException("nessuna connessione a internet")
        } finally {
            c.disconnect()
        }
    }

    /** Scarica l'APK in un file temporaneo e lo rinomina solo a download completo. */
    fun download(url: String, dest: File, expected: Long, cancelled: () -> Boolean, onProgress: (Int) -> Unit) {
        val part = File(dest.parentFile, dest.name + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Doppia")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub ha risposto ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else expected
            c.inputStream.use { inp ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        if (cancelled()) throw IllegalStateException("annullato")
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceAtMost(100)
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (expected > 0 && part.length() != expected) throw IllegalStateException("file incompleto")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IllegalStateException("impossibile salvare il file")
        } finally {
            c.disconnect()
            if (part.exists()) part.delete()
        }
    }
}

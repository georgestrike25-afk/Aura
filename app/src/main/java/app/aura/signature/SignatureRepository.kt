package app.aura.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.aura.BuildConfig
import app.aura.core.signature.Signature
import app.aura.core.signature.SignatureCatalog
import app.aura.core.signature.SignatureWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga y guarda en el teléfono el catálogo de Aura Signature y sus imágenes. Todo queda en caché:
 * sin conexión se ve lo ya descargado. Las obras se bajan solo al elegirlas (miniaturas ~40 KB, obra ~300 KB).
 */
class SignatureRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "signature").apply { mkdirs() }
    private val catalogFile = File(dir, "catalog.json")
    private val baseFile = File(dir, "base.txt")

    /** Catálogo guardado (puede ser null la primera vez sin conexión). */
    fun cached(): Pair<SignatureCatalog, String>? {
        val c = catalogFile.takeIf { it.exists() }?.readText()?.let(Signature::parse) ?: return null
        return c to (baseFile.takeIf { it.exists() }?.readText() ?: Signature.BASE_URLS.last())
    }

    /** Descarga el catálogo más reciente; si falla, devuelve el guardado (o el error si no hay). */
    suspend fun refresh(): Result<Pair<SignatureCatalog, String>> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (base in Signature.BASE_URLS) {
            try {
                val text = get(Signature.url(base, "catalog.json")).toString(Charsets.UTF_8)
                val catalog = Signature.parse(text) ?: continue
                catalogFile.writeText(text)
                baseFile.writeText(base)
                return@withContext Result.success(catalog to base)
            } catch (e: IOException) {
                lastError = e
            }
        }
        cached()?.let { Result.success(it) } ?: Result.failure(lastError ?: IOException("Catálogo no disponible"))
    }

    /** Miniatura (descargada una vez). */
    suspend fun thumb(base: String, w: SignatureWork): Bitmap? = withContext(Dispatchers.IO) {
        val f = fetch(base, w.thumb, "thumb_${w.id}.webp") ?: return@withContext null
        BitmapFactory.decodeFile(f.path)
    }

    /** Obra completa en disco, lista para abrir en Studio. */
    suspend fun full(base: String, w: SignatureWork): File? = withContext(Dispatchers.IO) { fetch(base, w.image, "full_${w.id}.webp") }

    private fun fetch(base: String, path: String, name: String): File? {
        val f = File(dir, name)
        if (f.exists() && f.length() > 0) return f
        return runCatching {
            val bytes = get(Signature.url(base, path))
            val tmp = File(dir, "$name.part")
            tmp.writeBytes(bytes)
            // Solo se guarda si es una imagen que Android puede leer.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, opts)
            if (opts.outWidth <= 0) { tmp.delete(); null } else f.also { tmp.renameTo(it) }
        }.getOrNull()
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Aura/${BuildConfig.VERSION_NAME} (Android)")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("Archivo demasiado grande")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 12 * 1024 * 1024
    }
}

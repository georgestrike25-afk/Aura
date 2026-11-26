package app.aura.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
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
import java.security.MessageDigest

/**
 * Descarga y guarda en el teléfono el catálogo de Aura Signature y sus imágenes. Todo queda en caché:
 * sin conexión se ve lo ya descargado. Las obras se bajan solo al elegirlas (miniaturas ~40 KB, obra ~300 KB).
 */
class SignatureRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "signature-photos").apply { mkdirs() }
    private val catalogFile = File(dir, "catalog.json")
    private val prefs = context.applicationContext.getSharedPreferences("signature-photos", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun saveFavorites(ids: Set<String>) {
        prefs.edit().putStringSet("favorites", ids).apply()
    }

    /** Catálogo guardado (puede ser null la primera vez sin conexión). */
    fun cached(): Pair<SignatureCatalog, String>? {
        val c = catalogFile.takeIf { it.exists() }?.readText()?.let(Signature::parse)?.takeIf { it.version == 2 } ?: return null
        return c to Signature.PHOTO_BASE_URL
    }

    /** Descarga el catálogo más reciente; si falla, devuelve el guardado (o el error si no hay). */
    suspend fun refresh(): Result<Pair<SignatureCatalog, String>> = withContext(Dispatchers.IO) {
        val base = Signature.PHOTO_BASE_URL
        try {
            val url = Signature.url(base, Signature.PHOTO_CATALOG) + "?refresh=" + System.currentTimeMillis()
            val text = get(url).toString(Charsets.UTF_8)
            val catalog = Signature.parse(text)?.takeIf { it.version == 2 }
                ?: throw IOException("Catálogo inválido")
            val atomic = AtomicFile(catalogFile)
            val output = atomic.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (e: IOException) {
                atomic.failWrite(output)
                throw e
            }
            Result.success(catalog to base)
        } catch (e: IOException) {
            cached()?.let { Result.success(it) } ?: Result.failure(e)
        }
    }

    /** Miniatura (descargada una vez). */
    suspend fun thumb(base: String, w: SignatureWork): Bitmap? = withContext(Dispatchers.IO) {
        val f = fetch(base, w.thumb) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Obra completa en disco, lista para abrir en Studio. */
    suspend fun full(base: String, w: SignatureWork): File? = withContext(Dispatchers.IO) { fetch(base, w.image, 1440 to 3120) }

    suspend fun mask(base: String, w: SignatureWork): File? = withContext(Dispatchers.IO) {
        w.mask?.let { fetch(base, it, 1440 to 3120) }
    }

    private fun fetch(base: String, path: String, expectedSize: Pair<Int, Int>? = null): File? {
        val name = MessageDigest.getInstance("SHA-256").digest((base + path).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val f = File(dir, name)
        if (f.exists() && f.length() > 0) return f
        return runCatching {
            val bytes = get(Signature.url(base, path))
            val tmp = File.createTempFile("photo-", ".part", dir)
            try {
                tmp.writeBytes(bytes)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, opts)
                val size = opts.outWidth to opts.outHeight
                if (opts.outWidth <= 0 || opts.outHeight <= 0 || maxOf(opts.outWidth, opts.outHeight) > 3120 ||
                    (expectedSize != null && size != expectedSize)) null
                else if (tmp.renameTo(f)) f else null
            } finally {
                tmp.delete()
            }
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

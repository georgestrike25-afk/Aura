package app.aura.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.media.ExifInterface
import app.aura.BuildConfig
import app.aura.core.signature.GalleryCollection
import app.aura.core.signature.Signature
import app.aura.core.signature.SignatureCatalog
import app.aura.core.signature.SignatureCollection
import app.aura.core.signature.SignatureWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Galería de Aura Signature: junta las obras publicadas en el repositorio (main y la rama de trabajo,
 * descargadas y guardadas en caché) con las que la persona sube desde el teléfono («Mis obras», que
 * nunca salen del teléfono). Sin conexión se ve lo ya descargado.
 */
class SignatureRepository(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "signature-photos").apply { mkdirs() }
    private val mineDir = File(app.filesDir, "signature-mine").apply { mkdirs() }
    private val mineIndex = File(mineDir, "mine.json")
    private val prefs = app.getSharedPreferences("signature-photos", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun saveFavorites(ids: Set<String>) {
        prefs.edit().putStringSet("favorites", ids).apply()
    }

    private fun cacheFile(base: String) = File(dir, "catalog-" + sha(base).take(16) + ".json")

    private fun cachedSource(base: String): SignatureCatalog? =
        cacheFile(base).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }?.let(Signature::parse)

    /** Galería con lo que ya está en el teléfono (sin red). */
    fun cached(): List<GalleryCollection> =
        mine() + Signature.merge(Signature.BASE_URLS.mapNotNull { b -> cachedSource(b)?.let { it to b } })

    fun hasRemoteCache(): Boolean = Signature.BASE_URLS.any { cacheFile(it).exists() }

    /** Descarga los catálogos publicados; si una carpeta falla se usa su copia guardada. */
    suspend fun refresh(): Result<List<GalleryCollection>> = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        var any = false
        val sources = Signature.BASE_URLS.mapNotNull { base ->
            try {
                val text = get(Signature.url(base, Signature.CATALOG) + "?t=" + System.currentTimeMillis()).toString(Charsets.UTF_8)
                val catalog = Signature.parse(text) ?: throw IOException("Catálogo inválido")
                writeAtomic(cacheFile(base), text.toByteArray(Charsets.UTF_8))
                any = true
                catalog to base
            } catch (e: IOException) {
                lastError = e
                cachedSource(base)?.also { any = true }?.let { it to base }
            }
        }
        if (!any) Result.failure(lastError ?: IOException("Sin catálogo"))
        else Result.success(mine() + Signature.merge(sources))
    }

    // ---------- Mis obras (subidas desde el teléfono) ----------

    private fun mineCatalog(): SignatureCatalog =
        mineIndex.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(SignatureCatalog.serializer(), it.readText()) }.getOrNull() }
            ?: SignatureCatalog(version = 2, collections = emptyList())

    private fun mine(): List<GalleryCollection> {
        val items = mineCatalog().collections.flatMap { it.items }
            .filter { File(mineDir, it.image).isFile && File(mineDir, it.thumb).isFile }
        if (items.isEmpty()) return emptyList()
        return listOf(
            GalleryCollection(
                MINE_ID, "Mis obras", "Imágenes que subiste desde el teléfono. Quedan solo acá.",
                items.reversed().map { app.aura.core.signature.GalleryWork(it, Signature.LOCAL_BASE) },
            ),
        )
    }

    /** Copia imágenes elegidas con el selector de fotos, recortadas a 1440×3120. Devuelve cuántas entraron. */
    suspend fun importImages(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val current = mineCatalog().collections.flatMap { it.items }.toMutableList()
        var added = 0
        for (uri in uris) {
            val bitmap = runCatching { decodeCover(uri, W, H) }.getOrNull() ?: continue
            try {
                val id = "mine-" + System.currentTimeMillis().toString(36) + added
                val image = "$id.webp"
                val thumb = "$id-thumb.webp"
                writeAtomic(File(mineDir, image), encode(bitmap, 92))
                val small = Bitmap.createScaledBitmap(bitmap, W / 4, H / 4, true)
                writeAtomic(File(mineDir, thumb), encode(small, 85))
                small.recycle()
                val title = displayName(uri)?.substringBeforeLast('.')?.replace('_', ' ')?.replace('-', ' ')?.trim()
                    ?.takeIf { it.isNotBlank() }?.take(40) ?: "Mi obra ${current.size + 1}"
                current += SignatureWork(id = id, title = title, image = image, thumb = thumb)
                added++
            } finally {
                bitmap.recycle()
            }
        }
        if (added > 0) saveMine(current)
        added
    }

    fun deleteMine(id: String) {
        val items = mineCatalog().collections.flatMap { it.items }
        val work = items.firstOrNull { it.id == id } ?: return
        File(mineDir, work.image).delete()
        File(mineDir, work.thumb).delete()
        saveMine(items - work)
    }

    private fun saveMine(items: List<SignatureWork>) {
        val catalog = SignatureCatalog(version = 2, collections = listOf(SignatureCollection(MINE_ID, "Mis obras", items = items)))
        writeAtomic(mineIndex, json.encodeToString(SignatureCatalog.serializer(), catalog).toByteArray(Charsets.UTF_8))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Decodifica sin pasarse de memoria, respeta la rotación EXIF y recorta al centro para cubrir w×h. */
    private fun decodeCover(uri: Uri, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = runCatching {
            app.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0
        val (srcW, srcH) = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
        // El mayor submuestreo que todavía cubre w×h sin agrandar.
        val maxSample = minOf(srcW / w.toFloat(), srcH / h.toFloat())
        var sample = 1
        while (sample * 2 <= maxSample) sample *= 2
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val dw = if (rotation % 180 == 0) decoded.width else decoded.height
        val dh = if (rotation % 180 == 0) decoded.height else decoded.width
        val scale = maxOf(w / dw.toFloat(), h / dh.toFloat())
        val m = Matrix().apply {
            postTranslate(-decoded.width / 2f, -decoded.height / 2f)
            postRotate(rotation.toFloat())
            postScale(scale, scale)
            postTranslate(w / 2f, h / 2f)
        }
        Canvas(out).drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        decoded.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }

    // ---------- Imágenes ----------

    private fun local(path: String): File? = File(mineDir, path).takeIf { it.isFile && it.parentFile == mineDir }

    /** Miniatura (descargada una vez). */
    suspend fun thumb(base: String, w: SignatureWork): Bitmap? = withContext(Dispatchers.IO) {
        val f = (if (base == Signature.LOCAL_BASE) local(w.thumb) else fetch(base, w.thumb)) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Obra completa en disco, lista para abrir en Studio. */
    suspend fun full(base: String, w: SignatureWork): File? = withContext(Dispatchers.IO) {
        if (base == Signature.LOCAL_BASE) local(w.image) else fetch(base, w.image, W to H)
    }

    suspend fun mask(base: String, w: SignatureWork): File? = withContext(Dispatchers.IO) {
        if (base == Signature.LOCAL_BASE) null else w.mask?.let { fetch(base, it, W to H) }
    }

    private fun fetch(base: String, path: String, expectedSize: Pair<Int, Int>? = null): File? {
        val f = File(dir, sha(base + path))
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

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (e: IOException) {
            atomic.failWrite(output)
            throw e
        }
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.useCaches = false
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
        const val MINE_ID = "mis-obras"
        private const val W = 1440
        private const val H = 3120
    }
}

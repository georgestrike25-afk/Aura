package app.aura.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.media.ExifInterface
import app.aura.BuildConfig
import app.aura.core.signature.ContentNames
import app.aura.core.signature.GalleryCollection
import app.aura.core.signature.GalleryWork
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
        mine() + visibleRemote(Signature.BASE_URLS.mapNotNull { b -> cachedSource(b)?.let { it to b } })

    fun hasRemoteCache(): Boolean = Signature.BASE_URLS.any { cacheFile(it).exists() }

    /** Descarga los catálogos publicados; si una carpeta falla se usa su copia guardada. */
    suspend fun refresh(): Result<List<GalleryCollection>> = withContext(Dispatchers.IO) {
        purgeLegacy()
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
        else Result.success(mine() + visibleRemote(sources))
    }

    // ---------- Mis obras (subidas desde el teléfono) ----------

    private fun mineCatalog(): SignatureCatalog =
        mineIndex.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(SignatureCatalog.serializer(), it.readText()) }.getOrNull() }
            ?: SignatureCatalog(version = 2, collections = emptyList())

    private fun mine(): List<GalleryCollection> {
        val order = ContentNames.Group.entries.map { it.name.lowercase() }
        return mineCatalog().collections
            .map { col -> col to col.items.filter { it.dhash != null && File(mineDir, it.image).isFile && File(mineDir, it.thumb).isFile } }
            .filter { it.second.isNotEmpty() }
            .sortedBy { (col, _) -> order.indexOf(col.id.removePrefix(MINE_PREFIX)).let { if (it < 0) order.size else it } }
            .map { (col, items) ->
                GalleryCollection(
                    col.id, col.title, "Tus fotos. Quedan solo en el teléfono.",
                    items.reversed().map { GalleryWork(it, Signature.LOCAL_BASE) },
                )
            }
    }

    /** Resultado de subir fotos: cuántas entraron, cuántas ya estaban y cuántas no se pudieron leer. */
    data class ImportResult(val added: Int, val repeated: Int, val failed: Int)

    /**
     * Copia imágenes elegidas con el selector de fotos (enteras, hasta 1440×3120). Reconoce el contenido para
     * agruparlas y nombrarlas, y saltea las que ya están (misma foto aunque tenga otro nombre o tamaño).
     */
    suspend fun importImages(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val collections = mineCatalog().collections.associate { it.id to it }.toMutableMap()
        val known = (collections.values.flatMap { it.items } + remoteWorks()).mapNotNull { it.dhash }.toMutableList()
        val names = collections.values.flatMap { it.items }.map { it.title }.toMutableList()
        var added = 0
        var repeated = 0
        var failed = 0
        for (uri in uris) {
            val bitmap = runCatching { decodeFit(uri, W, H) }.getOrNull()
            if (bitmap == null) { failed++; continue }
            try {
                val hash = fingerprintOf(bitmap)
                if (known.any { ContentNames.same(it, hash) }) { repeated++; continue }
                val small = Bitmap.createScaledBitmap(bitmap, 360, (360f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
                val content = recognize(bitmap)
                val hex = hash
                val id = "mine-" + hash.take(16) + "-" + System.currentTimeMillis().toString(36)
                val image = "$id.webp"
                val thumb = "$id-thumb.webp"
                writeAtomic(File(mineDir, image), encode(bitmap, 92))
                writeAtomic(File(mineDir, thumb), encode(small, 85))
                small.recycle()
                val title = ContentNames.unique(content.name, names)
                names += title
                known += hash
                val colId = MINE_PREFIX + content.group.name.lowercase()
                val col = collections[colId] ?: SignatureCollection(colId, content.group.title)
                collections[colId] = col.copy(items = col.items + SignatureWork(id = id, title = title, image = image, thumb = thumb, dhash = hex, width = bitmap.width, height = bitmap.height))
                added++
            } finally {
                bitmap.recycle()
            }
        }
        if (added > 0) saveMine(collections.values.toList())
        ImportResult(added, repeated, failed)
    }

    /** Qué muestra la foto: etiquetas + caras (en una copia de ~720 px, suficiente y rápida). */
    private suspend fun recognize(bitmap: Bitmap): ContentNames.Result {
        val w = minOf(720, bitmap.width)
        val mid = if (w == bitmap.width) bitmap else Bitmap.createScaledBitmap(bitmap, w, (w.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
        try {
            return ContentNames.classify(PhotoLabeler.labels(mid), PhotoLabeler.faceArea(mid))
        } finally {
            if (mid !== bitmap) mid.recycle()
        }
    }

    /**
     * Si la forma de clasificar cambió, vuelve a agrupar y nombrar las fotos ya subidas. Devuelve true si
     * cambió algo.
     */
    suspend fun reclassifyIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (prefs.getInt("labels-version", 1) >= ContentNames.VERSION) return@withContext false
        val items = mineCatalog().collections.flatMap { it.items }.filter { it.dhash != null }
        val collections = LinkedHashMap<String, SignatureCollection>()
        val names = mutableListOf<String>()
        for (item in items) {
            val file = File(mineDir, item.image)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: continue
            val content = try { recognize(bmp) } finally { bmp.recycle() }
            val title = ContentNames.unique(content.name, names)
            names += title
            val colId = MINE_PREFIX + content.group.name.lowercase()
            val col = collections[colId] ?: SignatureCollection(colId, content.group.title)
            collections[colId] = col.copy(items = col.items + item.copy(title = title))
        }
        saveMine(collections.values.toList())
        prefs.edit().putInt("labels-version", ContentNames.VERSION).apply()
        true
    }

    private fun remoteWorks(): List<SignatureWork> =
        Signature.BASE_URLS.mapNotNull { cachedSource(it) }.flatMap { c -> c.collections.flatMap { it.items } }

    /** Huella de la imagen: forma (dHash) + colores (ver ContentNames.fingerprint). */
    private fun fingerprintOf(bitmap: Bitmap): String {
        val tiny = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val gray = IntArray(72) { i ->
            val c = tiny.getPixel(i % 9, i / 9)
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        tiny.recycle()
        val grid = Bitmap.createScaledBitmap(bitmap, 4, 4, true)
        val colors = IntArray(16) { grid.getPixel(it % 4, it / 4) }
        grid.recycle()
        return ContentNames.fingerprint(gray, colors)
    }

    /** Borra todas las fotos subidas desde el teléfono. */
    fun deleteAllMine() {
        mineDir.listFiles()?.forEach { it.delete() }
    }

    /** Quita las fotos subidas con la primera versión (sin huella: podían estar repetidas y mal nombradas). */
    private fun purgeLegacy() {
        val cols = mineCatalog().collections
        val legacy = cols.flatMap { it.items }.filter { it.dhash == null }
        if (legacy.isEmpty()) return
        legacy.forEach { File(mineDir, it.image).delete(); File(mineDir, it.thumb).delete() }
        saveMine(cols.map { c -> c.copy(items = c.items.filter { it.dhash != null }) }.filter { it.items.isNotEmpty() })
    }

    /** Obras publicadas que la persona quitó de su galería (siguen publicadas, pero no se muestran acá). */
    private fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty()

    fun hide(id: String) {
        prefs.edit().putStringSet("hidden", hidden() + id).apply()
    }

    private fun visibleRemote(sources: List<Pair<SignatureCatalog, String>>): List<GalleryCollection> {
        val hide = hidden()
        return Signature.merge(sources).map { c -> c.copy(items = c.items.filterNot { it.work.id in hide }) }.filter { it.items.isNotEmpty() }
    }

    fun deleteMine(id: String) {
        val cols = mineCatalog().collections
        val work = cols.flatMap { it.items }.firstOrNull { it.id == id } ?: return
        File(mineDir, work.image).delete()
        File(mineDir, work.thumb).delete()
        saveMine(cols.map { c -> c.copy(items = c.items.filterNot { it.id == id }) }.filter { it.items.isNotEmpty() })
    }

    private fun saveMine(collections: List<SignatureCollection>) {
        val catalog = SignatureCatalog(version = 2, collections = collections)
        writeAtomic(mineIndex, json.encodeToString(SignatureCatalog.serializer(), catalog).toByteArray(Charsets.UTF_8))
    }

    /**
     * Decodifica sin pasarse de memoria y respeta la rotación EXIF. Conserva la foto entera (sin recortar
     * ni agrandar): solo la achica si pasa de [maxW]×[maxH]. El encuadre se elige después en Studio.
     */
    private fun decodeFit(uri: Uri, maxW: Int, maxH: Int): Bitmap? {
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
        val scale = minOf(1f, maxW / srcW.toFloat(), maxH / srcH.toFloat())
        val outW = (srcW * scale).toInt().coerceAtLeast(1)
        val outH = (srcH * scale).toInt().coerceAtLeast(1)
        var sample = 1
        while (srcW / (sample * 2) >= outW && srcH / (sample * 2) >= outH) sample *= 2
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val dw = if (rotation % 180 == 0) decoded.width else decoded.height
        val k = outW / dw.toFloat()
        val m = Matrix().apply {
            postTranslate(-decoded.width / 2f, -decoded.height / 2f)
            postRotate(rotation.toFloat())
            postScale(k, k)
            postTranslate(outW / 2f, outH / 2f)
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

    /** Obra en buena calidad para el visor (~1080 de ancho: nítida en pantalla y liviana en memoria). */
    suspend fun preview(base: String, w: SignatureWork): Bitmap? = withContext(Dispatchers.IO) {
        val f = (if (base == Signature.LOCAL_BASE) local(w.image) else fetch(base, w.image, W to H)) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
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
        const val MINE_PREFIX = "mine-"
        private const val W = 1440
        private const val H = 3120
    }
}

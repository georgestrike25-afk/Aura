package app.aura.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.StudioProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Proyectos de Aura Studio en almacenamiento interno privado (filesDir/studio/<id>/):
 *   project.json · photo.jpg (copia reducida, nunca se modifica el original) · mask.png · thumb.jpg
 * Nada se sube a ningún servidor.
 */
class ProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "studio").apply { mkdirs() }
    private val activeFile = File(root, "active.txt")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _projects = MutableStateFlow<List<StudioProject>>(emptyList())
    val projects: StateFlow<List<StudioProject>> = _projects.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _projects.value = root.listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { dir -> runCatching { json.decodeFromString<StudioProject>(File(dir, PROJECT).readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
        _activeId.value = activeFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
    }

    fun dir(id: String) = File(root, id)
    fun thumbFile(id: String) = File(dir(id), THUMB)

    /** Importa la foto elegida con el selector del sistema: la copia reducida es la de trabajo. */
    suspend fun create(uri: Uri, name: String, design: ClockDesign? = null, mask: File? = null): StudioProject = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri, MAX_PHOTO_SIDE) ?: error("No se pudo leer la imagen")
        val now = System.currentTimeMillis()
        val project = StudioProject(
            id = UUID.randomUUID().toString(), name = name, createdAt = now, updatedAt = now,
            design = design?.normalized() ?: ClockDesign(), hasMask = mask != null, autoMask = mask != null,
        )
        val folder = dir(project.id).apply { mkdirs() }
        try {
            File(folder, PHOTO).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "No se pudo guardar la foto" }
            }
            mask?.copyTo(File(folder, MASK))
            save(project)
            project
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun save(project: StudioProject, thumb: Bitmap? = null) = withContext(Dispatchers.IO) {
        val d = dir(project.id).apply { mkdirs() }
        writeProject(d, project)
        thumb?.let { File(d, THUMB).outputStream().use { o -> it.compress(Bitmap.CompressFormat.JPEG, 85, o) } }
        refresh()
    }

    suspend fun load(id: String): StudioProject? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<StudioProject>(File(dir(id), PROJECT).readText()) }.getOrNull()
    }

    suspend fun photo(id: String): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(File(dir(id), PHOTO).path)
    }

    /** Máscara base (automática) en ALPHA_8, o null si no hay. */
    suspend fun baseMask(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(dir(id), MASK)
        if (!f.exists()) return@withContext null
        BitmapFactory.decodeFile(f.path)?.let { argb -> argb.extractAlpha().also { argb.recycle() } }
    }

    suspend fun saveBaseMask(id: String, mask: Bitmap?) = withContext(Dispatchers.IO) {
        val f = File(dir(id), MASK)
        if (mask == null) {
            f.delete()
            return@withContext
        }
        // PNG con la máscara en el canal alfa (ALPHA_8 no se puede comprimir directamente).
        val argb = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(mask, 0f, 0f, Paint().apply { color = Color.BLACK })
        f.outputStream().use { argb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        argb.recycle()
    }

    suspend fun duplicate(id: String): StudioProject? = withContext(Dispatchers.IO) {
        val original = load(id) ?: return@withContext null
        val now = System.currentTimeMillis()
        val copy = original.copy(id = UUID.randomUUID().toString(), name = "${original.name} (copia)", createdAt = now, updatedAt = now)
        dir(id).copyRecursively(dir(copy.id), overwrite = true)
        writeProject(dir(copy.id), copy)
        refresh()
        copy
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        if (_activeId.value == id) activeFile.delete()
        refresh()
    }

    /** Proyecto que muestra el fondo animado. */
    suspend fun setActive(id: String) = withContext(Dispatchers.IO) {
        activeFile.writeText(id)
        _activeId.value = id
    }

    fun activeIdBlocking(): String? = activeFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    // ---------------------------------------------------------------- modo aleatorio

    private val rotationFile = File(root, "rotation.txt")
    private val brightnessCache = HashMap<String, Float>()

    fun rotationMode(): app.aura.core.studio.WallpaperRotation.Mode =
        rotationFile.takeIf { it.exists() }?.readText()?.trim()
            ?.let { n -> app.aura.core.studio.WallpaperRotation.Mode.entries.firstOrNull { it.name == n } }
            ?: app.aura.core.studio.WallpaperRotation.Mode.OFF

    fun setRotationMode(mode: app.aura.core.studio.WallpaperRotation.Mode) {
        rotationFile.writeText(mode.name)
    }

    /** Diseño que muestra el fondo ahora: el elegido por el modo aleatorio, o el activo si está apagado. */
    fun displayIdBlocking(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String? {
        val mode = rotationMode()
        if (mode == app.aura.core.studio.WallpaperRotation.Mode.OFF) return activeIdBlocking()
        val ids = root.listFiles { f -> f.isDirectory && File(f, PROJECT).exists() }.orEmpty().map { it.name }
        val light = ids.associateWith { brightnessOf(it) }
        return app.aura.core.studio.WallpaperRotation.pick(mode, ids, light, now) ?: activeIdBlocking()
    }

    /** Luminosidad media de un diseño (de su miniatura), para el modo «Según la hora». */
    private fun brightnessOf(id: String): Float = synchronized(brightnessCache) {
        brightnessCache.getOrPut(id) {
            val f = thumbFile(id)
            val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 }) else null
            if (bmp == null) 0.5f else try {
                var sum = 0f
                var n = 0
                for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
                    val c = bmp.getPixel(x, y)
                    sum += (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
                    n++
                }
                if (n == 0) 0.5f else sum / n
            } finally {
                bmp.recycle()
            }
        }
    }

    @Synchronized
    private fun writeProject(folder: File, project: StudioProject) {
        val file = AtomicFile(File(folder, PROJECT))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(project).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = appContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val s = info.size
                val scale = minOf(1f, maxSide / maxOf(s.width, s.height).toFloat())
                decoder.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val scale = minOf(1f, maxSide / maxOf(decoded.width, decoded.height).toFloat())
            if (scale >= 1f) return decoded
            val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, width, height, true).also { if (it !== decoded) decoded.recycle() }
        }
    }

    companion object {
        /** Suficiente para un fondo QHD+ (1440×3120) con margen para encuadrar. */
        const val MAX_PHOTO_SIDE = 3200
        private const val PROJECT = "project.json"
        private const val PHOTO = "photo.jpg"
        private const val MASK = "mask.png"
        private const val THUMB = "thumb.jpg"
    }
}

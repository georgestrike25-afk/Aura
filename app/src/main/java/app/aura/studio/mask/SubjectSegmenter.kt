package app.aura.studio.mask

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Segmentación del sujeto principal en el dispositivo con ML Kit Subject Segmentation.
 *
 * - Gratis, sin clave ni nube: la foto no sale del teléfono.
 * - El modelo lo provee Google Play Services (se descarga una vez, ~200 KB de APK propio).
 * - Si no está disponible (sin Play Services, modelo aún descargándose, error), devuelve null y el
 *   usuario sigue con el pincel manual.
 */
class SubjectSegmenter {
    private val processing = AtomicBoolean(false)

    sealed interface Result {
        data class Mask(val alpha: Bitmap, val coverage: Float) : Result
        data class Unavailable(val reason: String) : Result
    }

    suspend fun segment(photo: Bitmap, threshold: Float): Result {
        val scale = minOf(1f, MAX_SIDE / maxOf(photo.width, photo.height).toFloat())
        val width = (photo.width * scale).roundToInt().coerceAtLeast(1)
        val height = (photo.height * scale).roundToInt().coerceAtLeast(1)
        // La tarea nativa conserva su propia copia incluso si Studio deja de esperarla.
        return withTimeoutOrNull(6_000L) {
            suspendCancellableCoroutine { cont ->
                val input = if (width == photo.width && height == photo.height) {
                    photo.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    Bitmap.createScaledBitmap(photo, width, height, true)
                }
                if (input == null) {
                    cont.resume(Result.Unavailable("No se pudo preparar la imagen"))
                    return@suspendCancellableCoroutine
                }
                if (!processing.compareAndSet(false, true)) {
                    input.recycle()
                    cont.resume(Result.Unavailable("La detección anterior sigue procesándose"))
                    return@suspendCancellableCoroutine
                }
                val client = try {
                    SubjectSegmentation.getClient(
                        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
                    )
                } catch (e: Exception) {
                    input.recycle()
                    processing.set(false)
                    cont.resume(Result.Unavailable("Segmentación no disponible"))
                    return@suspendCancellableCoroutine
                }
                try {
                    client.process(InputImage.fromBitmap(input, 0))
                        .addOnCompleteListener(Dispatchers.Default.asExecutor()) { task ->
                            try {
                                if (cont.isActive) {
                                    val result = if (task.isSuccessful) {
                                        try {
                                            toMask(task.result.foregroundConfidenceMask, width, height, threshold)
                                        } catch (e: Exception) {
                                            Result.Unavailable("No se pudo leer la máscara")
                                        }
                                    } else {
                                        Result.Unavailable("Segmentación no disponible")
                                    }
                                    cont.resume(result) { _, discarded, _ ->
                                        if (discarded is Result.Mask) discarded.alpha.recycle()
                                    }
                                }
                            } finally {
                                input.recycle()
                                try { client.close() } finally { processing.set(false) }
                            }
                        }
                } catch (e: Exception) {
                    input.recycle()
                    try { client.close() } finally { processing.set(false) }
                    cont.resume(Result.Unavailable("Segmentación no disponible"))
                }
            }
        } ?: Result.Unavailable("La detección tardó demasiado")
    }

    private fun toMask(buffer: FloatBuffer?, width: Int, height: Int, threshold: Float): Result {
        if (buffer == null || buffer.limit() < width * height) return Result.Unavailable("Sin sujeto detectado")
        buffer.rewind()
        val pixels = IntArray(width * height)
        var covered = 0
        for (i in pixels.indices) {
            val confidence = buffer.get()
            val alpha = ((confidence - (threshold - 0.15f)) / 0.3f).coerceIn(0f, 1f)
            if (alpha > 0.5f) covered++
            pixels[i] = (alpha * 255).roundToInt() shl 24
        }
        if (covered == 0) return Result.Unavailable("Sin sujeto detectado")
        val argb = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            Result.Mask(argb.extractAlpha(), covered / pixels.size.toFloat())
        } finally {
            argb.recycle()
        }
    }

    companion object {
        private const val MAX_SIDE = 1024f
    }
}

package app.aura.signature

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reconoce qué hay en una foto (montaña, perro, cielo…) con el modelo de ML Kit de Google Play Services
 * (se baja una sola vez con la app; no suma peso al APK). Corre en el teléfono: la foto no sale de ahí.
 * Si el modelo todavía no está, devuelve una lista vacía y la foto queda en «Otras».
 */
object PhotoLabeler {
    suspend fun labels(bitmap: Bitmap): List<Pair<String, Float>> = withTimeoutOrNull(8_000L) {
        runCatching {
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())
            try {
                suspendCancellableCoroutine<List<Pair<String, Float>>> { cont ->
                    labeler.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { list -> if (cont.isActive) cont.resume(list.map { it.text to it.confidence }) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
                }
            } finally {
                labeler.close()
            }
        }.getOrDefault(emptyList())
    } ?: emptyList()
}

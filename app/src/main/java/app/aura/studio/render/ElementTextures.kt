package app.aura.studio.render

import android.graphics.Bitmap
import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.ElementTexture

/**
 * Texturas de materiales como Bitmap, generadas una sola vez por material (256×256, ~256 KB c/u)
 * y reutilizadas por la vista previa, la exportación y el fondo animado.
 */
object ElementTextures {
    private const val SIZE = 256
    private val cache = HashMap<ClockMaterial, Bitmap>()

    fun tile(m: ClockMaterial): Bitmap = synchronized(cache) {
        cache[m]?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(
            ElementTexture.generate(m, SIZE),
            SIZE, SIZE, Bitmap.Config.ARGB_8888,
        ).also { cache[m] = it }
    }
}

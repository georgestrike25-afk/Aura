package app.aura.core.signature

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Catálogo de Aura Signature: colecciones de fondos originales publicadas como archivos en el
 * repositorio (signature/catalog.json + imágenes). La app lo descarga, así se suman colecciones nuevas
 * sin actualizar el APK.
 */
@Serializable
data class SignatureCatalog(
    val version: Int = 1,
    val license: String = "",
    val collections: List<SignatureCollection> = emptyList(),
)

@Serializable
data class SignatureCollection(
    val id: String,
    val title: String,
    val description: String = "",
    val items: List<SignatureWork> = emptyList(),
)

@Serializable
data class SignatureWork(
    val id: String,
    val title: String,
    /** Rutas relativas a la carpeta del catálogo. */
    val image: String,
    val thumb: String,
    val width: Int = 1440,
    val height: Int = 3120,
    /** Zona libre sugerida para el reloj (izq., arriba, der., abajo; fracciones). */
    val clockArea: List<Float> = listOf(0.08f, 0.12f, 0.92f, 0.42f),
)

object Signature {
    /** Primero main (versión estable); si todavía no existe ahí, la rama de desarrollo. */
    val BASE_URLS = listOf(
        "https://raw.githubusercontent.com/georgestrike25-afk/Aura/main/signature/",
        "https://raw.githubusercontent.com/georgestrike25-afk/Aura/aura-artistic-evolution/signature/",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** null si el texto no es un catálogo válido (nunca inventa contenido). */
    fun parse(body: String): SignatureCatalog? = runCatching { json.decodeFromString(SignatureCatalog.serializer(), body) }
        .getOrNull()
        ?.let { c ->
            c.copy(
                collections = c.collections
                    .map { col -> col.copy(items = col.items.filter { it.id.isSafeId() && it.image.isSafePath() && it.thumb.isSafePath() }) }
                    .filter { it.items.isNotEmpty() },
            )
        }
        ?.takeIf { it.collections.isNotEmpty() }

    fun url(base: String, path: String): String = base + path

    // Defensa: el catálogo es contenido remoto y los ids y rutas se usan como nombres de archivo.
    private fun String.isSafeId() = matches(Regex("[a-z0-9_-]{1,40}"))
    private fun String.isSafePath() = matches(Regex("[a-z0-9_/-]{1,80}\\.(webp|jpg|png)")) && !contains("..")
}

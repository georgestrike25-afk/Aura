package app.aura.core.signature

import app.aura.core.studio.ClockDesign
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
    val mask: String? = null,
    /** Huella visual (forma + colores, ver ContentNames.fingerprint) para no repetir la misma foto. */
    val dhash: String? = null,
    val recommendedDesign: ClockDesign? = null,
    val width: Int = 1440,
    val height: Int = 3120,
    /** Zona libre sugerida para el reloj (izq., arriba, der., abajo; fracciones). */
    val clockArea: List<Float> = listOf(0.08f, 0.12f, 0.92f, 0.42f),
)

/** Una obra lista para mostrar, con la carpeta de donde salen sus imágenes. */
data class GalleryWork(val work: SignatureWork, val base: String)

data class GalleryCollection(val id: String, val title: String, val description: String, val items: List<GalleryWork>)

object Signature {
    /**
     * Carpetas publicadas, en orden de prioridad. Hoy solo `main` (donde publica el gestor de Signature
     * para PC); se dejó como lista por si se suma un servidor de imágenes.
     */
    val BASE_URLS = listOf(
        "https://raw.githubusercontent.com/georgestrike25-afk/Aura/main/signature/",
    )
    const val CATALOG = "catalog.json"

    /** Base de las obras que la persona sube desde el teléfono (no se descargan). */
    const val LOCAL_BASE = "local:"

    private val json = Json { ignoreUnknownKeys = true }

    /** null si el texto no es un catálogo válido (nunca inventa contenido). */
    fun parse(body: String): SignatureCatalog? = runCatching { json.decodeFromString(SignatureCatalog.serializer(), body) }
        .getOrNull()
        ?.let { c ->
            val workIds = mutableSetOf<String>()
            c.copy(
                collections = c.collections.filter { it.id.isSafeId() }.distinctBy { it.id }
                    .map { col -> col.copy(items = col.items.filter { it.isValid() && workIds.add(it.id) }) }
                    .filter { it.items.isNotEmpty() },
            )
        }
        ?.takeIf { it.version in 1..2 && (it.version == 2 || it.collections.isNotEmpty()) }

    /**
     * Junta catálogos de varias carpetas: una colección con el mismo id suma las obras nuevas; si una obra
     * aparece dos veces, gana la de la carpeta con más prioridad (la primera).
     */
    fun merge(sources: List<Pair<SignatureCatalog, String>>): List<GalleryCollection> {
        val seenWorks = mutableSetOf<String>()
        val seenPrints = mutableListOf<String>()
        val out = LinkedHashMap<String, GalleryCollection>()
        for ((catalog, base) in sources) {
            for (col in catalog.collections) {
                val items = col.items.filter { w ->
                    val h = w.dhash
                    val repeated = h != null && seenPrints.any { ContentNames.same(it, h) }
                    if (!repeated && h != null) seenPrints += h
                    !repeated && seenWorks.add(w.id)
                }.map { GalleryWork(it, base) }
                if (items.isEmpty()) continue
                // Una sola colección por nombre (sin importar mayúsculas, tildes ni espacios).
                val key = titleKey(col.title).ifBlank { col.id }
                val prev = out[key]
                out[key] = prev?.copy(items = prev.items + items)
                    ?: GalleryCollection(col.id, col.title, col.description, items)
            }
        }
        return out.values.toList()
    }

    /** Clave para comparar nombres de colección: «Retratos del Mar » = «retratos del mar» = «Retratós del mar». */
    fun titleKey(title: String): String {
        val plain = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        // «Árboles de la vida - Segunda edición», «… parte 2», «… vol 3», «… II» son la misma colección.
        return plain.replace(EDITION, "").trim()
    }

    private val EDITION = Regex(
        "(\\s+(primera|segunda|tercera|cuarta|quinta|sexta|septima|octava|novena|decima|nueva|\\d+(ra|da|ta|va|na|ma)?)" +
            "\\s+(edicion|parte|serie|tanda|coleccion|entrega))+$" +
            "|\\s+(edicion|parte|vol|volumen|serie|tanda|entrega)\\s+(\\d+|[ivx]+)$" +
            "|\\s+(\\d+|ii|iii|iv|v|vi|vii|viii|ix|x)$",
    )

    /**
     * Junta colecciones con el mismo nombre (las de la persona con las publicadas incluidas): queda una sola,
     * con todas sus obras, en el lugar de la primera.
     */
    fun unite(collections: List<GalleryCollection>): List<GalleryCollection> {
        val out = LinkedHashMap<String, GalleryCollection>()
        for (c in collections) {
            val key = titleKey(c.title).ifBlank { c.id }
            val prev = out[key]
            out[key] = prev?.copy(items = prev.items + c.items.filter { w -> prev.items.none { it.work.id == w.work.id } }) ?: c
        }
        return out.values.toList()
    }

    fun url(base: String, path: String): String = base + path

    private fun SignatureWork.isValid(): Boolean =
        id.isSafeId() && image.isSafePath() && thumb.isSafePath() &&
            (mask == null || (mask.endsWith(".png") && mask.isSafePath())) &&
            width == 1440 && height == 3120

    // El catálogo es contenido remoto. Las rutas siempre son relativas al catálogo.
    private fun String.isSafeId() = matches(Regex("[a-z0-9_-]{1,40}"))
    private fun String.isSafePath() = matches(Regex("[a-z0-9_/-]{1,80}\\.(webp|jpg|png)")) && !startsWith("/") && !contains("//") && !contains("..")
}

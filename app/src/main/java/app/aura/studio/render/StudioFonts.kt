package app.aura.studio.render

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import app.aura.R
import java.io.File

/** Familias de Aura Fonts: agrupan las tipografías por carácter. */
enum class FontFamilyGroup(val label: String) {
    EDITORIAL("Editorial"),
    MONUMENT("Monument"),
    ZEN("Zen"),
    FUTURE("Future"),
    POETRY("Poetry"),
    DISPLAY("Display"),
    RETRO("Retro"),
    MINE("Mis fuentes"),
}

/**
 * Fuentes para números incluidas en Aura (Google Fonts, licencia SIL OFL 1.1; ver
 * docs/licencias/fuentes), recortadas a los caracteres del reloj (0–9, «:», a. m./p. m.): las 61
 * ocupan ≈700 KB. Además, fuentes TTF/OTF que la persona importa desde el teléfono (id «user:…»).
 */
object StudioFonts {
    data class Entry(val id: String, val label: String, @FontRes val res: Int, val group: FontFamilyGroup)

    private fun e(id: String, label: String, res: Int, g: FontFamilyGroup) = Entry(id, label, res, g)

    val all = listOf(
        // Editorial: serif con contraste, revistas y libros.
        e("clock_playfair", "Playfair", R.font.clock_playfair, FontFamilyGroup.EDITORIAL),
        e("clock_abril", "Abril Fatface", R.font.clock_abril, FontFamilyGroup.EDITORIAL),
        e("clock_dmserifdisplay", "DM Serif", R.font.clock_dmserifdisplay, FontFamilyGroup.EDITORIAL),
        e("clock_bodonimoda", "Bodoni Moda", R.font.clock_bodonimoda, FontFamilyGroup.EDITORIAL),
        e("clock_italiana", "Italiana", R.font.clock_italiana, FontFamilyGroup.EDITORIAL),
        e("clock_marcellus", "Marcellus", R.font.clock_marcellus, FontFamilyGroup.EDITORIAL),
        e("clock_cinzel", "Cinzel", R.font.clock_cinzel, FontFamilyGroup.EDITORIAL),
        e("clock_yesevaone", "Yeseva", R.font.clock_yesevaone, FontFamilyGroup.EDITORIAL),
        e("clock_alfaslabone", "Alfa Slab", R.font.clock_alfaslabone, FontFamilyGroup.EDITORIAL),
        // Monument: altas, condensadas y con peso.
        e("clock_bebas", "Bebas Neue", R.font.clock_bebas, FontFamilyGroup.MONUMENT),
        e("clock_anton", "Anton", R.font.clock_anton, FontFamilyGroup.MONUMENT),
        e("clock_oswald", "Oswald", R.font.clock_oswald, FontFamilyGroup.MONUMENT),
        e("clock_teko", "Teko", R.font.clock_teko, FontFamilyGroup.MONUMENT),
        e("clock_sixcaps", "Six Caps", R.font.clock_sixcaps, FontFamilyGroup.MONUMENT),
        e("clock_fjallaone", "Fjalla One", R.font.clock_fjallaone, FontFamilyGroup.MONUMENT),
        e("clock_staatliches", "Staatliches", R.font.clock_staatliches, FontFamilyGroup.MONUMENT),
        e("clock_archivoblack", "Archivo Black", R.font.clock_archivoblack, FontFamilyGroup.MONUMENT),
        e("clock_bowlbyone", "Bowlby One", R.font.clock_bowlbyone, FontFamilyGroup.MONUMENT),
        e("clock_russo", "Russo One", R.font.clock_russo, FontFamilyGroup.MONUMENT),
        // Zen: livianas, redondeadas, mucho aire.
        e("clock_poiret", "Poiret One", R.font.clock_poiret, FontFamilyGroup.ZEN),
        e("clock_juliussansone", "Julius Sans", R.font.clock_juliussansone, FontFamilyGroup.ZEN),
        e("clock_quicksand", "Quicksand", R.font.clock_quicksand, FontFamilyGroup.ZEN),
        e("clock_comfortaa", "Comfortaa", R.font.clock_comfortaa, FontFamilyGroup.ZEN),
        e("clock_gruppo", "Gruppo", R.font.clock_gruppo, FontFamilyGroup.ZEN),
        e("clock_rajdhani", "Rajdhani", R.font.clock_rajdhani, FontFamilyGroup.ZEN),
        // Future: geométricas y técnicas.
        e("clock_orbitron", "Orbitron", R.font.clock_orbitron, FontFamilyGroup.FUTURE),
        e("clock_michroma", "Michroma", R.font.clock_michroma, FontFamilyGroup.FUTURE),
        e("clock_audiowide", "Audiowide", R.font.clock_audiowide, FontFamilyGroup.FUTURE),
        e("clock_unicaone", "Unica One", R.font.clock_unicaone, FontFamilyGroup.FUTURE),
        e("clock_chakrapetch", "Chakra Petch", R.font.clock_chakrapetch, FontFamilyGroup.FUTURE),
        e("clock_megrim", "Megrim", R.font.clock_megrim, FontFamilyGroup.FUTURE),
        e("clock_nixieone", "Nixie", R.font.clock_nixieone, FontFamilyGroup.FUTURE),
        e("clock_zendots", "Zen Dots", R.font.clock_zendots, FontFamilyGroup.FUTURE),
        e("clock_sairastencilone", "Saira Stencil", R.font.clock_sairastencilone, FontFamilyGroup.FUTURE),
        e("clock_wallpoet", "Wallpoet", R.font.clock_wallpoet, FontFamilyGroup.FUTURE),
        e("clock_sharetech", "Share Tech", R.font.clock_sharetech, FontFamilyGroup.FUTURE),
        e("clock_majormono", "Major Mono", R.font.clock_majormono, FontFamilyGroup.FUTURE),
        e("clock_blackops", "Black Ops", R.font.clock_blackops, FontFamilyGroup.FUTURE),
        // Poetry: caligráficas.
        e("clock_parisienne", "Parisienne", R.font.clock_parisienne, FontFamilyGroup.POETRY),
        e("clock_allura", "Allura", R.font.clock_allura, FontFamilyGroup.POETRY),
        e("clock_tangerine", "Tangerine", R.font.clock_tangerine, FontFamilyGroup.POETRY),
        e("clock_sacramento", "Sacramento", R.font.clock_sacramento, FontFamilyGroup.POETRY),
        e("clock_greatvibes", "Great Vibes", R.font.clock_greatvibes, FontFamilyGroup.POETRY),
        e("clock_lobster", "Lobster", R.font.clock_lobster, FontFamilyGroup.POETRY),
        e("clock_pacifico", "Pacifico", R.font.clock_pacifico, FontFamilyGroup.POETRY),
        // Display: con relieve, contorno o efecto.
        e("clock_monoton", "Monoton", R.font.clock_monoton, FontFamilyGroup.DISPLAY),
        e("clock_limelight", "Limelight", R.font.clock_limelight, FontFamilyGroup.DISPLAY),
        e("clock_bungee", "Bungee", R.font.clock_bungee, FontFamilyGroup.DISPLAY),
        e("clock_bungeeinline", "Bungee Inline", R.font.clock_bungeeinline, FontFamilyGroup.DISPLAY),
        e("clock_bungeeoutline", "Bungee Outline", R.font.clock_bungeeoutline, FontFamilyGroup.DISPLAY),
        e("clock_bungeeshade", "Bungee Shade", R.font.clock_bungeeshade, FontFamilyGroup.DISPLAY),
        e("clock_rampartone", "Rampart 3D", R.font.clock_rampartone, FontFamilyGroup.DISPLAY),
        e("clock_trainone", "Train One", R.font.clock_trainone, FontFamilyGroup.DISPLAY),
        e("clock_fasterone", "Faster One", R.font.clock_fasterone, FontFamilyGroup.DISPLAY),
        e("clock_righteous", "Righteous", R.font.clock_righteous, FontFamilyGroup.DISPLAY),
        e("clock_rubikmonoone", "Rubik Mono", R.font.clock_rubikmonoone, FontFamilyGroup.DISPLAY),
        // Retro: píxeles y pantallas antiguas.
        e("clock_pressstart2p", "Pixel 8-bit", R.font.clock_pressstart2p, FontFamilyGroup.RETRO),
        e("clock_silkscreen", "Silkscreen", R.font.clock_silkscreen, FontFamilyGroup.RETRO),
        e("clock_vt323", "VT323", R.font.clock_vt323, FontFamilyGroup.RETRO),
        e("clock_monofett", "Monofett", R.font.clock_monofett, FontFamilyGroup.RETRO),
        e("clock_codystar", "Codystar", R.font.clock_codystar, FontFamilyGroup.RETRO),
    )

    const val USER_PREFIX = "user:"
    private const val MAX_IMPORT_BYTES = 8L * 1024 * 1024

    private val cache = HashMap<String, Typeface>()

    fun userDir(context: Context): File = File(context.applicationContext.filesDir, "fonts").apply { mkdirs() }

    /** Fuentes importadas por la persona: (id, nombre visible, archivo). */
    fun userFonts(context: Context): List<Triple<String, String, File>> =
        userDir(context).listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }.orEmpty()
            .sortedBy { it.lastModified() }
            .map { Triple(USER_PREFIX + it.name, it.nameWithoutExtension.substringAfter('_').replace('-', ' '), it) }

    /**
     * Copia una fuente TTF/OTF elegida con el selector del sistema y comprueba que Android la pueda
     * leer. Devuelve el id o un mensaje de error.
     */
    fun import(context: Context, uri: Uri, displayName: String?): Result<String> = runCatching {
        val name = (displayName ?: "fuente.ttf").replace(Regex("[^A-Za-z0-9._ -]"), "").ifBlank { "fuente.ttf" }
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext == "ttf" || ext == "otf") { "Elegí un archivo .ttf u .otf" }
        val target = File(userDir(context), "${System.currentTimeMillis()}_${name.replace(' ', '-')}")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir el archivo" }
            target.outputStream().use { out ->
                val copied = input.copyTo(out)
                require(copied in 1..MAX_IMPORT_BYTES) { "La fuente es demasiado grande (máx. 8 MB)" }
            }
        }
        try {
            Typeface.createFromFile(target)
        } catch (e: RuntimeException) {
            target.delete()
            throw IllegalArgumentException("Android no puede leer esa fuente")
        }
        USER_PREFIX + target.name
    }

    fun remove(context: Context, id: String) {
        if (!id.startsWith(USER_PREFIX)) return
        File(userDir(context), id.removePrefix(USER_PREFIX)).delete()
        synchronized(cache) { cache.remove(id) }
    }

    fun resolver(context: Context): (String) -> Typeface? {
        val app = context.applicationContext
        return { id ->
            synchronized(cache) {
                cache[id] ?: run {
                    if (id.startsWith(USER_PREFIX)) {
                        File(userDir(app), id.removePrefix(USER_PREFIX)).takeIf { it.exists() }
                            ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
                    } else {
                        all.firstOrNull { it.id == id }?.let { runCatching { ResourcesCompat.getFont(app, it.res) }.getOrNull() }
                    }
                }?.also { cache[id] = it }
            }
        }
    }
}

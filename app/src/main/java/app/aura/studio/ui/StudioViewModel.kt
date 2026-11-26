package app.aura.studio.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aura.aura
import app.aura.core.studio.AutoDesign
import app.aura.core.studio.ArtisticClock
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.Fusion
import app.aura.core.studio.Harmony
import app.aura.core.studio.ImageTraits
import app.aura.core.studio.DigitSlot
import app.aura.core.studio.MagneticGuides
import app.aura.core.studio.History
import app.aura.core.studio.MaskStroke
import app.aura.core.studio.PhotoFrame
import app.aura.core.studio.StudioProject
import app.aura.studio.mask.MaskRenderer
import app.aura.studio.mask.SubjectSegmenter
import app.aura.studio.render.AtmospherePainter
import app.aura.studio.render.ClockExtrasBuilder
import app.aura.studio.render.ClockPainter
import app.aura.studio.render.StudioCompositor
import app.aura.studio.render.StudioFonts
import app.aura.studio.wallpaper.WallpaperApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Estado de Aura Studio: lista de proyectos y el proyecto abierto en el editor. El proyecto entero
 * (reloj, encuadre, máscara y trazos) vive en un único historial, así deshacer/rehacer cubre todo.
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.aura
    private val repo = app.studio
    private val compositor = StudioCompositor(ClockPainter(StudioFonts.resolver(application)))
    private val segmenter = SubjectSegmenter()
    private val renderLock = Mutex()

    val weather = app.weather.snapshot
    val projects = repo.projects
    val activeId = repo.activeId

    private val _history = MutableStateFlow<History<StudioProject>?>(null)
    val history: StateFlow<History<StudioProject>?> = _history.asStateFlow()

    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()

    private val _maskOverlay = MutableStateFlow<ImageBitmap?>(null)
    val maskOverlay: StateFlow<ImageBitmap?> = _maskOverlay.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _now = MutableStateFlow(LocalDateTime.now())

    /** Relación de aspecto de la pantalla real: la vista previa y la exportación coinciden. */
    val screen = WallpaperApplier.screenSize(application)

    private var photo: Bitmap? = null
    private var previewPhoto: Bitmap? = null
    private var baseMask: Bitmap? = null
    private var mask: Bitmap? = null
    private var maskKey: Any? = null
    private var subjectCache: Bitmap? = null
    private var subjectKey: Any? = null
    private var gestureBase: StudioProject? = null
    @Volatile private var lastExtras: app.aura.studio.render.ClockExtras? = null
    private var savedVersion: StudioProject? = null

    val photoSize: Pair<Int, Int>? get() = photo?.let { it.width to it.height }
    val previewPhotoBitmap: ImageBitmap? get() = previewPhoto?.asImageBitmap()

    init {
        viewModelScope.launch { repo.refresh() }
        viewModelScope.launch {
            while (true) {
                val n = LocalDateTime.now()
                _now.value = n
                delay((60 - n.second) * 1000L + 50)
            }
        }
        viewModelScope.launch {
            combine(_history, _now) { h, _ -> h?.current }.collectLatest { p ->
                if (p != null) renderPreview(p)
            }
        }
    }

    val current: StudioProject? get() = _history.value?.current
    val hasUnsavedChanges: Boolean get() = current != null && current != savedVersion

    // ---------------------------------------------------------------- proyectos

    fun createFromPhoto(uri: Uri, onCreated: (String) -> Unit) = createFromPhoto(uri, null, onCreated = onCreated)

    fun createFromPhoto(
        uri: Uri, title: String?, design: ClockDesign? = null, mask: java.io.File? = null, onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = "Importando foto…"
            try {
                val name = title ?: ("Diseño " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d/M HH:mm")))
                val project = repo.create(uri, name, design, mask)
                onCreated(project.id)
            } catch (e: Exception) {
                _message.value = "No se pudo abrir la imagen: ${e.message}"
            } finally {
                _busy.value = null
            }
        }
    }

    fun open(id: String, autoDesign: Boolean = false) {
        if (current?.id == id) return
        viewModelScope.launch {
            _busy.value = "Abriendo…"
            val project = repo.load(id)
            val ph = repo.photo(id)
            if (project == null || ph == null) {
                _message.value = "No se encontró el diseño"
                _busy.value = null
                return@launch
            }
            renderLock.withLock {
                releaseBitmaps()
                photo = ph
                previewPhoto = scaledCopy(ph, PREVIEW_PHOTO_SIDE)
                baseMask = repo.baseMask(id)
            }
            savedVersion = project
            _history.value = History(project)
            _busy.value = null
            // Primera vez: se intenta separar el sujeto automáticamente, sin intervención.
            if (!project.hasMask && !project.autoMask) {
                detectSubject(silent = true) { if (autoDesign) generateProposals() }
            } else if (autoDesign) generateProposals()
        }
    }

    fun duplicate(id: String) = viewModelScope.launch { repo.duplicate(id); _message.value = "Diseño duplicado" }

    fun delete(id: String) = viewModelScope.launch {
        if (current?.id == id) close()
        repo.delete(id)
    }

    fun close() {
        _history.value = null
        _proposals.value = emptyList()
        proposalRound = 0
        _preview.value = null
        _maskOverlay.value = null
        gestureBase = null
        viewModelScope.launch { renderLock.withLock { releaseBitmaps() } }
    }

    fun save(onDone: (() -> Unit)? = null) {
        val p = current ?: return
        viewModelScope.launch {
            val updated = p.copy(updatedAt = System.currentTimeMillis())
            val thumb = renderAt(THUMB_WIDTH, (THUMB_WIDTH * screen.height / screen.width.toFloat()).roundToInt(), updated, preview = true)
            try {
                repo.save(updated, thumb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "No se pudo guardar el diseño."
                return@launch
            } finally {
                thumb.recycle()
            }
            if (current?.id == p.id) {
                if (current == p) _history.value = _history.value?.replace(updated)
                savedVersion = updated
            }
            _message.value = "Diseño guardado"
            onDone?.invoke()
        }
    }

    // ---------------------------------------------------------------- edición

    /** Cambio en vivo (arrastre, deslizador): no crea un paso de historial hasta [commit]. */
    fun live(transform: (StudioProject) -> StudioProject) {
        val h = _history.value ?: return
        if (gestureBase == null) gestureBase = h.current
        _history.value = h.replace(transform(h.current))
    }

    fun commit() {
        val h = _history.value ?: return
        val base = gestureBase ?: return
        gestureBase = null
        _history.value = h.replace(base).push(h.current)
    }

    fun edit(transform: (StudioProject) -> StudioProject) {
        val h = _history.value ?: return
        gestureBase = null
        _history.value = h.push(transform(h.current))
    }

    fun design(transform: (ClockDesign) -> ClockDesign) = edit { it.copy(design = transform(it.design).normalized()) }
    fun designLive(transform: (ClockDesign) -> ClockDesign) = live { it.copy(design = transform(it.design).normalized()) }
    fun frameLive(transform: (PhotoFrame) -> PhotoFrame) = live { it.copy(frame = transform(it.frame).normalized()) }
    fun atmosphere(transform: (app.aura.core.studio.Atmosphere) -> app.aura.core.studio.Atmosphere) = edit { it.copy(atmosphere = transform(it.atmosphere).normalized()) }
    fun atmosphereLive(transform: (app.aura.core.studio.Atmosphere) -> app.aura.core.studio.Atmosphere) = live { it.copy(atmosphere = transform(it.atmosphere).normalized()) }

    /** Tamaño de la vista previa (px), para convertir gestos en coordenadas del lienzo. */
    val previewSize: Pair<Int, Int> get() = PREVIEW_WIDTH to (PREVIEW_WIDTH * screen.height / screen.width.toFloat()).roundToInt()

    /** Esquinas actuales del reloj en la vista previa (px), para los tiradores de «Deformar». */
    fun clockQuad(): FloatArray? {
        val p = current ?: return null
        val extras = lastExtras ?: return null
        val (w, h) = previewSize
        return compositor.clock.quad(w, h, p.design, extras)
    }

    /**
     * Deformaciones rápidas proporcionales al tamaño del reloj (no de la pantalla):
     * "left"/"right" = perspectiva lateral, "back" = se aleja hacia el fondo, "up" = se inclina hacia arriba.
     */
    fun warpPreset(kind: String) {
        val quad = clockQuadUnwarped() ?: return
        val (w, h) = previewSize
        val boxW = kotlin.math.hypot(quad[2] - quad[0], quad[3] - quad[1])
        val boxH = kotlin.math.hypot(quad[6] - quad[0], quad[7] - quad[1])
        val ay = boxH * 0.22f / h
        val ax = boxW * 0.10f / w
        val warp = when (kind) {
            "left" -> listOf(0f, ay, 0f, 0f, 0f, 0f, 0f, -ay)
            "right" -> listOf(0f, 0f, 0f, ay, 0f, -ay, 0f, 0f)
            "back" -> listOf(ax, 0f, -ax, 0f, 0f, 0f, 0f, 0f)
            else -> listOf(0f, 0f, 0f, 0f, -ax, 0f, ax, 0f)
        }
        design { it.copy(warp = warp) }
    }

    private fun clockQuadUnwarped(): FloatArray? {
        val p = current ?: return null
        val extras = lastExtras ?: return null
        val (w, h) = previewSize
        return compositor.clock.quad(w, h, p.design.copy(warp = emptyList()), extras)
    }

    /** Agranda (o achica) el reloj para que ocupe casi todo el ancho de la pantalla. */
    fun fillWidth() {
        val quad = clockQuad() ?: return
        val (w, _) = previewSize
        val xs = listOf(quad[0], quad[2], quad[4], quad[6])
        val span = (xs.max() - xs.min()).takeIf { it > 1f } ?: return
        val k = (w * 0.94f) / span
        design { it.copy(size = it.size * k, centerX = 0.5f) }
    }

    // ---------------------------------------------------------------- dígitos independientes

    private val _selection = MutableStateFlow<Set<DigitSlot>>(emptySet())
    /** Dígitos elegidos para editar; vacío = todos. */
    val selection: StateFlow<Set<DigitSlot>> = _selection.asStateFlow()

    /** Guías magnéticas activas durante un arrastre, en px de la vista previa: (vertical, posición). */
    private val _guides = MutableStateFlow<List<Pair<Boolean, Float>>>(emptyList())
    val guides: StateFlow<List<Pair<Boolean, Float>>> = _guides.asStateFlow()

    /** Desplazamientos «sin imán» acumulados durante el arrastre, para poder despegarse de una guía. */
    private var dragRaw: Map<DigitSlot, Pair<Float, Float>>? = null

    fun select(slots: Set<DigitSlot>) { _selection.value = slots }

    /** Los dígitos a los que se aplican los controles (la selección, o todos si no hay). */
    val targets: Set<DigitSlot> get() = _selection.value.ifEmpty { DigitSlot.entries.toSet() }

    fun artistic(transform: (ArtisticClock) -> ArtisticClock) = design { it.copy(artistic = transform(it.artistic)) }
    fun artisticLive(transform: (ArtisticClock) -> ArtisticClock) = designLive { it.copy(artistic = transform(it.artistic)) }

    /** Número que muestra ahora cada lugar (para las etiquetas del panel). */
    fun glyphs(): Map<DigitSlot, Char> = lastExtras?.let { current?.design?.artistic?.glyphs(it.hours, it.minutes) }.orEmpty()

    /** Caja de cada dígito en la vista previa (px). */
    fun digitBounds(): Map<DigitSlot, android.graphics.RectF> {
        val p = current ?: return emptyMap()
        val extras = lastExtras ?: return emptyMap()
        val (w, h) = previewSize
        return compositor.clock.digitBounds(w, h, p.design, extras)
    }

    /** Toque en la vista previa (fracciones 0–1): elige el dígito tocado o, si no hay ninguno, limpia la selección. */
    fun selectAt(fx: Float, fy: Float) {
        val (w, h) = previewSize
        val x = fx * w
        val y = fy * h
        val hit = digitBounds().entries
            .filter { (_, r) -> android.graphics.RectF(r).apply { inset(-12f, -12f) }.contains(x, y) }
            .minByOrNull { (_, r) -> kotlin.math.hypot(r.centerX() - x, r.centerY() - y) }?.key
        _selection.value = hit?.let { setOf(it) } ?: emptySet()
    }

    /**
     * Gesto sobre los dígitos elegidos: mover (fracciones del lienzo), escalar y girar. Con guías
     * magnéticas: el centro de la selección se pega al centro de la pantalla o a otros dígitos.
     */
    fun moveDigitsLive(dx: Float, dy: Float, zoom: Float, rotation: Float) {
        val p = current ?: return
        val slots = targets.filterNot { p.design.artistic.style(it).locked }.toSet()
        if (slots.isEmpty()) return
        val raw = (dragRaw ?: slots.associateWith { p.design.artistic.style(it).let { s -> s.offsetX to s.offsetY } })
            .mapValues { (_, o) -> (o.first + dx) to (o.second + dy) }
        dragRaw = raw
        val art = p.design.artistic
        val moved = art.copy(digits = art.digits + slots.associateWith { s ->
            art.style(s).let { st ->
                st.copy(
                    offsetX = raw.getValue(s).first, offsetY = raw.getValue(s).second,
                    scaleX = st.scaleX * zoom, scaleY = st.scaleY * zoom, rotation = st.rotation + rotation,
                ).normalized()
            }
        })
        val extras = lastExtras ?: return designLive { it.copy(artistic = moved) }
        val (w, h) = previewSize
        val bounds = compositor.clock.digitBounds(w, h, p.design.copy(artistic = moved), extras)
        val sel = slots.mapNotNull { bounds[it] }
        if (sel.isEmpty()) return designLive { it.copy(artistic = moved) }
        val box = android.graphics.RectF(sel.first()).apply { sel.drop(1).forEach { union(it) } }
        val others = bounds.filterKeys { it !in slots }.values
        val threshold = w * 0.018f
        val (sx, gx) = MagneticGuides.snap(box.centerX(), listOf(w / 2f) + others.map { it.centerX() }, threshold)
        val (sy, gy) = MagneticGuides.snap(box.centerY(), listOf(h / 2f) + others.map { it.centerY() } + others.map { it.bottom - (box.height() / 2f) }, threshold)
        val ddx = (sx - box.centerX()) / w
        val ddy = (sy - box.centerY()) / h
        _guides.value = listOfNotNull(gx?.let { true to it }, gy?.let { false to it })
        designLive { d ->
            d.copy(artistic = moved.copy(digits = moved.digits + slots.associateWith { s -> moved.style(s).copy(offsetX = raw.getValue(s).first + ddx, offsetY = raw.getValue(s).second + ddy).normalized() }))
        }
    }

    /** Fin de un gesto de dígitos: guarda el paso en el historial y oculta las guías. */
    fun endDigitGesture() {
        dragRaw = null
        _guides.value = emptyList()
        commit()
    }

    data class DesignPreview(val proposal: AutoDesign.Proposal, val image: ImageBitmap)

    private val _proposals = MutableStateFlow<List<DesignPreview>>(emptyList())
    val proposals = _proposals.asStateFlow()
    private var proposalRound = 0

    fun generateProposals() {
        val project = current ?: return
        if (_busy.value != null) return
        val round = proposalRound++
        _busy.value = "Componiendo propuestas…"
        viewModelScope.launch {
            try {
                val (w, h) = previewSize
                val analysisWidth = 72
                val analysisHeight = (analysisWidth * h / w.toFloat()).roundToInt()
                val designs = withContext(Dispatchers.Default) {
                    renderLock.withLock {
                        val src = previewPhoto ?: return@withLock emptyList()
                        val sample = Bitmap.createBitmap(analysisWidth, analysisHeight, Bitmap.Config.ARGB_8888)
                        try {
                            compositor.drawPhoto(Canvas(sample), analysisWidth, analysisHeight, src, project)
                            val pixels = IntArray(analysisWidth * analysisHeight)
                            sample.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)
                            val subject = currentMask(project)?.let { m ->
                                sample.eraseColor(Color.TRANSPARENT)
                                val matrix = compositor.photoMatrix(analysisWidth, analysisHeight, src, project).apply {
                                    preScale(src.width / m.width.toFloat(), src.height / m.height.toFloat())
                                }
                                Canvas(sample).drawBitmap(m, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                                IntArray(pixels.size).also {
                                    sample.getPixels(it, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)
                                    if (project.mask.inverted) for (index in it.indices) it[index] = it[index].inv()
                                }
                            }
                            AutoDesign.propose(pixels, analysisWidth, analysisHeight, subject, round)
                        } finally {
                            sample.recycle()
                        }
                    }
                }
                val previews = designs.map { proposal ->
                    val image = renderAt(w / 2, h / 2, project.copy(design = proposal.design), preview = true, updateExtras = false)
                    DesignPreview(proposal, image.asImageBitmap())
                }
                if (current?.id == project.id) _proposals.value = previews
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "No se pudieron componer las propuestas. Podés volver a intentar."
            } finally {
                _busy.value = null
            }
        }
    }

    fun chooseProposal(proposal: AutoDesign.Proposal) {
        design { proposal.design.copy(hourFormat = it.hourFormat, dateStyle = it.dateStyle) }
        _proposals.value = emptyList()
    }

    // ---------------------------------------------------------------- Aura Fusion

    private val _harmony = MutableStateFlow<Pair<ImageTraits, Harmony>?>(null)
    /** Análisis de la foto y propuesta de «Armonizar» (no se aplica sola). */
    val harmony: StateFlow<Pair<ImageTraits, Harmony>?> = _harmony.asStateFlow()

    /** Analiza la foto tal como está encuadrada y la zona donde está el reloj. */
    fun analyzePhoto() {
        val p = current ?: return
        val src = previewPhoto ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val (pw, ph) = previewSize
            val w = 72
            val h = (w * ph / pw.toFloat()).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            compositor.drawPhoto(android.graphics.Canvas(small), w, h, src, p)
            val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
            small.recycle()
            val quad = clockQuad()
            val region = if (quad == null) floatArrayOf(0.2f, 0.2f, 0.8f, 0.4f) else {
                val xs = listOf(quad[0], quad[2], quad[4], quad[6])
                val ys = listOf(quad[1], quad[3], quad[5], quad[7])
                floatArrayOf((xs.min() / pw).coerceIn(0f, 1f), (ys.min() / ph).coerceIn(0f, 1f), (xs.max() / pw).coerceIn(0f, 1f), (ys.max() / ph).coerceIn(0f, 1f))
            }
            val traits = Fusion.analyze(px, w, h, region)
            _harmony.value = traits to Fusion.harmonize(traits)
        }
    }

    /** Aplica la propuesta en un solo paso (se puede deshacer y seguir editando a mano). */
    fun applyHarmony() {
        val hm = _harmony.value?.second ?: return
        design {
            it.withMaterial(hm.material).copy(color = hm.color, accentColor = hm.accentColor, customFont = hm.fontId, lightAngle = hm.lightAngle)
        }
        _message.value = "Armonizado: ${hm.material.label}. Podés deshacerlo o seguir ajustando."
    }

    fun undo() { gestureBase = null; _history.value = _history.value?.undo() }
    fun redo() { gestureBase = null; _history.value = _history.value?.redo() }

    // ---------------------------------------------------------------- máscara

    fun detectSubject(silent: Boolean = false, onFinished: () -> Unit = {}) {
        val p = current ?: return
        if (_busy.value != null) return
        _busy.value = "Buscando el sujeto…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    renderLock.withLock {
                        val source = photo
                        if (source == null || current?.id != p.id) {
                            SubjectSegmenter.Result.Unavailable("El diseño se cerró")
                        } else {
                            segmenter.segment(source, p.mask.threshold)
                        }
                    }
                }
                if (current?.id != p.id) {
                    if (result is SubjectSegmenter.Result.Mask) result.alpha.recycle()
                    return@launch
                }
                when (result) {
                    is SubjectSegmenter.Result.Mask -> {
                        val sourceSize = photoSize
                        if (sourceSize == null) {
                            result.alpha.recycle()
                            return@launch
                        }
                        val (w, h) = MaskRenderer.workingSize(sourceSize.first, sourceSize.second)
                        val scaled = Bitmap.createScaledBitmap(result.alpha, w, h, true)
                        if (scaled !== result.alpha) result.alpha.recycle()
                        try {
                            repo.saveBaseMask(p.id, scaled)
                            renderLock.withLock {
                                if (current?.id == p.id) {
                                    baseMask?.recycle()
                                    baseMask = scaled
                                    maskKey = null
                                }
                            }
                            if (current?.id == p.id) {
                                edit { it.copy(autoMask = true, hasMask = true, strokes = emptyList()) }
                                if (!silent) _message.value = "Sujeto detectado. Podés retocarlo con el pincel."
                            }
                        } finally {
                            if (baseMask !== scaled) scaled.recycle()
                        }
                    }
                    is SubjectSegmenter.Result.Unavailable -> {
                        _history.value = _history.value?.let { it.replace(it.current.copy(autoMask = true)) }
                        if (!silent) _message.value = "Detección automática no disponible. Usá el pincel."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) _message.value = "No se pudo guardar la máscara."
            } finally {
                _busy.value = null
            }
            if (current?.id == p.id) onFinished()
        }
    }

    fun addStroke(stroke: MaskStroke) = edit { it.copy(strokes = it.strokes + stroke, hasMask = true) }

    fun clearMask() {
        val p = current ?: return
        viewModelScope.launch {
            repo.saveBaseMask(p.id, null)
            renderLock.withLock { baseMask?.recycle(); baseMask = null; maskKey = null }
            edit { it.copy(strokes = emptyList(), hasMask = false, autoMask = true) }
        }
    }

    // ---------------------------------------------------------------- exportar / aplicar

    fun applyStatic(target: WallpaperApplier.Target) = withExport("Aplicando fondo…") { bmp ->
        WallpaperApplier.applyStatic(getApplication(), bmp, target)
        _message.value = "Fondo aplicado. La hora queda fija en la imagen; para la hora en tiempo real usá el fondo animado."
    }

    fun saveToGallery() = withExport("Exportando…") { bmp ->
        val ok = WallpaperApplier.saveToGallery(getApplication(), bmp, "Aura-" + System.currentTimeMillis())
        _message.value = if (ok) "Guardado en Imágenes › Aura (${bmp.width}×${bmp.height})" else "No se pudo guardar la imagen"
    }

    /** Guarda, marca el diseño como activo y devuelve el intent del selector de fondos animados. */
    fun prepareLive(onReady: (Intent) -> Unit) {
        val p = current ?: return
        save {
            viewModelScope.launch {
                repo.setActive(p.id)
                onReady(WallpaperApplier.liveWallpaperIntent(getApplication()))
            }
        }
    }

    fun setActive(id: String) = viewModelScope.launch { repo.setActive(id); _message.value = "Diseño activo para el fondo animado" }

    fun consumeMessage() { _message.value = null }

    private fun withExport(label: String, block: suspend (Bitmap) -> Unit) {
        val p = current ?: return
        viewModelScope.launch {
            _busy.value = label
            try {
                val bmp = renderAt(screen.width, screen.height, p, preview = false)
                block(bmp)
                bmp.recycle()
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
            } finally {
                _busy.value = null
            }
        }
    }

    // ---------------------------------------------------------------- render

    private suspend fun renderPreview(p: StudioProject) {
        val w = PREVIEW_WIDTH
        val h = (w * screen.height / screen.width.toFloat()).roundToInt()
        val bmp = renderAt(w, h, p, preview = true)
        _preview.value = bmp.asImageBitmap()
        updateMaskOverlay(p)
    }

    private suspend fun renderAt(w: Int, h: Int, p: StudioProject, preview: Boolean, updateExtras: Boolean = true): Bitmap = withContext(Dispatchers.Default) {
        renderLock.withLock {
            val src = (if (preview) previewPhoto else photo) ?: photo ?: error("Sin foto")
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val m = currentMask(p)
            val key = listOf(w, h, preview, p.frame, p.mask, p.design.anyBehindSubject(), p.hasMask, p.strokes, baseMask)
            val subject = if (preview && key == subjectKey && subjectCache != null) {
                subjectCache
            } else {
                compositor.subjectLayer(w, h, src, m, p).also {
                    if (preview) {
                        subjectCache?.recycle()
                        subjectCache = it
                        subjectKey = key
                    }
                }
            }
            val snapshot = app.weather.cached()
            val extras = ClockExtrasBuilder.build(getApplication(), p.design, app.settings.current(), snapshot, _now.value)
            if (updateExtras) lastExtras = extras
            val tint = AtmospherePainter.tintFor(p.atmosphere, _now.value, snapshot)
            // En la vista previa las partículas las anima el editor por encima; al exportar quedan quietas.
            val resolved = p.atmosphere.resolved(snapshot?.data?.current)
            compositor.render(out, src, m, p.copy(atmosphere = resolved), extras, subject, tint = tint, particlesAt = if (preview) null else 0f)
            if (!preview) subject?.recycle()
            out
        }
    }

    private fun currentMask(p: StudioProject): Bitmap? {
        if (!p.hasMask) return null
        val ph = photo ?: return null
        val key = p.strokes to baseMask
        if (key != maskKey || mask == null) {
            val (w, h) = MaskRenderer.workingSize(ph.width, ph.height)
            mask?.recycle()
            mask = MaskRenderer.render(baseMask, p.strokes, w, h)
            maskKey = key
        }
        return mask
    }

    /** Sujeto teñido de rojo translúcido para el editor de máscara. */
    private suspend fun updateMaskOverlay(p: StudioProject) = withContext(Dispatchers.Default) {
        renderLock.withLock {
            val m = currentMask(p)
            if (m == null) {
                _maskOverlay.value = null
                return@withLock
            }
            val out = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            if (p.mask.inverted) {
                c.drawColor(OVERLAY)
                c.drawBitmap(m, 0f, 0f, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            } else {
                c.drawBitmap(m, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(OVERLAY, PorterDuff.Mode.SRC_IN); color = OVERLAY })
            }
            _maskOverlay.value = out.asImageBitmap()
        }
    }

    private fun scaledCopy(src: Bitmap, maxSide: Int): Bitmap {
        val k = minOf(1f, maxSide / maxOf(src.width, src.height).toFloat())
        return if (k >= 1f) src else Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
    }

    private fun releaseBitmaps() {
        subjectCache?.recycle(); subjectCache = null; subjectKey = null
        mask?.recycle(); mask = null; maskKey = null
        if (previewPhoto !== photo) previewPhoto?.recycle()
        previewPhoto = null
        baseMask?.recycle(); baseMask = null
        photo?.recycle(); photo = null
    }

    override fun onCleared() {
        releaseBitmaps()
    }

    companion object {
        private const val PREVIEW_WIDTH = 720
        private const val PREVIEW_PHOTO_SIDE = 1800
        private const val THUMB_WIDTH = 360
        private val OVERLAY = Color.argb(150, 229, 128, 107)
    }
}

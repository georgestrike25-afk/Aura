package app.aura.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.DigitDepth
import app.aura.core.studio.DigitGroup
import app.aura.core.studio.DigitSlot
import app.aura.core.studio.StudioProject
import app.aura.studio.render.StudioFonts
import app.aura.ui.components.ColorSwatches
import app.aura.ui.components.LabeledRow
import app.aura.ui.theme.Aura
import kotlin.math.roundToInt

private val SLOT_LABELS = mapOf(
    DigitSlot.HOUR_TENS to "1.ª hora",
    DigitSlot.HOUR_UNITS to "2.ª hora",
    DigitSlot.MINUTE_TENS to "1.º min",
    DigitSlot.MINUTE_UNITS to "2.º min",
)

/** Recuadros de los dígitos elegidos y guías magnéticas, sobre la vista previa. */
@Composable
internal fun DigitOverlay(vm: StudioViewModel, area: IntSize) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val guides by vm.guides.collectAsStateWithLifecycle()
    vm.history.collectAsStateWithLifecycle().value // redibujar al cambiar el diseño
    val bounds = vm.digitBounds()
    val (pw, ph) = vm.previewSize
    val gold = Aura.tokens.accent
    Canvas(Modifier.fillMaxSize()) {
        if (area.width == 0) return@Canvas
        val sx = size.width / pw
        val sy = size.height / ph
        bounds.forEach { (slot, r) ->
            val chosen = slot in selection
            val locked = vm.current?.design?.artistic?.style(slot)?.locked == true
            drawRect(
                color = if (chosen) gold else Color.White.copy(alpha = 0.35f),
                topLeft = Offset(r.left * sx, r.top * sy),
                size = Size(r.width() * sx, r.height() * sy),
                style = Stroke(
                    width = if (chosen) 2.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (locked) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
                ),
            )
        }
        guides.forEach { (vertical, v) ->
            if (vertical) {
                drawLine(gold, Offset(v * sx, 0f), Offset(v * sx, size.height), strokeWidth = 1.dp.toPx())
            } else {
                drawLine(gold, Offset(0f, v * sy), Offset(size.width, v * sy), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/**
 * Cada dígito como objeto independiente: selección individual, por grupos o todos; posición, escala,
 * giro, inclinación, opacidad, color, fuente, material, profundidad, bloqueo, visibilidad y orden.
 */
@Composable
internal fun ColumnScope.DigitsPanel(vm: StudioViewModel, p: StudioProject) {
    val art = p.design.artistic
    val selection by vm.selection.collectAsStateWithLifecycle()
    LabeledRow("Dígitos independientes", supporting = "Cada número se edita por separado y conserva su estilo aunque cambie la hora") {
        Switch(art.enabled, { v -> vm.artistic { it.copy(enabled = v) } })
    }
    if (!art.enabled) return

    val glyphs = vm.glyphs()
    val targets = vm.targets
    val first = art.style(targets.first())
    Text("Editar", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DigitGroup.entries.forEach { g ->
            SelectChip(g.label, selection == g.slots || (g == DigitGroup.ALL && selection.isEmpty())) { vm.select(if (g == DigitGroup.ALL) emptySet() else g.slots) }
        }
        DigitSlot.entries.forEach { s ->
            val label = "${SLOT_LABELS[s]} · ${glyphs[s] ?: "–"}" + if (art.style(s).locked) " 🔒" else ""
            SelectChip(label, selection == setOf(s)) { vm.select(setOf(s)) }
        }
    }

    StudioSlider("Escala ancho", first.scaleX, 0.25f..3f, "${(first.scaleX * 100).roundToInt()} %", { v -> vm.artisticLive { a -> a.edit(targets) { it.copy(scaleX = v) } } }, vm::commit)
    StudioSlider("Escala alto", first.scaleY, 0.25f..3f, "${(first.scaleY * 100).roundToInt()} %", { v -> vm.artisticLive { a -> a.edit(targets) { it.copy(scaleY = v) } } }, vm::commit)
    StudioSlider("Rotación", first.rotation, -180f..180f, "${first.rotation.roundToInt()}°", { v -> vm.artisticLive { a -> a.edit(targets) { it.copy(rotation = v) } } }, vm::commit)
    StudioSlider("Inclinación", first.skewX, -1f..1f, "${(first.skewX * 100).roundToInt()}", { v -> vm.artisticLive { a -> a.edit(targets) { it.copy(skewX = v) } } }, vm::commit)
    StudioSlider("Opacidad", first.opacity.toFloat(), 0f..100f, "${first.opacity} %", { v -> vm.artisticLive { a -> a.edit(targets) { it.copy(opacity = v.roundToInt()) } } }, vm::commit)
    Text("Ajuste fino de posición", style = MaterialTheme.typography.bodyMedium)
    val n = app.aura.core.studio.ClockDesign.NUDGE
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedButton(onClick = { vm.artistic { a -> a.edit(targets) { it.copy(offsetX = it.offsetX - n) } } }) { Text("←") }
        OutlinedButton(onClick = { vm.artistic { a -> a.edit(targets) { it.copy(offsetY = it.offsetY - n) } } }) { Text("↑") }
        OutlinedButton(onClick = { vm.artistic { a -> a.edit(targets) { it.copy(offsetY = it.offsetY + n) } } }) { Text("↓") }
        OutlinedButton(onClick = { vm.artistic { a -> a.edit(targets) { it.copy(offsetX = it.offsetX + n) } } }) { Text("→") }
    }

    Text("Color", style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.artistic { a -> a.edit(targets) { it.copy(color = null) } } }) { Text("Como el reloj") }
        ColorSwatches(INKS.map { Color(it) }, first.color?.let { Color(it) } ?: Color.Transparent, { c -> vm.artistic { a -> a.edit(targets) { it.copy(color = c.toArgb()) } } }, Modifier.padding(vertical = 6.dp))
    }

    Text("Fuente", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectChip("Como el reloj", first.fontId == null) { vm.artistic { a -> a.edit(targets) { it.copy(fontId = null) } } }
        StudioFonts.all.forEach { f ->
            FilterChip(
                selected = first.fontId == f.id,
                onClick = { vm.artistic { a -> a.edit(targets) { it.copy(fontId = f.id) } } },
                label = { Text("${glyphs[targets.first()] ?: '8'}", fontFamily = FontFamily(Font(f.res)), style = MaterialTheme.typography.titleLarge) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Aura.tokens.accent.copy(alpha = 0.25f)),
            )
        }
    }

    Text("Material", style = MaterialTheme.typography.bodyMedium)
    Chips(listOf<Pair<ClockMaterial?, String>>(null to "Como el reloj") + ClockMaterial.entries.map { it to it.label }, first.material) { m ->
        vm.artistic { a -> a.edit(targets) { it.copy(material = m) } }
    }
    if (first.material?.isElement == true) {
        StudioSlider("Intensidad", first.intensity.toFloat(), 0f..100f, "${first.intensity} %", { v -> vm.artisticLive { a -> a.edit(targets) { it.copy(intensity = v.roundToInt()) } } }, vm::commit)
    }

    Text("Profundidad", style = MaterialTheme.typography.bodyMedium)
    Chips(
        listOf(DigitDepth.INHERIT to "Como el reloj", DigitDepth.BEHIND_SUBJECT to "Detrás del sujeto", DigitDepth.IN_FRONT_OF_SUBJECT to "Delante del sujeto"),
        first.depth,
    ) { dep -> vm.artistic { a -> a.edit(targets) { it.copy(depth = dep) } } }
    if (!p.hasMask) {
        Text("Para poner números detrás del sujeto, primero marcá el sujeto en la pestaña Profundidad.", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText)
    }

    LabeledRow("Bloqueado", supporting = "No se mueve ni cambia por accidente") {
        Switch(targets.all { art.style(it).locked }, { v -> vm.artistic { it.setLocked(targets, v) } })
    }
    LabeledRow("Visible") {
        Switch(targets.none { art.style(it).hidden }, { v -> vm.artistic { a -> a.edit(targets) { it.copy(hidden = !v) } } })
    }
    if (targets.size == 1) {
        val slot = targets.first()
        val pos = art.drawOrder().indexOf(slot)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Capa ${pos + 1} de 4", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.artistic { it.moveLayer(slot, +1) } }, enabled = pos < 3) { Text("Subir") }
            OutlinedButton(onClick = { vm.artistic { it.moveLayer(slot, -1) } }, enabled = pos > 0) { Text("Bajar") }
        }
    }
    TextButton(onClick = { vm.artistic { it.reset(targets) } }) { Text("Restablecer ${if (targets.size == 4) "todos" else "selección"}") }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Aura.tokens.accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/**
 * Aura Fusion: colores dominantes de la foto (tocar uno lo usa para los números) y «Armonizar», que
 * propone material, color, tipografía y luz. Se aplica solo al tocar «Aplicar» y se puede deshacer.
 */
@Composable
internal fun ColumnScope.FusionSection(vm: StudioViewModel) {
    val harmony by vm.harmony.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { if (harmony == null) vm.analyzePhoto() }
    Text("Aura Fusion", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    val h = harmony
    if (h == null) {
        Text("Analizando la foto…", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText)
        return
    }
    val (traits, proposal) = h
    Text("Colores de la foto (tocá uno para los números)", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText)
    ColorSwatches(traits.palette.map { Color(it) }, Color.Transparent, { c -> vm.design { it.copy(color = c.toArgb()) } }, Modifier.padding(vertical = 6.dp))
    Text(
        "Brillo ${(traits.brightness * 100).roundToInt()} % · contraste ${(traits.contrast * 200).roundToInt().coerceAtMost(100)} % · " +
            "saturación ${(traits.saturation * 100).roundToInt()} % · ${if (traits.warmth > 0.03f) "cálida" else if (traits.warmth < -0.03f) "fría" else "neutra"}",
        style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText,
    )
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Armonizar: ${proposal.material.label} · ${StudioFonts.all.firstOrNull { it.id == proposal.fontId }?.label ?: proposal.fontId}", style = MaterialTheme.typography.titleSmall)
            proposal.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = vm::applyHarmony) { Text("Aplicar") }
                TextButton(onClick = vm::analyzePhoto) { Text("Volver a analizar") }
            }
        }
    }
}

/**
 * Aura Layers: pila de capas de arriba hacia abajo. Cada dígito se puede poner delante o detrás del
 * sujeto, ocultar, bloquear y reordenar; el sujeto se corrige con el pincel.
 */
@Composable
internal fun ColumnScope.LayersSection(vm: StudioViewModel, p: StudioProject, onEditMask: () -> Unit) {
    val art = p.design.artistic
    val glyphs = vm.glyphs()
    Text("Capas (de arriba hacia abajo)", style = MaterialTheme.typography.titleMedium)
    if (!art.enabled) {
        LayerRow("Reloj", if (p.design.behindSubject) "Detrás del sujeto" else "Delante del sujeto", selected = false) {
            TextButton(onClick = { vm.design { it.copy(behindSubject = !it.behindSubject) } }, enabled = p.hasMask) { Text("Cambiar") }
        }
        Text("Activá «Dígitos independientes» para tener una capa por número.", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText)
    } else {
        val order = art.drawOrder()
        // Los dígitos que van delante del sujeto se ven por encima de él; los demás, por debajo.
        val front = order.filter { !art.isBehind(it, p.design.behindSubject) }.reversed()
        val behind = order.filter { art.isBehind(it, p.design.behindSubject) }.reversed()
        front.forEach { DigitLayerRow(vm, p, it, glyphs) }
        LayerRow("Sujeto de la foto", if (p.hasMask) "máscara lista" else "sin máscara", selected = false) {
            TextButton(onClick = onEditMask) { Text("Pincel") }
        }
        behind.forEach { DigitLayerRow(vm, p, it, glyphs) }
    }
    LayerRow("Foto", "fondo", selected = false) {}
    Spacer(Modifier.padding(top = 8.dp))
}

@Composable
private fun DigitLayerRow(vm: StudioViewModel, p: StudioProject, slot: DigitSlot, glyphs: Map<DigitSlot, Char>) {
    val art = p.design.artistic
    val st = art.style(slot)
    val pos = art.drawOrder().indexOf(slot)
    val isBehind = art.isBehind(slot, p.design.behindSubject)
    val selection by vm.selection.collectAsStateWithLifecycle()
    LayerRow("${SLOT_LABELS[slot]} · ${glyphs[slot] ?: "–"}", if (isBehind) "detrás" else "delante", selected = selection == setOf(slot)) {
        TextButton(onClick = { vm.select(setOf(slot)) }) { Text("Editar") }
        TextButton(onClick = {
            vm.artistic { a -> a.edit(slot) { it.copy(depth = if (isBehind) DigitDepth.IN_FRONT_OF_SUBJECT else DigitDepth.BEHIND_SUBJECT) } }
        }, enabled = p.hasMask && !st.locked) { Text(if (isBehind) "Al frente" else "Atrás") }
        TextButton(onClick = { vm.artistic { a -> a.edit(slot) { it.copy(hidden = !it.hidden) } } }, enabled = !st.locked) { Text(if (st.hidden) "Mostrar" else "Ocultar") }
        TextButton(onClick = { vm.artistic { it.setLocked(setOf(slot), !st.locked) } }) { Text(if (st.locked) "Desbloquear" else "Bloquear") }
        TextButton(onClick = { vm.artistic { it.moveLayer(slot, +1) } }, enabled = pos < 3) { Text("▲") }
        TextButton(onClick = { vm.artistic { it.moveLayer(slot, -1) } }, enabled = pos > 0) { Text("▼") }
    }
}

@Composable
private fun LayerRow(title: String, subtitle: String, selected: Boolean, actions: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Aura.tokens.accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text("  $subtitle", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/**
 * Aura Fonts: familias (Editorial, Monument, Zen, Future, Poetry, Display, Retro) con vista previa real
 * de los números, y fuentes TTF/OTF propias importadas desde el teléfono.
 */
@Composable
internal fun ColumnScope.FontPicker(selected: String?, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var group by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(
            StudioFonts.all.firstOrNull { it.id == selected }?.group ?: if (selected?.startsWith(StudioFonts.USER_PREFIX) == true) app.aura.studio.render.FontFamilyGroup.MINE else app.aura.studio.render.FontFamilyGroup.EDITORIAL,
        )
    }
    var userFonts by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(StudioFonts.userFonts(context)) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        StudioFonts.import(context, uri, name)
            .onSuccess { id -> userFonts = StudioFonts.userFonts(context); error = null; group = app.aura.studio.render.FontFamilyGroup.MINE; onPick(id) }
            .onFailure { error = it.message ?: "No se pudo importar la fuente" }
    }
    Text("Aura Fonts", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        app.aura.studio.render.FontFamilyGroup.entries.forEach { g -> SelectChip(g.label, g == group) { group = g } }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (group == app.aura.studio.render.FontFamilyGroup.MINE) {
            OutlinedButton(onClick = { picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }) {
                Text("Importar TTF/OTF")
            }
            userFonts.forEach { (id, label, file) ->
                val family = androidx.compose.runtime.remember(file) { runCatching { FontFamily(Font(file)) }.getOrNull() }
                FontTile("09:50", label, family, id == selected) { onPick(id) }
            }
        } else {
            StudioFonts.all.filter { it.group == group }.forEach { f ->
                FontTile("09:50", f.label, FontFamily(Font(f.res)), f.id == selected) { onPick(f.id) }
            }
        }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (group == app.aura.studio.render.FontFamilyGroup.MINE && userFonts.isEmpty()) {
        Text("Tus fuentes se guardan solo en este teléfono.", style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText)
    }
}

@Composable
private fun FontTile(sample: String, label: String, family: FontFamily?, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (on) Aura.tokens.accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (on) androidx.compose.foundation.BorderStroke(1.5.dp, Aura.tokens.accent) else null,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(sample, fontFamily = family, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Aura.tokens.mutedText, maxLines = 1)
        }
    }
}

/**
 * Partículas de Atmosphere animadas sobre la vista previa (solo mientras el editor está visible, a
 * 30 cuadros por segundo como máximo). La vista previa de fondo se renderiza sin partículas.
 */
@Composable
internal fun AtmosphereOverlay(a: app.aura.core.studio.Atmosphere, area: IntSize) {
    val time = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    if (a.animate) {
        androidx.compose.runtime.LaunchedEffect(a) {
            var start = -1L
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameMillis { ms ->
                    if (start < 0) start = ms
                    if (ms - last >= 33) { last = ms; time.floatValue = (ms - start) / 1000f }
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (area.width == 0) return@Canvas
        val t = time.floatValue
        drawIntoCanvas { c -> app.aura.studio.render.AtmospherePainter.drawEffect(c.nativeCanvas, size.width.toInt(), size.height.toInt(), a, t) }
    }
}

/** Aura Atmosphere y Living: efecto ambiental, intensidad, animación y luz según la hora. */
@Composable
internal fun ColumnScope.AtmospherePanel(vm: StudioViewModel, p: StudioProject) {
    val a = p.atmosphere
    Text("Efecto ambiental", style = MaterialTheme.typography.bodyMedium)
    Chips(app.aura.core.studio.AtmosphereEffect.entries.map { it to it.label }, a.effect) { e -> vm.atmosphere { it.copy(effect = e) } }
    if (a.active) {
        StudioSlider("Intensidad", a.intensity.toFloat(), 0f..100f, "${a.intensity} %", { v -> vm.atmosphereLive { it.copy(intensity = v.roundToInt()) } }, vm::commit)
        LabeledRow("Animado en el fondo", supporting = "Se mueve solo con la pantalla a la vista; apagado queda quieto y no gasta batería") {
            Switch(a.animate, { v -> vm.atmosphere { it.copy(animate = v) } })
        }
    }
    LabeledRow("Luz según la hora", supporting = "Amanecer rosado, hora dorada, atardecer violeta y noche azul (usa la salida y puesta del sol del clima)") {
        Switch(a.timeLight, { v -> vm.atmosphere { it.copy(timeLight = v) } })
    }
    if (a.timeLight) {
        StudioSlider("Fuerza de la luz", a.timeLightStrength.toFloat(), 0f..100f, "${a.timeLightStrength} %", { v -> vm.atmosphereLive { it.copy(timeLightStrength = v.roundToInt()) } }, vm::commit)
    }
    Text(
        "Con el ahorro de batería del teléfono activado, el fondo animado no mueve las partículas.",
        style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.padding(top = 6.dp),
    )
}

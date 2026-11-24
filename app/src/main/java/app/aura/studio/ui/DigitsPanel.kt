package app.aura.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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

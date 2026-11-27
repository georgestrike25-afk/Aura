package app.aura.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aura.R
import app.aura.core.studio.MaskStroke
import app.aura.ui.theme.Aura
import kotlin.math.roundToInt

/**
 * Edición manual de la máscara de profundidad: pincel/borrador con un dedo, zoom y desplazamiento
 * con dos. Funciona siempre, aunque la detección automática no esté disponible.
 */
@Composable
fun MaskEditorScreen(viewModel: StudioViewModel, onDone: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val overlay by viewModel.maskOverlay.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val photo = remember(history?.current?.id) { viewModel.previewPhotoBitmap }
    val project = history?.current

    var tool by remember { mutableStateOf(MaskTool.SMART) }
    val erase = tool == MaskTool.ERASE || tool == MaskTool.SMART_ERASE
    val handlingStroke by rememberUpdatedState(busy != null)
    var brushDp by remember { mutableFloatStateOf(28f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val live = remember { mutableStateListOf<Offset>() }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    // Al mover el tamaño del pincel se muestra un círculo del tamaño real en el centro de la foto.
    var brushPreview by remember { mutableStateOf(0L) }
    LaunchedEffect(brushPreview) { if (brushPreview != 0L) { kotlinx.coroutines.delay(1200); brushPreview = 0L } }

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(painterResource(R.drawable.ic_ui_back), "Volver", tint = Color.White) }
            Text("Máscara de profundidad", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::undo, enabled = busy == null && history?.canUndo == true) { Icon(painterResource(R.drawable.ic_ui_undo), "Deshacer", tint = Color.White) }
            IconButton(onClick = viewModel::redo, enabled = busy == null && history?.canRedo == true) { Icon(painterResource(R.drawable.ic_ui_redo), "Rehacer", tint = Color.White) }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (photo == null || project == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Aura.tokens.accent)
                return@Box
            }
            val imgW = photo.width.toFloat()
            val imgH = photo.height.toFloat()
            val density = androidx.compose.ui.platform.LocalDensity.current.density

            fun fitScale() = minOf(viewSize.width / imgW, viewSize.height / imgH).takeIf { it > 0f } ?: 1f
            fun toImage(p: Offset): Offset {
                val s = fitScale() * zoom
                val cx = viewSize.width / 2f + pan.x
                val cy = viewSize.height / 2f + pan.y
                return Offset(((p.x - cx) / s + imgW / 2f) / imgW, ((p.y - cy) / s + imgH / 2f) / imgH)
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(project.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var drawing = !handlingStroke
                            live.clear()
                            if (drawing) {
                                live += down.position
                                cursor = down.position
                            }
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) {
                                    // Dos dedos: zoom y desplazamiento; se descarta el trazo en curso.
                                    drawing = false
                                    live.clear()
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                                    pan += event.calculatePan()
                                } else if (drawing) {
                                    event.changes.firstOrNull()?.let { c -> live += c.position; cursor = c.position }
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.any { it.pressed })
                            if (drawing && !handlingStroke && live.isNotEmpty()) {
                                val start = toImage(live.first())
                                if (start.x in 0f..1f && start.y in 0f..1f) {
                                    val pts = live.flatMap { val q = toImage(it); listOf(q.x, q.y) }
                                    val radius = (brushDp * density / (fitScale() * zoom)) / maxOf(imgW, imgH)
                                    // La herramienta se lee ahora (no al abrir el editor): si no, «Quitar» quedaba como «agregar».
                                    val eraseNow = tool == MaskTool.ERASE || tool == MaskTool.SMART_ERASE
                                    val stroke = MaskStroke(pts, radius, eraseNow)
                                    if (tool == MaskTool.SMART || tool == MaskTool.SMART_ERASE) viewModel.refineStroke(stroke) else viewModel.addStroke(stroke)
                                }
                            }
                            live.clear()
                            cursor = null
                        }
                    },
            ) {
                val s = fitScale() * zoom
                val w = imgW * s
                val h = imgH * s
                val left = size.width / 2f + pan.x - w / 2f
                val top = size.height / 2f + pan.y - h / 2f
                val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
                val dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
                drawImage(photo, dstOffset = dstOffset, dstSize = dstSize)
                overlay?.let { drawImage(it, dstOffset = dstOffset, dstSize = dstSize) }
                if (live.size > 1) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(live[0].x, live[0].y)
                        live.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path,
                        color = if (erase) Color(0x99FFFFFF) else Color(0x99E5806B),
                        style = Stroke(width = brushDp * density * 2, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                    )
                }
                cursor?.let { drawCircle(Color.White, radius = brushDp * density, center = it, style = Stroke(width = 2f)) }
                if (brushPreview != 0L && cursor == null) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color(0x55E5806B), radius = brushDp * density, center = c)
                    drawCircle(Color.White, radius = brushDp * density, center = c, style = Stroke(width = 2f))
                }
            }
            busy?.let { status ->
                Column(
                    Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Aura.tokens.accent)
                    Text(status, color = Aura.tokens.mutedText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Panel compacto: la foto ocupa la mayor parte de la pantalla para corregir fino.
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = tool == MaskTool.SMART, enabled = busy == null,
                    onClick = { tool = MaskTool.SMART }, label = { Text("Inteligente") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_ui_auto), null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = tool == MaskTool.SMART_ERASE, enabled = busy == null,
                    onClick = { tool = MaskTool.SMART_ERASE }, label = { Text("Quitar") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_ui_auto), null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = tool == MaskTool.BRUSH, enabled = busy == null,
                    onClick = { tool = MaskTool.BRUSH }, label = { Text("Pincel") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_ui_brush), null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = tool == MaskTool.ERASE, enabled = busy == null,
                    onClick = { tool = MaskTool.ERASE }, label = { Text("Borrador") },
                )
                FilterChip(
                    selected = project?.mask?.inverted == true, enabled = busy == null,
                    onClick = { viewModel.edit { it.copy(mask = it.mask.copy(inverted = !it.mask.inverted)) } },
                    label = { Text("Invertir") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_ui_invert), null, Modifier.size(16.dp)) },
                )
            }
            SliderRow("Pincel", brushDp, 6f..80f, "${brushDp.roundToInt()}") { brushDp = it; brushPreview = System.nanoTime() }
            SliderRow("Zoom", zoom, 1f..8f, "%.1f×".format(zoom)) { zoom = it; if (it <= 1.01f) pan = Offset.Zero }
            SliderRow(
                "Bordes", (project?.mask?.feather ?: 0).toFloat(), 0f..30f, "${project?.mask?.feather ?: 0}",
                enabled = busy == null, onDone = viewModel::commit,
            ) { v -> viewModel.live { it.copy(mask = it.mask.copy(feather = v.roundToInt())) } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::undo, enabled = busy == null && history?.canUndo == true) {
                    Icon(painterResource(R.drawable.ic_ui_undo), "Deshacer", tint = MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = viewModel::redo, enabled = busy == null && history?.canRedo == true) {
                    Icon(painterResource(R.drawable.ic_ui_redo), "Rehacer", tint = MaterialTheme.colorScheme.onBackground)
                }
                TextButton(onClick = { viewModel.detectSubject() }, enabled = busy == null) { Text("Automático") }
                TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Ajustar") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDone, enabled = busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Aura.tokens.accent, contentColor = Aura.tokens.onAccent),
                ) { Text("Listo") }
            }
            val hint = when (tool) {
                MaskTool.SMART -> "Pasá el dedo por el objeto: se selecciona entero, con su borde."
                MaskTool.SMART_ERASE -> "Pasá el dedo por lo que sobra: se quita con su borde."
                MaskTool.BRUSH -> "Pintá lo que va delante del reloj. Dos dedos o la barra Zoom para acercar."
                MaskTool.ERASE -> "Borrá lo que no va delante del reloj. Dos dedos o la barra Zoom para acercar."
            }
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Aura.tokens.mutedText, maxLines = 2)
        }
    }
}

/** Fila compacta: nombre, barra y valor. */
@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String,
    enabled: Boolean = true, onDone: (() -> Unit)? = null, onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.width(56.dp))
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled,
            onValueChangeFinished = onDone, modifier = Modifier.weight(1f),
        )
        Text(valueText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private enum class MaskTool { SMART, SMART_ERASE, BRUSH, ERASE }

package app.aura.studio.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/** Arrastre, pellizco y giro con aviso de fin, para registrar un solo paso de deshacer por gesto. */
fun Modifier.transformGesture(
    key: Any?,
    onTransform: (pan: Offset, zoom: Float, rotation: Float) -> Unit,
    onEnd: () -> Unit,
    enabled: Boolean = true,
    /** Toque sin arrastre (posición en px del área); null = no se usa. */
    onTap: ((Offset) -> Unit)? = null,
): Modifier = if (!enabled) this else pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var travelled = 0f
        var transformed = false
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val rotation = event.calculateRotation()
            travelled += pan.getDistance()
            // Hasta superar el umbral de arrastre no se mueve nada: así un toque no desplaza por error.
            val moving = transformed || travelled > viewConfiguration.touchSlop || zoom != 1f || event.changes.size > 1
            if (moving && (zoom != 1f || pan != Offset.Zero || rotation != 0f)) {
                transformed = true
                onTransform(pan, zoom, rotation)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
        if (!transformed && onTap != null) onTap(down.position) else onEnd()
    }
}

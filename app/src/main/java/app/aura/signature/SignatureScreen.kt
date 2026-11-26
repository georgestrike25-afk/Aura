package app.aura.signature

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aura.core.signature.GalleryCollection
import app.aura.core.signature.GalleryWork
import app.aura.core.signature.Signature
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Row
import app.aura.core.signature.SignatureWork
import app.aura.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignatureViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SignatureRepository(app)

    private val _catalog = MutableStateFlow(repo.cached())
    val catalog: StateFlow<List<GalleryCollection>> = _catalog.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Sube imágenes del teléfono a «Mis obras». */
    fun import(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty() || _importing.value) return@launch
        _importing.value = true
        try {
            val n = repo.importImages(uris)
            _catalog.value = repo.cached()
            _message.value = when {
                n == 0 -> "No se pudo leer ninguna de esas imágenes."
                n < uris.size -> "Se agregaron $n de ${uris.size} imágenes a «Mis obras»."
                n == 1 -> "Imagen agregada a «Mis obras»."
                else -> "Se agregaron $n imágenes a «Mis obras»."
            }
        } finally {
            _importing.value = false
        }
    }

    fun deleteMine(id: String) {
        repo.deleteMine(id)
        _catalog.value = repo.cached()
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _opening = MutableStateFlow<String?>(null)
    /** Id de la obra que se está descargando para abrir en Studio. */
    val opening: StateFlow<String?> = _opening.asStateFlow()

    private val _favorites = MutableStateFlow(repo.favorites())
    val favorites = _favorites.asStateFlow()

    fun toggleFavorite(id: String) {
        val updated = _favorites.value.let { if (id in it) it - id else it + id }
        repo.saveFavorites(updated)
        _favorites.value = updated
    }

    val thumbs = mutableStateMapOf<String, ImageBitmap>()

    fun refresh() = viewModelScope.launch {
        if (_loading.value) return@launch
        _loading.value = true
        try {
            repo.refresh()
                .onSuccess { _catalog.value = it; _error.value = null }
                .onFailure {
                    _catalog.value = repo.cached()
                    _error.value = if (!repo.hasRemoteCache()) "No se pudo descargar la galería. Revisá la conexión." else null
                }
        } finally {
            _loading.value = false
        }
    }

    fun loadThumb(base: String, w: SignatureWork) {
        val key = base + w.thumb
        if (thumbs.containsKey(key)) return
        viewModelScope.launch { repo.thumb(base, w)?.let { bmp: Bitmap -> thumbs[key] = bmp.asImageBitmap() } }
    }

    /** Descarga la obra completa y la entrega como Uri para crear un proyecto de Studio. */
    fun open(base: String, w: SignatureWork, onReady: (Uri, java.io.File?) -> Unit) = viewModelScope.launch {
        if (_opening.value != null) return@launch
        _error.value = null
        _opening.value = w.id
        val f = repo.full(base, w)
        val mask = if (f != null) repo.mask(base, w) else null
        _opening.value = null
        if (f == null) _error.value = "No se pudo descargar «${w.title}»." else onReady(Uri.fromFile(f), mask)
    }
}

/**
 * Aura Signature: galería de fondos pensados para el reloj. Junta las obras publicadas (se actualizan
 * solas mientras la pantalla está abierta) con las que la persona sube desde el teléfono. Tocar una obra
 * la abre como proyecto nuevo en Studio.
 */
@Composable
fun SignatureScreen(onUse: (Uri, SignatureWork, java.io.File?) -> Unit, vm: SignatureViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh().join()
                delay(REFRESH_EVERY_MS)
            }
        }
    }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var previewKey by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        vm.import(uris)
    }
    val selected: GalleryWork? = catalog.asSequence().flatMap { it.items.asSequence() }
        .firstOrNull { it.base + it.work.id == previewKey }
    if (selected != null) {
        val w = selected.work
        val mine = selected.base == Signature.LOCAL_BASE
        AlertDialog(
            onDismissRequest = { if (opening == null) previewKey = null },
            title = { Text(w.title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    vm.thumbs[selected.base + w.thumb]?.let {
                        Image(it, contentDescription = w.title, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(320.dp))
                    }
                    if (opening == w.id) CircularProgressIndicator(Modifier.padding(12.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row {
                        TextButton(onClick = { vm.toggleFavorite(w.id) }) {
                            Text(if (w.id in favorites) "Quitar favorito" else "Favorito")
                        }
                        if (mine) TextButton(enabled = opening == null, onClick = { vm.deleteMine(w.id); previewKey = null }) {
                            Text("Borrar")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = opening == null, onClick = {
                    vm.open(selected.base, w) { uri, mask ->
                        previewKey = null
                        onUse(uri, w, mask)
                    }
                }) { Text("Abrir en Studio") }
            },
            dismissButton = {
                TextButton(enabled = opening == null, onClick = { previewKey = null }) { Text("Cerrar") }
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Signature", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Fondos pensados para que el reloj viva dentro de la imagen. Tocá uno para abrirlo en Studio. " +
                        "Las obras nuevas que se publiquen aparecen solas.",
                    style = MaterialTheme.typography.bodyMedium, color = Aura.tokens.mutedText,
                )
                Spacer(Modifier.height(10.dp))
                Button(enabled = !importing, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (importing) "Subiendo…" else "Subir mis imágenes") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.padding(top = 6.dp)) }
                Row {
                    TextButton(enabled = !loading, onClick = { vm.refresh() }) {
                        Text(if (loading) "Actualizando…" else "Actualizar galería")
                    }
                    TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                        Text(if (favoritesOnly) "Ver todas" else "Ver favoritos")
                    }
                }
            }
        }
        if (catalog.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loading) CircularProgressIndicator(color = Aura.tokens.accent)
                    else Text("Todavía no hay obras. Subí las tuyas con el botón de arriba.", style = MaterialTheme.typography.bodyMedium)
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { vm.refresh() }) { Text("Reintentar") }
                    }
                }
            }
            return@LazyColumn
        }
        if (favoritesOnly && catalog.none { col -> col.items.any { it.work.id in favorites } }) {
            item { Text("Todavía no guardaste favoritos.", Modifier.padding(20.dp)) }
        }
        catalog.forEach { col ->
            val works = if (favoritesOnly) col.items.filter { it.work.id in favorites } else col.items
            if (works.isEmpty()) return@forEach
            item(key = "col-" + col.id) {
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                    Text(col.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                    if (col.description.isNotBlank()) {
                        Text(col.description, style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(works, key = { it.base + it.work.id }) { gw ->
                            val w = gw.work
                            LaunchedEffect(gw.base, w.thumb) { vm.loadThumb(gw.base, w) }
                            Column(Modifier.width(150.dp).clickable(enabled = opening == null) { previewKey = gw.base + w.id }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(w.width / w.height.toFloat()).clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    vm.thumbs[gw.base + w.thumb]?.let { Image(it, contentDescription = w.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                        ?: CircularProgressIndicator(Modifier.padding(24.dp), color = Aura.tokens.accent, strokeWidth = 2.dp)
                                    if (opening == w.id) CircularProgressIndicator(color = Aura.tokens.accent)
                                }
                                Text(
                                    (if (w.id in favorites) "★ " else "") + w.title,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Las obras se descargan al verlas y quedan guardadas en el teléfono. «Mis obras» nunca sale del teléfono.",
                style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}

/** Cada cuánto se revisa si hay obras nuevas con la pantalla abierta (GitHub cachea ~5 min). */
private const val REFRESH_EVERY_MS = 5 * 60_000L

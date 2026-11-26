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
import app.aura.core.signature.SignatureCatalog
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
    val catalog: StateFlow<Pair<SignatureCatalog, String>?> = _catalog.asStateFlow()

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
                .onFailure { _error.value = if (_catalog.value == null) "No se pudo descargar la galería. Revisá la conexión." else null }
        } finally {
            _loading.value = false
        }
    }

    fun loadThumb(base: String, w: SignatureWork) {
        if (thumbs.containsKey(w.thumb)) return
        viewModelScope.launch { repo.thumb(base, w)?.let { bmp: Bitmap -> thumbs[w.thumb] = bmp.asImageBitmap() } }
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
 * Aura Signature: galería de fondos originales pensados para el reloj (espacio libre arriba,
 * protagonistas moderados). Tocar una obra la abre como proyecto nuevo en Studio.
 */
@Composable
fun SignatureScreen(onUse: (Uri, SignatureWork, java.io.File?) -> Unit, vm: SignatureViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh().join()
                delay(60_000L)
            }
        }
    }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentCatalog = catalog
    val selected = currentCatalog?.first?.collections?.asSequence()
        ?.flatMap { it.items.asSequence() }?.firstOrNull { it.id == previewId }
    if (selected != null && currentCatalog != null) {
        AlertDialog(
            onDismissRequest = { if (opening == null) previewId = null },
            title = { Text(selected.title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    vm.thumbs[selected.thumb]?.let {
                        Image(it, contentDescription = selected.title, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(320.dp))
                    }
                    if (opening == selected.id) CircularProgressIndicator(Modifier.padding(12.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = opening == null, onClick = {
                    vm.open(currentCatalog.second, selected) { uri, mask ->
                        previewId = null
                        onUse(uri, selected, mask)
                    }
                }) { Text("Abrir en Studio") }
            },
            dismissButton = {
                TextButton(enabled = opening == null, onClick = { previewId = null }) { Text("Cerrar") }
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Signature", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Obras originales de Aura, pensadas para que el reloj viva dentro de la imagen. Tocá una para abrirla en Studio.",
                    style = MaterialTheme.typography.bodyMedium, color = Aura.tokens.mutedText,
                )
                TextButton(enabled = !loading, onClick = { vm.refresh() }) {
                    Text(if (loading) "Actualizando…" else "Actualizar galería")
                }
                TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                    Text(if (favoritesOnly) "Ver todas las obras" else "Ver favoritos")
                }
            }
        }
        val c = catalog
        if (c == null) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loading) CircularProgressIndicator(color = Aura.tokens.accent)
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { vm.refresh() }) { Text("Reintentar") }
                    }
                }
            }
            return@LazyColumn
        }
        val (cat, base) = c
        if (cat.collections.isEmpty()) {
            item { Text("Las colecciones publicadas aparecerán acá automáticamente.", Modifier.padding(20.dp)) }
        } else if (favoritesOnly && favorites.isEmpty()) {
            item { Text("Todavía no guardaste favoritos.", Modifier.padding(20.dp)) }
        }
        cat.collections.forEach { col ->
            val works = if (favoritesOnly) col.items.filter { it.id in favorites } else col.items
            if (works.isEmpty()) return@forEach
            item(key = col.id) {
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                    Text(col.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                    if (col.description.isNotBlank()) {
                        Text(col.description, style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(works, key = { it.id }) { w ->
                            LaunchedEffect(base, w.thumb) { vm.loadThumb(base, w) }
                            Column(Modifier.width(150.dp).clickable(enabled = opening == null) { previewId = w.id }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(w.width / w.height.toFloat()).clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    vm.thumbs[w.thumb]?.let { Image(it, contentDescription = w.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                        ?: CircularProgressIndicator(Modifier.padding(24.dp), color = Aura.tokens.accent, strokeWidth = 2.dp)
                                    if (opening == w.id) CircularProgressIndicator(color = Aura.tokens.accent)
                                }
                                Text(w.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                                TextButton(onClick = { vm.toggleFavorite(w.id) }) {
                                    Text(if (w.id in favorites) "Quitar favorito" else "Guardar favorito")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                cat.license.ifBlank { "Obras originales de Aura" } + ". Se descargan al verlas y quedan guardadas en el teléfono.",
                style = MaterialTheme.typography.bodySmall, color = Aura.tokens.mutedText, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}


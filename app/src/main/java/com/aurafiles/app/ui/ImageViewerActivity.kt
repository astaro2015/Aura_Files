package com.aurafiles.app.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.aurafiles.app.data.FileRepository
import com.aurafiles.app.data.FastDocumentListing
import com.aurafiles.app.index.StorageIndexer
import androidx.exifinterface.media.ExifInterface
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.model.DeleteAnimationMode
import com.aurafiles.app.ui.theme.AuraFilesTheme
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        val parentUri = intent.getStringExtra(EXTRA_PARENT_URI)?.takeIf(String::isNotBlank)?.let(Uri::parse)
        val initialName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val sessionImages = intent.getStringExtra(EXTRA_SESSION)?.let(ImageViewerSessions::take).orEmpty()
        setContent {
            AuraFilesTheme {
                ImageViewerScreen(
                    initialUri = initialUri,
                    parentUri = parentUri,
                    initialName = initialName,
                    sessionImages = sessionImages,
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "image_uri"
        private const val EXTRA_PARENT_URI = "image_parent_uri"
        private const val EXTRA_NAME = "image_name"
        private const val EXTRA_SESSION = "image_session"

        fun start(context: Context, entry: FileEntry, siblings: List<FileEntry> = emptyList()) {
            val candidates = siblings.asSequence()
                .filterNot(FileEntry::isDirectory)
                .filter { sibling ->
                    sibling.mimeType?.startsWith("image/") == true ||
                        sibling.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                }
                .distinctBy { it.uri.toString() }
                .map { ViewerImage(it.uri, it.name, it.parentUri) }
                .toMutableList()
            if (candidates.none { it.uri == entry.uri }) {
                candidates.add(0, ViewerImage(entry.uri, entry.name, entry.parentUri))
            }
            val session = if (candidates.size > 1) ImageViewerSessions.put(candidates) else null
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java)
                    .putExtra(EXTRA_URI, entry.uri.toString())
                    .putExtra(EXTRA_PARENT_URI, entry.parentUri?.toString().orEmpty())
                    .putExtra(EXTRA_NAME, entry.name)
                    .putExtra(EXTRA_SESSION, session)
            )
        }
    }
}

private data class ViewerImage(val uri: Uri, val name: String, val parentUri: Uri? = null)

private object ImageViewerSessions {
    private var sequence = 0L
    private val sessions = LinkedHashMap<String, List<ViewerImage>>()

    @Synchronized fun put(images: List<ViewerImage>): String {
        val key = "images-${++sequence}-${System.nanoTime()}"
        sessions[key] = images
        while (sessions.size > 3) sessions.remove(sessions.keys.first())
        return key
    }

    @Synchronized fun take(key: String): List<ViewerImage>? = sessions.remove(key)
}
private data class ExifDetails(
    val date: String?,
    val width: Int?,
    val height: Int?,
    val camera: String?,
    val iso: String?,
    val exposure: String?,
    val focal: String?,
    val gps: String?,
)

@Composable
private fun ImageViewerScreen(
    initialUri: Uri,
    parentUri: Uri?,
    initialName: String,
    sessionImages: List<ViewerImage>,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deleteAnimationMode = remember(context) { FileRepository(context.applicationContext).deleteAnimationMode() }
    var deletingUri by remember { mutableStateOf<Uri?>(null) }
    val sourceImages by produceState(
        initialValue = sessionImages.takeIf { it.isNotEmpty() }
            ?: listOf(ViewerImage(initialUri, initialName, parentUri)),
        parentUri,
        initialUri,
        sessionImages,
    ) {
        value = if (sessionImages.isNotEmpty()) sessionImages else
            withContext(Dispatchers.IO) { neighboringImages(context, parentUri, initialUri, initialName) }
    }
    var deletedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    val images = sourceImages.filterNot { it.uri in deletedUris }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { images.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()
    var infoOpen by remember { mutableStateOf(false) }
    var rotation by remember { mutableIntStateOf(0) }

    // The first composition contains only the opened image. When the full neighbour list
    // arrives, keep showing that same URI rather than silently jumping to list index 0.
    LaunchedEffect(sourceImages) {
        if (sourceImages.isNotEmpty()) {
            val wanted = sourceImages.indexOfFirst { it.uri == initialUri }.coerceAtLeast(0)
            val shown = sourceImages.getOrNull(pagerState.currentPage)?.uri
            if (shown != initialUri || pagerState.currentPage !in images.indices) {
                pagerState.scrollToPage(wanted.coerceIn(sourceImages.indices))
            }
        }
    }
    LaunchedEffect(images.size) {
        if (images.isEmpty()) {
            onClose()
        } else if (pagerState.currentPage > images.lastIndex) {
            pagerState.scrollToPage(images.lastIndex)
        }
    }
    LaunchedEffect(pagerState.currentPage) { rotation = 0 }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
                Text(
                    images.getOrNull(pagerState.currentPage)?.name.orEmpty(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text("${pagerState.currentPage + 1} / ${images.size}")
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Icon(Icons.Rounded.RotateRight, contentDescription = "Повернуть просмотр")
                }
                IconButton(onClick = { infoOpen = true }) { Icon(Icons.Rounded.Info, contentDescription = "EXIF") }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { page -> images.getOrNull(page)?.uri.toString() },
                reverseLayout = false,
            ) { page ->
                images.getOrNull(page)?.let { image ->
                    ZoomableViewerImage(
                        image = image,
                        rotation = rotation,
                        deleting = deletingUri == image.uri,
                        deleteAnimationMode = deleteAnimationMode,
                    )
                }
            }
        }

        // Always keep deletion visible in the actual full-screen image area. The old toolbar
        // placement was easy to miss (and could be hidden/crowded on narrow screens).
        IconButton(
            onClick = {
                val current = images.getOrNull(pagerState.currentPage) ?: return@IconButton
                if (deletingUri != null) return@IconButton
                deletingUri = current.uri
                scope.launch {
                    val delayMillis = deleteAnimationMode.preDeleteDelayMillis()
                    if (delayMillis > 0L) delay(delayMillis)
                    val result = withContext(Dispatchers.IO) { moveViewerImageToTrash(context, current) }
                    result.onSuccess {
                        deletedUris = deletedUris + current.uri
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: "Не удалось переместить фото в корзину",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    deletingUri = null
                }
            },
            enabled = deletingUri == null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(14.dp)
                .size(42.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), CircleShape),
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "В корзину",
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (infoOpen) {
        val current = images.getOrNull(pagerState.currentPage)
        val exif by produceState<ExifDetails?>(initialValue = null, current?.uri) {
            value = if (current == null) null else withContext(Dispatchers.IO) { readExif(context, current.uri) }
        }
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text("Информация об изображении") },
            text = {
                if (exif == null) CircularProgressIndicator()
                else Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val e = exif!!
                    Text("Разрешение: ${e.width ?: "?"} × ${e.height ?: "?"}")
                    e.date?.let { Text("Снято: $it") }
                    e.camera?.let { Text("Камера: $it") }
                    e.iso?.let { Text("ISO: $it") }
                    e.exposure?.let { Text("Выдержка: $it") }
                    e.focal?.let { Text("Фокусное: $it") }
                    e.gps?.let { Text("GPS: $it") }
                }
            },
            confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("Готово") } },
        )
    }
}

@Composable
private fun ZoomableViewerImage(
    image: ViewerImage,
    rotation: Int,
    deleting: Boolean,
    deleteAnimationMode: DeleteAnimationMode,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val targetEdge = remember(context) {
        max(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
            .times(2)
            .coerceIn(1600, 4096)
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, image.uri, targetEdge) {
        value = withContext(Dispatchers.IO) { decodeForViewer(context, image.uri, targetEdge) }
    }
    var scale by remember(image.uri) { mutableFloatStateOf(1f) }
    var x by remember(image.uri) { mutableFloatStateOf(0f) }
    var y by remember(image.uri) { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val loaded = bitmap
        if (loaded == null) {
            CircularProgressIndicator()
        } else {
            // The delete effect must use the picture edges, not the pager page edges.
            // With fillMaxSize + ContentScale.Fit most portrait/landscape photos had large
            // transparent margins, so the burn spent much of its animation eating invisible space.
            val displayFit = min(
                widthPx / loaded.width.coerceAtLeast(1),
                heightPx / loaded.height.coerceAtLeast(1),
            ).coerceAtLeast(0.01f)
            val fit = displayFit.coerceAtMost(1f)
            val pixelScale = (1f / fit.coerceAtLeast(0.01f)).coerceIn(1f, 12f)
            val imageWidth = with(density) { (loaded.width * displayFit).toDp() }
            val imageHeight = with(density) { (loaded.height * displayFit).toDp() }
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(imageWidth)
                    .height(imageHeight)
                    .auraDeleteEffect(
                        active = deleting,
                        mode = deleteAnimationMode,
                        seed = image.uri.toString().hashCode(),
                    )
                    .pointerInput(image.uri) {
                        // Do not steal a normal one-finger horizontal drag from HorizontalPager
                        // while the image is fitted. Two fingers always control zoom/pan; once
                        // zoomed in, one finger pans the image.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                val handle = pressed.size >= 2 || scale > 1.01f
                                if (handle && pressed.isNotEmpty()) {
                                    var zoom = 1f
                                    var pan = Offset.Zero
                                    if (pressed.size >= 2) {
                                        val first = pressed[0]
                                        val second = pressed[1]
                                        val previousDelta = first.previousPosition - second.previousPosition
                                        val currentDelta = first.position - second.position
                                        val previousDistance = previousDelta.getDistance()
                                        if (previousDistance > 0.5f) {
                                            zoom = (currentDelta.getDistance() / previousDistance).coerceIn(0.5f, 2f)
                                        }
                                        val previousCentroid = Offset(
                                            (first.previousPosition.x + second.previousPosition.x) / 2f,
                                            (first.previousPosition.y + second.previousPosition.y) / 2f,
                                        )
                                        val currentCentroid = Offset(
                                            (first.position.x + second.position.x) / 2f,
                                            (first.position.y + second.position.y) / 2f,
                                        )
                                        pan = currentCentroid - previousCentroid
                                    } else {
                                        val change = pressed.first()
                                        pan = change.position - change.previousPosition
                                    }
                                    scale = (scale * zoom).coerceIn(1f, 12f)
                                    if (scale <= 1.01f) {
                                        scale = 1f
                                        x = 0f
                                        y = 0f
                                    } else {
                                        x += pan.x
                                        y += pan.y
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(image.uri, pixelScale) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1.05f) { scale = 1f; x = 0f; y = 0f }
                            else scale = min(3f, pixelScale)
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = x
                        translationY = y
                        rotationZ = rotation.toFloat()
                    },
            )
        }
    }
}

private fun neighboringImages(context: Context, parentUri: Uri?, current: Uri, currentName: String): List<ViewerImage> {
    val result = mutableListOf<ViewerImage>()
    val parent = parentUri?.let { FastDocumentListing.resolve(context, it) }
    if (parent != null) {
        FastDocumentListing.list(context, parent)
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { info ->
                info.mimeType?.startsWith("image/") == true ||
                    info.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            }
            .sortedBy { it.name.lowercase() }
            .forEach { result += ViewerImage(it.uri, it.name, parentUri) }
    }
    if (result.none { it.uri == current }) result += ViewerImage(current, currentName, parentUri)
    return result.distinctBy { it.uri.toString() }
}

private fun moveViewerImageToTrash(context: Context, image: ViewerImage): Result<Unit> = runCatching {
    val parentUri = image.parentUri ?: throw IOException("Не удалось определить папку изображения")
    val document = when (image.uri.scheme) {
        ContentResolver.SCHEME_FILE -> {
            val path = image.uri.path ?: throw IOException("Не удалось определить путь к изображению")
            DocumentFile.fromFile(File(path))
        }
        else -> DocumentFile.fromSingleUri(context, image.uri)
            ?: throw IOException("Не удалось открыть изображение для удаления")
    }
    val repository = FileRepository(context.applicationContext)
    val root = repository.restoreRoot() ?: throw IOException("Корзина Aura недоступна: хранилище не подключено")
    repository.moveToTrash(
        root = root,
        entry = FileEntry(
            document = document,
            name = image.name,
            uri = image.uri,
            isDirectory = false,
            mimeType = document.type,
            size = document.length(),
            modifiedAt = document.lastModified(),
            parentUri = parentUri,
        ),
    )
    // The viewer is a separate Activity, so the main browser would otherwise keep its
    // old in-memory FileEntry and show a dead thumbnail/name after we return. Keep the
    // Room index in sync and hand the deleted URI back to the main UI process-wide.
    ExternalFileChanges.recordDeleted(image.uri)
    runCatching { StorageIndexer(context.applicationContext).removeUris(root, listOf(image.uri)) }
    runCatching { repository.clearAnalysisCache() }
}

private fun decodeForViewer(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (uri.scheme == ContentResolver.SCHEME_FILE) BitmapFactory.decodeFile(uri.path, bounds)
    else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    val decoded = if (uri.scheme == ContentResolver.SCHEME_FILE) BitmapFactory.decodeFile(uri.path, opts)
    else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    decoded ?: return null
    val exif = runCatching { openExif(context, uri) }.getOrNull() ?: return decoded
    if (exif.rotationDegrees == 0 && !exif.isFlipped) return decoded
    val matrix = android.graphics.Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        if (exif.rotationDegrees != 0) postRotate(exif.rotationDegrees.toFloat())
    }
    return runCatching {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { transformed ->
            if (transformed !== decoded) decoded.recycle()
        }
    }.getOrElse { decoded }
}

private fun openExif(context: Context, uri: Uri): ExifInterface =
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        ExifInterface(requireNotNull(uri.path))
    } else {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Не удалось открыть EXIF")
        descriptor.use { ExifInterface(it.fileDescriptor) }
    }

private fun readExif(context: Context, uri: Uri): ExifDetails {
    val exif = openExif(context, uri)
    val dateRaw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
    val camera = listOfNotNull(
        exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotBlank),
        exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotBlank),
    ).distinct().joinToString(" ").ifBlank { null }
    val latLong = exif.latLong
    return ExifDetails(
        date = dateRaw,
        width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 },
        height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 },
        camera = camera,
        iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
        exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "$it с" },
        focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "$it мм" },
        gps = latLong?.let { "%.6f, %.6f".format(it[0], it[1]) },
    )
}

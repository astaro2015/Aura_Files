package com.aurafiles.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Size
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurafiles.app.model.FileEntry
import com.aurafiles.app.ui.theme.AuraBlue
import com.aurafiles.app.ui.theme.AuraGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.max

@Composable
internal fun FileThumbnail(
    entry: FileEntry,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val canPreview = isImage(entry) || isVideo(entry)
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.uri, entry.modifiedAt, canPreview) {
        value = if (canPreview) withContext(Dispatchers.IO) {
            runCatching { loadThumbnail(context, entry, 240) }.getOrNull()
        } else null
    }
    if (bitmap == null) fallback()
    else Image(
        bitmap = bitmap!!.asImageBitmap(),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(9.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
internal fun FilePreviewDialog(
    entry: FileEntry,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 5.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            entry.mimeType ?: "Неизвестный формат",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.AutoMirrored.Rounded.Launch, contentDescription = "Открыть в другом приложении")
                    }
                }
                PreviewBody(entry = entry, onOpenExternal = onOpenExternal, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewBody(entry: FileEntry, onOpenExternal: () -> Unit, modifier: Modifier = Modifier) {
    when {
        isImage(entry) -> BitmapPreview(entry, modifier)
        isPdf(entry) -> PdfPreview(entry, modifier)
        isText(entry) -> TextPreview(entry, modifier)
        isVideo(entry) -> MediaPreview(entry, modifier, audioOnly = false)
        isAudio(entry) -> MediaPreview(entry, modifier, audioOnly = true)
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = AuraBlue, modifier = Modifier.size(72.dp))
                Text("Встроенный просмотр этого формата пока недоступен")
                TextButton(onClick = onOpenExternal) { Text("Открыть в другом приложении") }
            }
        }
    }
}

@Composable
private fun BitmapPreview(entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.uri, entry.modifiedAt) {
        value = withContext(Dispatchers.IO) { runCatching { loadImage(context, entry) }.getOrNull() }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        if (bitmap == null) CircularProgressIndicator() else ZoomableImage(bitmap!!)
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale == 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
    )
}

@Composable
private fun PdfPreview(entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    var pageIndex by remember(entry.uri) { mutableIntStateOf(0) }
    val page by produceState<PdfPage?>(initialValue = null, entry.uri, pageIndex) {
        value = withContext(Dispatchers.IO) { runCatching { renderPdfPage(context, entry, pageIndex) }.getOrNull() }
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (page == null) CircularProgressIndicator()
            else Image(
                bitmap = page!!.bitmap.asImageBitmap(),
                contentDescription = "Страница ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit,
            )
        }
        val count = page?.count ?: 1
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pageIndex -= 1 }, enabled = pageIndex > 0) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Предыдущая страница")
            }
            Text("${pageIndex + 1} из $count", fontWeight = FontWeight.Medium)
            IconButton(onClick = { pageIndex += 1 }, enabled = pageIndex + 1 < count) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Следующая страница")
            }
        }
    }
}

@Composable
private fun TextPreview(entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    val text by produceState<String?>(initialValue = null, entry.uri, entry.modifiedAt) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(MAX_TEXT_PREVIEW_CHARS)
                    val count = reader.read(buffer)
                    if (count <= 0) "" else String(buffer, 0, count) + if (count == buffer.size) "\n\n…предпросмотр ограничен…" else ""
                } ?: throw IOException("Не удалось прочитать файл")
            }.getOrElse { "Не удалось показать текст: ${it.message}" }
        }
    }
    Box(modifier.fillMaxSize().padding(14.dp)) {
        if (text == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else Text(
            text!!,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun MediaPreview(entry: FileEntry, modifier: Modifier, audioOnly: Boolean) {
    val context = LocalContext.current
    var videoView by remember(entry.uri) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(entry.uri) {
        onDispose { videoView?.stopPlayback() }
    }
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (audioOnly) {
            Spacer(Modifier.height(50.dp))
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = AuraGreen, modifier = Modifier.size(88.dp))
            Text(entry.name, modifier = Modifier.padding(16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AndroidView(
            factory = {
                VideoView(context).apply {
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(entry.uri)
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        start()
                        controls.show(0)
                    }
                    videoView = this
                }
            },
            modifier = if (audioOnly) Modifier.fillMaxWidth().height(120.dp) else Modifier.fillMaxSize(),
        )
    }
}

private fun loadThumbnail(context: Context, entry: FileEntry, edge: Int): Bitmap? {
    val cacheKey = "${entry.uri}|${entry.modifiedAt}|$edge"
    THUMBNAIL_CACHE.get(cacheKey)?.let { return it }
    val bitmap = loadThumbnailUncached(context, entry, edge) ?: return null
    THUMBNAIL_CACHE.put(cacheKey, bitmap)
    return bitmap
}

private fun loadThumbnailUncached(context: Context, entry: FileEntry, edge: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && entry.uri.scheme == "content") {
        return context.contentResolver.loadThumbnail(entry.uri, Size(edge, edge), CancellationSignal())
    }
    return if (isImage(entry)) {
        decodeSampledImage(context, entry, edge)
    } else {
        MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(context, entry.uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
                } else {
                    retriever.frameAtTime?.let { raw ->
                        val largest = max(raw.width, raw.height)
                        if (largest <= edge) raw else {
                            val scale = edge.toFloat() / largest
                            Bitmap.createScaledBitmap(
                                raw,
                                (raw.width * scale).roundToInt().coerceAtLeast(1),
                                (raw.height * scale).roundToInt().coerceAtLeast(1),
                                true,
                            ).also { raw.recycle() }
                        }
                    }
                }
            } finally {
                retriever.release()
            }
        }
    }
}

private fun loadImage(context: Context, entry: FileEntry): Bitmap {
    return decodeSampledImage(context, entry, MAX_IMAGE_EDGE)
        ?: throw IOException("Не удалось декодировать изображение")
}

private fun decodeSampledImage(context: Context, entry: FileEntry, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (entry.uri.scheme == "file") {
        BitmapFactory.decodeFile(entry.uri.path, bounds)
    } else {
        context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = if (entry.uri.scheme == "file") {
        BitmapFactory.decodeFile(entry.uri.path, options)
    } else {
        context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
    return bitmap
}

private fun renderPdfPage(context: Context, entry: FileEntry, requestedPage: Int): PdfPage {
    openDescriptor(context, entry.uri).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val pageIndex = requestedPage.coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(pageIndex).use { page ->
                val scale = (1400f / page.width.coerceAtLeast(1)).coerceAtMost(2.5f)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return PdfPage(bitmap, renderer.pageCount)
            }
        }
    }
}

private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
    return if (uri.scheme == "file") {
        ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Не удалось открыть файл")
    }
}

private fun isImage(entry: FileEntry) = entry.mimeType?.startsWith("image/") == true ||
    entry.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "heic")

private fun isVideo(entry: FileEntry) = entry.mimeType?.startsWith("video/") == true
private fun isAudio(entry: FileEntry) = entry.mimeType?.startsWith("audio/") == true
private fun isPdf(entry: FileEntry) = entry.mimeType == "application/pdf" || entry.name.endsWith(".pdf", true)
private fun isText(entry: FileEntry) = entry.mimeType?.startsWith("text/") == true ||
    entry.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "xml", "csv", "log", "kt", "java", "c", "cpp", "h")

private data class PdfPage(val bitmap: Bitmap, val count: Int)
private const val MAX_TEXT_PREVIEW_CHARS = 300_000
private const val MAX_IMAGE_EDGE = 2_048
private const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
private val THUMBNAIL_CACHE = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

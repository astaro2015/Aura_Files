package com.aurafiles.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
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
import androidx.compose.material.icons.rounded.Fullscreen
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.sqrt

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
            THUMBNAIL_DECODER.withPermit {
                runCatching { loadThumbnail(context, entry, 240) }.getOrNull()
            }
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
                                        // Fullscreen is launched from MediaPreview so playback position is preserved.
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
        isVideo(entry) -> MediaPreview(entry, modifier, audioOnly = false, onOpenExternal = onOpenExternal)
        isAudio(entry) -> MediaPreview(entry, modifier, audioOnly = true, onOpenExternal = onOpenExternal)
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
    val result by produceState<Result<Bitmap>?>(initialValue = null, entry.uri, entry.modifiedAt) {
        value = withContext(Dispatchers.IO) { runCatching { loadImage(context, entry) } }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        when {
            result == null -> CircularProgressIndicator()
            result!!.isSuccess -> ZoomableImage(result!!.getOrThrow())
            else -> Text(
                "Не удалось показать изображение: ${result!!.exceptionOrNull()?.message ?: "повреждённый или неподдерживаемый файл"}",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val result by produceState<Result<PdfPage>?>(initialValue = null, entry.uri, pageIndex) {
        value = withContext(Dispatchers.IO) { runCatching { renderPdfPage(context, entry, pageIndex) } }
    }
    val page = result?.getOrNull()
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                result == null -> CircularProgressIndicator()
                page != null -> Image(
                    bitmap = page.bitmap.asImageBitmap(),
                    contentDescription = "Страница ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
                else -> Text(
                    "Не удалось показать PDF: ${result!!.exceptionOrNull()?.message ?: "повреждённый или неподдерживаемый файл"}",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    readTextPreview(reader)
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
private fun MediaPreview(
    entry: FileEntry,
    modifier: Modifier,
    audioOnly: Boolean,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(entry.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(entry.uri))
            prepare()
            playWhenReady = true
        }
    }
    val fullscreenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val position = result.data?.getLongExtra(
                FullscreenVideoActivity.EXTRA_POSITION,
                player.currentPosition,
            ) ?: player.currentPosition
            player.seekTo(position.coerceAtLeast(0L))
            player.play()
        }
    }
    var playbackError by remember(entry.uri) { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Этот видеокодек не поддерживается устройством"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (audioOnly) {
            Spacer(Modifier.height(50.dp))
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = AuraGreen, modifier = Modifier.size(88.dp))
            Text(entry.name, modifier = Modifier.padding(16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    useController = true
                    keepScreenOn = true
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = if (audioOnly) {
                Modifier.fillMaxWidth().height(120.dp)
            } else {
                Modifier.weight(1f).fillMaxWidth()
            },
        )
        if (!audioOnly) {
    TextButton(
        onClick = {
            val position = player.currentPosition.coerceAtLeast(0L)
            player.pause()
            fullscreenLauncher.launch(
                Intent(context, FullscreenVideoActivity::class.java)
                    .putExtra(FullscreenVideoActivity.EXTRA_URI, entry.uri.toString())
                    .putExtra(FullscreenVideoActivity.EXTRA_POSITION, position)
            )
        }
    ) {
        Icon(Icons.Rounded.Fullscreen, contentDescription = null)
        Text("На весь экран")
    }
}
playbackError?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onOpenExternal) { Text("Открыть в…") }
                }
            }
        }
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
    // Do not use ContentResolver.loadThumbnail() here. Some OEM MediaProvider implementations
    // have been observed to decode a very large source bitmap before honouring the requested
    // thumbnail size, which can make a simple grid scroll attempt a 100-250+ MiB allocation.
    // Images are sampled from bounds first; videos use the platform scaled-frame API.
    return if (isImage(entry)) {
        decodeSampledImage(context, entry, edge, thumbnail = true)
    } else {
        loadVideoThumbnail(context, entry, edge)
    }
}

private fun loadVideoThumbnail(context: Context, entry: FileEntry, edge: Int): Bitmap? {
    // getScaledFrameAtTime() was added in API 27 and avoids materialising a full 4K/8K frame.
    // On older Android versions it is safer to show the normal video icon than to risk an OOM.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(context, entry.uri)
            retriever.getScaledFrameAtTime(
                -1L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                edge.coerceIn(64, MAX_THUMBNAIL_EDGE),
                edge.coerceIn(64, MAX_THUMBNAIL_EDGE),
            )?.let { ensureBitmapWithin(it, edge.coerceAtMost(MAX_THUMBNAIL_EDGE)) }
        } finally {
            retriever.release()
        }
    }
}

private fun loadImage(context: Context, entry: FileEntry): Bitmap {
    return decodeSampledImage(context, entry, MAX_IMAGE_EDGE, thumbnail = false)
        ?: throw IOException("Не удалось декодировать изображение")
}

private fun decodeSampledImage(
    context: Context,
    entry: FileEntry,
    maxEdge: Int,
    thumbnail: Boolean,
): Bitmap? {
    val requestedEdge = maxEdge.coerceIn(1, if (thumbnail) MAX_THUMBNAIL_EDGE else MAX_IMAGE_EDGE)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (entry.uri.scheme == "file") {
        BitmapFactory.decodeFile(entry.uri.path, bounds)
    } else {
        context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }

    // If bounds could not be decoded, never retry with inSampleSize=1. That would turn a corrupt,
    // exotic or OEM-problematic image into an unbounded full-resolution allocation.
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null
    val sourcePixels = width.toLong() * height.toLong()
    if (sourcePixels <= 0L || sourcePixels > MAX_SOURCE_PIXELS) return null

    var sample = 1
    while (
        width / sample > requestedEdge ||
        height / sample > requestedEdge ||
        (width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODED_PIXELS
    ) {
        if (sample >= MAX_SAMPLE_SIZE) return null
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (thumbnail) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    }
    val decoded = try {
        if (entry.uri.scheme == "file") {
            BitmapFactory.decodeFile(entry.uri.path, options)
        } else {
            context.contentResolver.openInputStream(entry.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (_: OutOfMemoryError) {
        null
    } ?: return null
    val oriented = applyExifOrientation(context, entry.uri, decoded)
    return ensureBitmapWithin(oriented, requestedEdge)
}

private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val exif = runCatching {
        if (uri.scheme == "file") {
            ExifInterface(requireNotNull(uri.path))
        } else {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Не удалось открыть EXIF")
            descriptor.use { ExifInterface(it.fileDescriptor) }
        }
    }.getOrNull() ?: return bitmap
    if (exif.rotationDegrees == 0 && !exif.isFlipped) return bitmap
    val matrix = Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        if (exif.rotationDegrees != 0) postRotate(exif.rotationDegrees.toFloat())
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { transformed ->
            if (transformed !== bitmap) bitmap.recycle()
        }
    } catch (_: OutOfMemoryError) {
        bitmap
    } catch (_: RuntimeException) {
        bitmap
    }
}

private fun ensureBitmapWithin(bitmap: Bitmap, edge: Int): Bitmap {
    val largest = max(bitmap.width, bitmap.height)
    if (largest <= edge || edge <= 0) return bitmap
    val scale = edge.toFloat() / largest.toFloat()
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

private fun renderPdfPage(context: Context, entry: FileEntry, requestedPage: Int): PdfPage {
    openDescriptor(context, entry.uri).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val pageIndex = requestedPage.coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(pageIndex).use { page ->
                val pageWidth = page.width.coerceAtLeast(1)
                val pageHeight = page.height.coerceAtLeast(1)
                var scale = (PDF_TARGET_WIDTH.toFloat() / pageWidth).coerceAtMost(PDF_MAX_SCALE)
                val estimatedPixels = pageWidth.toDouble() * pageHeight.toDouble() * scale * scale
                if (estimatedPixels > PDF_MAX_PIXELS) {
                    scale *= sqrt(PDF_MAX_PIXELS / estimatedPixels).toFloat()
                }
                val bitmap = Bitmap.createBitmap(
                    (pageWidth * scale).roundToInt().coerceAtLeast(1),
                    (pageHeight * scale).roundToInt().coerceAtLeast(1),
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

private fun isImage(entry: FileEntry) = isPreviewImage(entry.name, entry.mimeType)

private fun isVideo(entry: FileEntry) = isPreviewVideo(entry.name, entry.mimeType)
private fun isAudio(entry: FileEntry) = isPreviewAudio(entry.name, entry.mimeType)
private fun isPdf(entry: FileEntry) = entry.mimeType == "application/pdf" || entry.name.endsWith(".pdf", true)
private fun isText(entry: FileEntry) = entry.mimeType?.startsWith("text/") == true ||
    entry.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "xml", "csv", "log", "kt", "java", "c", "cpp", "h")

private data class PdfPage(val bitmap: Bitmap, val count: Int)
private const val MAX_IMAGE_EDGE = 2_048
private const val PDF_TARGET_WIDTH = 1_400
private const val PDF_MAX_SCALE = 2.5f
private const val PDF_MAX_PIXELS = 6_000_000.0
private const val MAX_THUMBNAIL_EDGE = 320
private const val MAX_SOURCE_DIMENSION = 65_535
private const val MAX_SOURCE_PIXELS = 400_000_000L
private const val MAX_DECODED_PIXELS = 4_194_304L // hard ceiling: 2048 x 2048
private const val MAX_SAMPLE_SIZE = 1 shl 15
private const val THUMBNAIL_CACHE_BYTES = 12 * 1024 * 1024
private val THUMBNAIL_DECODER = Semaphore(2)
private val THUMBNAIL_CACHE = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

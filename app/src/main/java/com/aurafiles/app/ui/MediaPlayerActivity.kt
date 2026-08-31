package com.aurafiles.app.ui

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.aurafiles.app.data.FastDocumentListing
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.aurafiles.app.model.FileEntry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MediaPlayerActivity : ComponentActivity() {
    private var player: Player? = null
    private var directPlayer: ExoPlayer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var playerView: PlayerView
    private lateinit var gestures: MediaGestureDetector
    private lateinit var titleView: TextView
    private lateinit var lockButton: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private var audioOnly = false
    private var locked = false
    private var immersiveFullscreen = false
    private lateinit var currentUri: Uri
    private var parentUri: Uri? = null
    private var currentName: String = ""
    private val positionStore by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        parentUri = intent.getStringExtra(EXTRA_PARENT)?.takeIf(String::isNotBlank)?.let(Uri::parse)
        currentName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        audioOnly = intent.getBooleanExtra(EXTRA_AUDIO, false)
        if (!audioOnly) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        if (audioOnly) connectAudioService() else createVideoPlayer()
    }

    override fun onStop() {
        savePosition()
        if (!audioOnly) {
            val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
            if (!inPip) player?.pause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        savePosition()
        playerView.player = null
        directPlayer?.release()
        directPlayer = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        player = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!audioOnly && player?.isPlaying == true) enterPip()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and the system permission UI can reveal the bars. Restore the
        // user's selected immersive mode when this window becomes active again.
        if (hasFocus && immersiveFullscreen) applyFullscreenState()
    }

    private fun buildUi() {
        if (!audioOnly) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        gestures = MediaGestureDetector(this) { action ->
            val p = player ?: return@MediaGestureDetector
            when (action) {
                GestureAction.SeekBack -> p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                GestureAction.SeekForward -> p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                is GestureAction.Brightness -> adjustBrightness(action.delta)
                is GestureAction.Volume -> adjustVolume(action.delta)
            }
        }
        // Observe gestures without swallowing PlayerView touches, so Media3 controls remain usable.
        playerView.setOnTouchListener { view, event ->
            if (!locked) gestures.onTouchEvent(event, view.width, view.height)
            false
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x66000000)
        }
        val close = iconButton(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть") { finish() }
        top.addView(close)
        titleView = TextView(this).apply {
            text = currentName
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!audioOnly) {
            fullscreenButton = iconButton(android.R.drawable.ic_menu_crop, "Во весь экран") { toggleFullscreen() }
            top.addView(fullscreenButton)
        }
        top.addView(iconButton(android.R.drawable.ic_menu_manage, "Скорость") { chooseSpeed() })
        top.addView(iconButton(android.R.drawable.ic_menu_sort_by_size, "Аудиодорожка") { chooseTrack(C.TRACK_TYPE_AUDIO) })
        top.addView(iconButton(android.R.drawable.ic_menu_info_details, "Субтитры") { chooseSubtitle() })
        if (!audioOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            top.addView(iconButton(android.R.drawable.ic_menu_slideshow, "Картинка в картинке") { enterPip() })
        }
        lockButton = iconButton(android.R.drawable.ic_lock_lock, "Заблокировать управление") { toggleLock() }
        top.addView(lockButton)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        applyEdgeToEdgeInsets(root, top)
        setContentView(root)
    }

    private fun applyEdgeToEdgeInsets(root: FrameLayout, topBar: LinearLayout) {
        val baseTopStart = dp(8)
        val baseTopTop = dp(8)
        val baseTopEnd = dp(8)
        val baseTopBottom = dp(8)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(
                baseTopStart + bars.left,
                baseTopTop + bars.top,
                baseTopEnd + bars.right,
                baseTopBottom,
            )
            playerView.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun createVideoPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        directPlayer = exo
        attachPlayer(exo)
    }

    private fun connectAudioService() {
        val token = SessionToken(this, ComponentName(this, AuraPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }
                .onSuccess(::attachPlayer)
                .onFailure { showError("Не удалось подключить MediaSession: ${it.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun attachPlayer(attached: Player) {
        player = attached
        playerView.player = attached
        val openedUri = currentUri
        val currentItem = mediaItem(openedUri, currentName)
        val saved = positionStore.getLong(positionKey(openedUri), 0L).coerceAtLeast(0L)
        // Start playback immediately. Building a sibling playlist may require listing a very
        // large SAF directory, so that work is deliberately moved off the UI thread.
        attached.setMediaItem(currentItem, saved)
        attached.prepare()
        attached.playWhenReady = true
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { neighboringMedia(parentUri, openedUri, currentName, audioOnly) }
            if (items.size <= 1 || player !== attached) return@launch
            if (attached.currentMediaItem?.mediaId != openedUri.toString()) return@launch
            val position = attached.currentPosition.coerceAtLeast(0L)
            val wasPlaying = attached.playWhenReady
            val index = items.indexOfFirst { it.mediaId == openedUri.toString() }.coerceAtLeast(0)
            attached.setMediaItems(items, index, position)
            attached.prepare()
            attached.playWhenReady = wasPlaying
        }
        attached.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.takeIf(String::isNotBlank)?.let { currentUri = Uri.parse(it) }
                titleView.text = mediaItem?.mediaMetadata?.title ?: currentName
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                showPlaybackError("Не удалось воспроизвести файл: ${error.errorCodeName}")
            }
        })
    }

    private fun neighboringMedia(parent: Uri?, current: Uri, name: String, audio: Boolean): List<MediaItem> {
        val extensions = if (audio) AUDIO_EXTENSIONS else VIDEO_EXTENSIONS
        val result = mutableListOf<Pair<Uri, String>>()
        val directory = parent?.let { FastDocumentListing.resolve(this, it) }
        if (directory != null) {
            FastDocumentListing.list(this, directory).asSequence()
                .filterNot { it.isDirectory }
                .filter { info ->
                    val ext = info.name.substringAfterLast('.', "").lowercase()
                    val mime = info.mimeType.orEmpty()
                    ext in extensions || if (audio) mime.startsWith("audio/") else mime.startsWith("video/")
                }
                .sortedBy { it.name.lowercase() }
                .forEach { result += it.uri to it.name }
        }
        if (result.none { it.first == current }) result += current to name
        return result.distinctBy { it.first.toString() }.map { (uri, title) -> mediaItem(uri, title) }
    }

    private fun mediaItem(uri: Uri, title: String): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

    private fun chooseSpeed() {
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = values.map { "${it}×" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Скорость воспроизведения").setItems(labels) { _, which ->
            player?.setPlaybackSpeed(values[which])
        }.show()
    }

    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val options = mutableListOf<TrackOption>()
        p.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val label = format.label?.takeIf(String::isNotBlank)
                    ?: format.language?.takeIf(String::isNotBlank)
                    ?: if (type == C.TRACK_TYPE_AUDIO) "Аудио ${options.size + 1}" else "Субтитры ${options.size + 1}"
                options += TrackOption(label, group.mediaTrackGroup, index)
            }
        }
        if (options.isEmpty()) {
            Toast.makeText(this, if (type == C.TRACK_TYPE_AUDIO) "Дополнительных аудиодорожек нет" else "Субтитры не найдены", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (type == C.TRACK_TYPE_AUDIO) "Аудиодорожка" else "Субтитры")
            .setItems(options.map(TrackOption::label).toTypedArray()) { _, which ->
                val option = options[which]
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(option.group, option.index))
                    .build()
            }.show()
    }

    private fun chooseSubtitle() {
        val p = player ?: return
        val choices = arrayOf("Выбрать дорожку", "Выключить субтитры")
        AlertDialog.Builder(this).setTitle("Субтитры").setItems(choices) { _, which ->
            if (which == 0) chooseTrack(C.TRACK_TYPE_TEXT)
            else p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }.show()
    }

    private fun toggleLock() {
        locked = !locked
        playerView.useController = !locked
        if (locked) playerView.hideController() else playerView.showController()
        lockButton.alpha = if (locked) 1f else 0.75f
        Toast.makeText(this, if (locked) "Управление заблокировано" else "Управление разблокировано", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFullscreen() {
        if (audioOnly) return
        immersiveFullscreen = !immersiveFullscreen
        applyFullscreenState()
    }

    private fun applyFullscreenState() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (immersiveFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (::fullscreenButton.isInitialized) {
            fullscreenButton.setImageResource(
                if (immersiveFullscreen) android.R.drawable.ic_menu_revert else android.R.drawable.ic_menu_crop
            )
            fullscreenButton.contentDescription = if (immersiveFullscreen) "Выйти из полноэкранного режима" else "Во весь экран"
        }
    }

    private fun adjustBrightness(delta: Float) {
        val params = window.attributes
        val current = if (params.screenBrightness < 0f) 0.5f else params.screenBrightness
        params.screenBrightness = (current + delta).coerceIn(0.02f, 1f)
        window.attributes = params
    }

    private fun adjustVolume(delta: Float) {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + delta * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audioOnly) return
        val size = directPlayer?.videoSize
        val width = size?.width?.takeIf { it > 0 } ?: 16
        val height = size?.height?.takeIf { it > 0 } ?: 9
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(width, height)).build())
    }

    private fun savePosition() {
        val p = player ?: return
        val uri = p.currentMediaItem?.mediaId?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: currentUri
        val pos = p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration
        val keep = if (duration > 0 && pos > duration - 10_000L) 0L else pos
        positionStore.edit().putLong(positionKey(uri), keep).apply()
    }

    private fun positionKey(uri: Uri): String = "position:${uri}"

    private fun iconButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
        alpha = 0.85f
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showPlaybackError(message: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Ошибка воспроизведения")
            .setMessage(message)
            .setPositiveButton("Открыть во внешнем плеере") { _, _ ->
                val shareUri = externallyReadableUri(currentUri)
                val externalIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(shareUri, if (audioOnly) "audio/*" else "video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                externalIntent.clipData = android.content.ClipData.newRawUri("Aura media", shareUri)
                runCatching { startActivity(externalIntent) }
                    .onFailure { showError("Подходящий внешний плеер не найден") }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }


    private fun externallyReadableUri(uri: Uri): Uri {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
        val path = requireNotNull(uri.path) { "Не удалось определить путь файла" }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class TrackOption(val label: String, val group: androidx.media3.common.TrackGroup, val index: Int)

    companion object {
        private const val EXTRA_URI = "media_uri"
        private const val EXTRA_PARENT = "media_parent"
        private const val EXTRA_NAME = "media_name"
        private const val EXTRA_AUDIO = "media_audio"
        private const val PREFS = "aura_media_positions"

        fun start(context: Context, entry: FileEntry, audioOnly: Boolean) {
            context.startActivity(
                Intent(context, MediaPlayerActivity::class.java)
                    .putExtra(EXTRA_URI, entry.uri.toString())
                    .putExtra(EXTRA_PARENT, entry.parentUri?.toString().orEmpty())
                    .putExtra(EXTRA_NAME, entry.name)
                    .putExtra(EXTRA_AUDIO, audioOnly)
            )
        }
    }
}

private sealed interface GestureAction {
    data object SeekBack : GestureAction
    data object SeekForward : GestureAction
    data class Brightness(val delta: Float) : GestureAction
    data class Volume(val delta: Float) : GestureAction
}

private class MediaGestureDetector(
    context: Context,
    private val action: (GestureAction) -> Unit,
) {
    private var downX = 0f
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            downX = e.x
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (e.x < viewportWidth / 2f) action(GestureAction.SeekBack) else action(GestureAction.SeekForward)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceY) < abs(distanceX)) return false
            // GestureDetector.distanceY is positive when the finger moves upward.
            // Up should increase brightness/volume, down should decrease it.
            val delta = (distanceY / viewportHeight.coerceAtLeast(1)).coerceIn(-0.08f, 0.08f)
            if (downX < viewportWidth / 2f) action(GestureAction.Brightness(delta)) else action(GestureAction.Volume(delta))
            return true
        }
    })

    fun onTouchEvent(event: MotionEvent, width: Int, height: Int): Boolean {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        return detector.onTouchEvent(event)
    }
}

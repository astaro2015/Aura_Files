package com.aurafiles.app.ui

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class AuraPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val positions by lazy { getSharedPreferences("aura_media_positions", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveCurrent()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) saveCurrent()
            }
        })
        mediaSession = MediaSession.Builder(this, exo).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        saveCurrent()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun saveCurrent() {
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration = exo.duration
        val resume = if (duration > 0L && position > duration - RESUME_END_THRESHOLD_MS) 0L else position
        savePosition(exo.currentMediaItem?.mediaId, resume)
    }

    private fun savePosition(mediaId: String?, position: Long) {
        if (mediaId.isNullOrBlank()) return
        positions.edit().putLong("position:$mediaId", position.coerceAtLeast(0L)).apply()
    }

    companion object {
        private const val RESUME_END_THRESHOLD_MS = 10_000L
    }
}

package com.musicflow.app

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*

/**
 * 媒体播放前台服务 — Android 16 核心适配
 * 解决锁屏断播、后台被杀、蓝牙线控失效
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 播放状态回调给 WebView
    var onPlaybackEvent: ((String, String?) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "musicflow_playback"
        const val CHANNEL_NAME = "音乐播放"
        const val NOTIFICATION_ID = 1001

        var instance: PlaybackService? = null
            private set

        // JS 统一调用入口
        fun play(url: String?, title: String?, artist: String?) {
            instance?.playMedia(url, title, artist)
        }

        fun pause() = instance?.player?.pause()
        fun resume() = instance?.player?.play()
        fun seekTo(ms: Long) = instance?.player?.seekTo(ms)
        fun getCurrentPosition(): Long = instance?.player?.currentPosition ?: 0
        fun getDuration(): Long = instance?.player?.duration ?: 0
        fun isPlaying(): Boolean = instance?.player?.isPlaying ?: false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        createPlayer()
        createMediaSession()
    }

    // ==================== ExoPlayer ====================

    private fun createPlayer() {
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        updateMediaSessionState()
                        when (state) {
                            Player.STATE_READY -> {
                                startForegroundNotification()
                                onPlaybackEvent?.invoke("ready", "${duration}ms")
                            }
                            Player.STATE_ENDED -> {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                onPlaybackEvent?.invoke("ended", null)
                            }
                            Player.STATE_BUFFERING -> {
                                onPlaybackEvent?.invoke("buffering", null)
                            }
                            Player.STATE_IDLE -> {
                                onPlaybackEvent?.invoke("idle", null)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateMediaSessionState()
                        onPlaybackEvent?.invoke(
                            if (isPlaying) "playing" else "paused",
                            "${currentPosition}"
                        )
                    }
                })
            }
    }

    // ==================== MediaSession ====================

    private fun createMediaSession() {
        val p = player ?: return

        mediaSession = MediaSession.Builder(this, p)
            .setCallback(object : MediaSession.Callback {
                override fun onPlay() = p.play()
                override fun onPause() = p.pause()
                override fun onSeekTo(positionMs: Long) = p.seekTo(positionMs)
                override fun onSkipToNext() = onPlaybackEvent?.invoke("skipNext", null)
                override fun onSkipToPrevious() = onPlaybackEvent?.invoke("skipPrev", null)
                override fun onStop() {
                    p.stop()
                    stopSelf()
                }
            })
            .build()

        setMediaNotificationProvider { _, _ -> buildNotification() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun updateMediaSessionState() {
        val p = player ?: return
        mediaSession?.let { session ->
            session.player.let { sp ->
                // Sync metadata
                if (p.mediaItemCount > 0) {
                    val meta = p.mediaMetadata
                    sp.setMediaMetadata(sp.mediaMetadata.buildUpon()
                        .setTitle(meta.title)
                        .setArtist(meta.artist)
                        .build())
                }
            }
        }
    }

    // ==================== 播放控制 ====================

    fun playMedia(url: String?, title: String?, artist: String?) {
        if (url.isNullOrEmpty()) return

        val p = player ?: return
        val meta = MediaMetadata.Builder()
            .setTitle(title ?: "未知歌曲")
            .setArtist(artist ?: "未知艺术家")
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(meta)
            .build()

        p.setMediaItem(mediaItem)
        p.prepare()
        p.play()

        // 更新 MediaSession 元数据
        mediaSession?.let { session ->
            session.player.setMediaMetadata(meta)
            session.player.playWhenReady = true
        }
    }

    // ==================== 前台通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): androidx.media3.session.MediaNotification {
        val p = player ?: return androidx.media3.session.MediaNotification(
            NOTIFICATION_ID, android.app.Notification()
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = p.mediaMetadata.title?.toString() ?: "MusicFlow"
        val artist = p.mediaMetadata.artist?.toString() ?: ""

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(
                        (mediaSession?.sessionCompat as? MediaSessionCompat)?.sessionToken
                    )
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                android.R.drawable.ic_media_previous, "上一首",
                null // handled by MediaSession
            )
            .addAction(
                if (p.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (p.isPlaying) "暂停" else "播放",
                null
            )
            .addAction(
                android.R.drawable.ic_media_next, "下一首",
                null
            )
            .build()

        return androidx.media3.session.MediaNotification(NOTIFICATION_ID, notification)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification().notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification().notification)
        }
    }

    // ==================== 生命周期 ====================

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 保留后台播放，不销毁服务
        if (player?.isPlaying == true) {
            // 继续播放
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        instance = null
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
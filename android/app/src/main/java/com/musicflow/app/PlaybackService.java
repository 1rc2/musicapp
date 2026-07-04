package com.musicflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 媒体播放前台服务 — targetSdk 36 后台保活核心
 * 使用平台 API（无第三方依赖），解决锁屏断播、后台被杀
 */
public class PlaybackService extends Service
        implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        MediaPlayer.OnPreparedListener, AudioManager.OnAudioFocusChangeListener {

    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isForeground = false;

    private String currentTitle = "MusicFlow";
    private String currentArtist = "";

    public static PlaybackService instance;

    private static final String CHANNEL_ID = "musicflow_playback";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("play".equals(action)) {
                playMedia(intent.getStringExtra("url"),
                        intent.getStringExtra("title"),
                        intent.getStringExtra("artist"));
            } else if ("pause".equals(action)) {
                pause();
            } else if ("resume".equals(action)) {
                resume();
            } else if ("stop".equals(action)) {
                stop();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============== 播放控制 ==============

    public void playMedia(String url, String title, String artist) {
        if (url == null || url.isEmpty()) return;
        currentTitle = title != null ? title : "未知歌曲";
        currentArtist = artist != null ? artist : "未知艺术家";
        releasePlayer();

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        mediaPlayer.setOnPreparedListener(this);

        try {
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
            updatePlaybackState(PlaybackState.STATE_BUFFERING);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        requestAudioFocus();
        mp.start();
        updatePlaybackState(PlaybackState.STATE_PLAYING);
        startForegroundNotification();
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            abandonAudioFocus();
            stopForegroundNotification();
        }
    }

    public void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            requestAudioFocus();
            mediaPlayer.start();
            updatePlaybackState(PlaybackState.STATE_PLAYING);
            startForegroundNotification();
        }
    }

    public void stop() {
        releasePlayer();
        abandonAudioFocus();
        stopForegroundNotification();
        stopSelf();
    }

    public void seekTo(long ms) {
        if (mediaPlayer != null) mediaPlayer.seekTo((int) ms);
    }

    public long getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    // ============== 音频焦点 ==============

    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener(this, handler)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                stop();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null) mediaPlayer.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(1.0f, 1.0f);
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                        updatePlaybackState(PlaybackState.STATE_PLAYING);
                        startForegroundNotification();
                    }
                }
                break;
        }
    }

    // ============== MediaSession ==============

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "MusicFlow");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() { resume(); }
            @Override
            public void onPause() { pause(); }
            @Override
            public void onSeekTo(long pos) { seekTo(pos); }
            @Override
            public void onStop() { stop(); }
        });
        mediaSession.setActive(true);
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setState(state, getCurrentPosition(), 1.0f)
                .setActions(PlaybackState.ACTION_PLAY |
                        PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_SEEK_TO |
                        PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        mediaSession.setPlaybackState(builder.build());
    }

    // ============== 通知 ==============

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundNotification() {
        if (isForeground) return;
        isForeground = true;

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        Notification notif = nb.setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(android.R.drawable.ic_media_previous, "上一首", null)
                .addAction(android.R.drawable.ic_media_pause, "暂停", null)
                .addAction(android.R.drawable.ic_media_next, "下一首", null)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notif);
        }
    }

    private void stopForegroundNotification() {
        if (!isForeground) return;
        isForeground = false;
        stopForeground(true);
    }

    // ============== 播放器回调 ==============

    @Override
    public void onCompletion(MediaPlayer mp) {
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        abandonAudioFocus();
        stopForegroundNotification();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        releasePlayer();
        abandonAudioFocus();
        stopForegroundNotification();
        stopSelf();
        return true;
    }

    // ============== 生命周期 ==============

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        releasePlayer();
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
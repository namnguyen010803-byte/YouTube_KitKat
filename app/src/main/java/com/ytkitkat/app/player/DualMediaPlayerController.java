package com.ytkitkat.app.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceHolder;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.model.PlaybackSource;
import com.ytkitkat.app.network.HttpClient;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Bộ điều khiển MediaPlayer kép tối ưu cho Android 4.4.4 (KitKat - API 19):
 *
 * 1. Chế độ HLS (Ưu tiên - {@link PlaybackSource#MODE_HLS}) hoặc Progressive MP4 ({@link PlaybackSource#MODE_PROGRESSIVE}):
 *    - Nếu phát qua link {@code .m3u8} con của client {@code IOS} (đã mux sẵn cả Video H.264 và Audio AAC)
 *      hoặc MP4 {@code itag 18}, chỉ khởi tạo duy nhất {@code videoPlayer} để giải phóng tài nguyên RAM/CPU.
 *
 * 2. Chế độ Adaptive Dual ({@link PlaybackSource#MODE_DUAL_ADAPTIVE}):
 *    - Khi phát luồng video rời ({@code itag 299/298/137/136/135/134/160}) từ {@code ANDROID_VR}
 *      cùng luồng âm thanh rời ({@code itag 140} M4A Original Audio), khởi chạy cả {@code videoPlayer}
 *      (gắn với {@link SurfaceHolder}) và {@code audioPlayer}.
 *    - Đợi cả 2 player {@code onPrepared} rồi mới đồng thời {@code start()}.
 *    - Gắn bộ định thời {@link Handler} kiểm tra mỗi {@link Config#AV_SYNC_CHECK_INTERVAL_MS} (1000ms):
 *      nếu {@code Math.abs(videoPos - audioPos) > 150ms} ({@link Config#AV_SYNC_TOLERANCE_MS}),
 *      tự động căn chỉnh bằng cách {@code seekTo()} luồng chạy chậm về vị trí luồng chạy nhanh.
 *
 * 3. Chế độ Audio-Only ({@link PlaybackSource#MODE_AUDIO_ONLY}):
 *    - Giải phóng hoàn toàn {@code videoPlayer} và bề mặt hiển thị, chỉ duy trì {@code audioPlayer}
 *      với {@code PARTIAL_WAKE_LOCK} để phát nền tiết kiệm pin tối đa.
 */
public final class DualMediaPlayerController {

    private static final String TAG = "DualPlayerController";

    public interface PlayerEventListener {
        void onPrepared(int durationMs, int videoWidth, int videoHeight);

        void onPlaybackStateChanged(boolean isPlaying);

        void onBufferingStateChanged(boolean isBuffering);

        void onProgressUpdate(int currentPositionMs, int durationMs, int bufferedPercent);

        void onVideoSizeChanged(int width, int height);

        void onCompletion();

        void onError(String message);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PlayerEventListener listener;

    private MediaPlayer videoPlayer;
    private MediaPlayer audioPlayer;
    private SurfaceHolder activeSurfaceHolder;

    private PlaybackSource currentSource;
    private boolean videoPrepared;
    private boolean audioPrepared;
    private boolean playWhenReady = true;
    private boolean preparing;
    private boolean videoBuffering;
    private boolean audioBuffering;
    private int pendingSeekMs = 0;
    private int lastBufferedPercent = 0;
    private float currentPlaybackSpeed = 1.0f;
    private long lastSyncSeekTimeMs = 0L;

    /**
     * Runnable chạy mỗi 1000ms (Config.AV_SYNC_CHECK_INTERVAL_MS) để:
     * 1. Cập nhật tiến trình (SeekBar + nhãn thời gian).
     * 2. Kiểm tra độ lệch hình/tiếng khi chạy chế độ Dual Adaptive:
     *    Nếu Math.abs(videoPos - audioPos) > 150ms -> seekTo() luồng chạy chậm về vị trí luồng chạy nhanh.
     */
    private final Runnable syncAndProgressLoop = new Runnable() {
        @Override
        public void run() {
            try {
                performTickAndAvSync();
            } catch (Exception e) {
                Log.w(TAG, "Lỗi trong chu kỳ đồng bộ AV: " + e.getMessage());
            }
            if (isReady() && isPlaying()) {
                mainHandler.postDelayed(this, Config.AV_SYNC_CHECK_INTERVAL_MS);
            }
        }
    };

    public DualMediaPlayerController(Context context, PlayerEventListener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void setListener(PlayerEventListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Quản lý SurfaceHolder (cho phép gắn/đổi giữa Full Player & Mini-player)
    // ------------------------------------------------------------------

    public void attachSurfaceHolder(SurfaceHolder holder) {
        this.activeSurfaceHolder = holder;
        if (videoPlayer != null) {
            try {
                videoPlayer.setDisplay(holder);
                if (holder != null) {
                    holder.setKeepScreenOn(true);
                }
            } catch (Exception e) {
                Log.w(TAG, "Không gắn được SurfaceHolder vào videoPlayer: " + e.getMessage());
            }
        }
    }

    public void detachSurfaceHolder(SurfaceHolder holder) {
        if (this.activeSurfaceHolder == holder) {
            this.activeSurfaceHolder = null;
            if (videoPlayer != null) {
                try {
                    videoPlayer.setDisplay(null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Khởi chạy / Chuyển đổi nguồn phát (1080p60 / 720p60 / 360p / Audio-Only)
    // ------------------------------------------------------------------

    /**
     * Nạp nguồn phát mới (HLS .m3u8 con, Dual Adaptive H.264+AAC, Progressive MP4, hoặc Audio-Only)
     * và tự động nhảy tới vị trí {@code startPositionMs} (dùng khi đổi độ phân giải giữa chừng).
     */
    public void playSource(PlaybackSource source, int startPositionMs, boolean autoStart) {
        if (source == null || source.getPrimaryUrl() == null) {
            notifyError("Nguồn phát không hợp lệ");
            return;
        }
        stopSyncLoop();
        releasePlayersOnly();

        this.currentSource = source;
        this.pendingSeekMs = Math.max(0, startPositionMs);
        this.playWhenReady = autoStart;
        this.preparing = true;
        this.videoPrepared = false;
        this.audioPrepared = false;
        this.videoBuffering = false;
        this.audioBuffering = false;
        this.lastBufferedPercent = 0;

        notifyBuffering(true);

        int mode = source.getMode();
        if (mode == PlaybackSource.MODE_AUDIO_ONLY) {
            // Chế độ Audio-Only: giải phóng bề mặt hiển thị của videoPlayer, chỉ chạy audioPlayer
            if (activeSurfaceHolder != null) {
                try {
                    activeSurfaceHolder.setKeepScreenOn(false);
                } catch (Exception ignored) {
                }
            }
            videoPrepared = true;
            setupAndPrepareAudioPlayer(source.getPrimaryUrl(), source.getPrimaryUserAgent());
        } else if (mode == PlaybackSource.MODE_DUAL_ADAPTIVE) {
            // Chế độ Adaptive Dual (BravePipe): chạy cả videoPlayer (H.264) và audioPlayer (AAC itag 140)
            setupAndPrepareVideoPlayer(source.getPrimaryUrl(), source.getPrimaryUserAgent(), true);
            setupAndPrepareAudioPlayer(source.getSecondaryAudioUrl(), source.getSecondaryAudioUserAgent());
        } else {
            // Chế độ HLS (.m3u8 con đã mux H.264+AAC) hoặc Progressive MP4 (itag 18):
            // Chỉ dùng duy nhất videoPlayer để giải phóng tài nguyên RAM/CPU.
            audioPrepared = true;
            setupAndPrepareVideoPlayer(source.getPrimaryUrl(), source.getPrimaryUserAgent(), false);
        }
    }

    private static Map<String, String> buildStreamHeaders(String clientUserAgent) {
        Map<String, String> headers = new HashMap<String, String>();
        String ua = (clientUserAgent != null && clientUserAgent.length() > 0)
                ? clientUserAgent
                : HttpClient.DEFAULT_USER_AGENT;
        headers.put("User-Agent", ua);
        return headers;
    }

    private void setupAndPrepareVideoPlayer(String videoUrl, String clientUserAgent, boolean muteVideoTrack) {
        try {
            videoPlayer = new MediaPlayer();
            videoPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            videoPlayer.setScreenOnWhilePlaying(true);
            if (muteVideoTrack) {
                videoPlayer.setVolume(0f, 0f);
            } else {
                videoPlayer.setVolume(1f, 1f);
            }
            if (activeSurfaceHolder != null) {
                videoPlayer.setDisplay(activeSurfaceHolder);
            }

            videoPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    videoPrepared = true;
                    onSinglePlayerPrepared();
                }
            });

            videoPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    if (listener != null && width > 0 && height > 0) {
                        listener.onVideoSizeChanged(width, height);
                    }
                }
            });

            videoPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override
                public void onBufferingUpdate(MediaPlayer mp, int percent) {
                    lastBufferedPercent = percent;
                }
            });

            videoPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        videoBuffering = true;
                        // Nếu ở chế độ Dual, tạm dừng audioPlayer khi video đang nạp đệm để không trôi tiếng
                        if (isDualMode() && audioPlayer != null && audioPrepared && audioPlayer.isPlaying()) {
                            try {
                                audioPlayer.pause();
                            } catch (Exception ignored) {
                            }
                        }
                        notifyBuffering(true);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        videoBuffering = false;
                        if (isDualMode() && playWhenReady && audioPlayer != null && audioPrepared) {
                            try {
                                int vPos = videoPlayer.getCurrentPosition();
                                audioPlayer.seekTo(vPos);
                                audioPlayer.start();
                            } catch (Exception ignored) {
                            }
                        }
                        notifyBuffering(audioBuffering);
                    }
                    return false;
                }
            });

            videoPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    handleCompletion();
                }
            });

            videoPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "videoPlayer lỗi: what=" + what + ", extra=" + extra);
                    notifyError("Lỗi giải mã luồng hình (mã " + what + "/" + extra + ")");
                    return true;
                }
            });

            videoPlayer.setDataSource(appContext, Uri.parse(videoUrl), buildStreamHeaders(clientUserAgent));
            videoPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Không khởi tạo được videoPlayer", e);
            notifyError("Không mở được luồng video: " + e.getMessage());
        }
    }

    private void setupAndPrepareAudioPlayer(String audioUrl, String clientUserAgent) {
        try {
            audioPlayer = new MediaPlayer();
            audioPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            try {
                audioPlayer.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
            } catch (Exception ignored) {
            }

            audioPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    audioPrepared = true;
                    onSinglePlayerPrepared();
                }
            });

            audioPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override
                public void onBufferingUpdate(MediaPlayer mp, int percent) {
                    if (videoPlayer == null) {
                        lastBufferedPercent = percent;
                    }
                }
            });

            audioPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        audioBuffering = true;
                        notifyBuffering(true);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        audioBuffering = false;
                        notifyBuffering(videoBuffering);
                    }
                    return false;
                }
            });

            audioPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (videoPlayer == null) {
                        handleCompletion();
                    }
                }
            });

            audioPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "audioPlayer lỗi: what=" + what + ", extra=" + extra);
                    notifyError("Lỗi giải mã luồng âm thanh (mã " + what + "/" + extra + ")");
                    return true;
                }
            });

            audioPlayer.setDataSource(appContext, Uri.parse(audioUrl), buildStreamHeaders(clientUserAgent));
            audioPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Không khởi tạo được audioPlayer", e);
            notifyError("Không mở được luồng âm thanh: " + e.getMessage());
        }
    }

    private void onSinglePlayerPrepared() {
        if (!videoPrepared || !audioPrepared) {
            return;
        }
        preparing = false;
        notifyBuffering(false);

        if (pendingSeekMs > 0) {
            seekInternal(pendingSeekMs);
            pendingSeekMs = 0;
        }

        if (currentPlaybackSpeed != 1.0f) {
            applyPlaybackSpeedInternal(currentPlaybackSpeed);
        }

        int duration = getDuration();
        int vw = videoPlayer != null ? videoPlayer.getVideoWidth() : 0;
        int vh = videoPlayer != null ? videoPlayer.getVideoHeight() : 0;
        if (listener != null) {
            listener.onPrepared(duration, vw, vh);
        }

        if (playWhenReady) {
            start();
        } else if (listener != null) {
            listener.onPlaybackStateChanged(false);
        }
    }

    // ------------------------------------------------------------------
    // Điều khiển Phát / Tạm dừng / Tua / Đồng bộ 150ms
    // ------------------------------------------------------------------

    public void start() {
        playWhenReady = true;
        if (!isReady()) {
            return;
        }
        try {
            if (isDualMode() && videoPlayer != null && audioPlayer != null) {
                int vPos = videoPlayer.getCurrentPosition();
                int aPos = audioPlayer.getCurrentPosition();
                if (Math.abs(vPos - aPos) > Config.AV_SYNC_TOLERANCE_MS) {
                    audioPlayer.seekTo(vPos);
                }
                audioPlayer.start();
                videoPlayer.start();
            } else if (videoPlayer != null) {
                videoPlayer.start();
            } else if (audioPlayer != null) {
                audioPlayer.start();
            }
            if (listener != null) {
                listener.onPlaybackStateChanged(true);
            }
            startSyncLoop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Không thể start MediaPlayer: " + e.getMessage());
        }
    }

    public void pause() {
        playWhenReady = false;
        stopSyncLoop();
        try {
            if (videoPlayer != null && videoPrepared && videoPlayer.isPlaying()) {
                videoPlayer.pause();
            }
        } catch (IllegalStateException ignored) {
        }
        try {
            if (audioPlayer != null && audioPrepared && audioPlayer.isPlaying()) {
                audioPlayer.pause();
            }
        } catch (IllegalStateException ignored) {
        }
        if (listener != null) {
            listener.onPlaybackStateChanged(false);
        }
    }

    public void togglePlayPause() {
        if (isPlaying()) {
            pause();
        } else {
            start();
        }
    }

    public void seekTo(int positionMs) {
        int clamped = Math.max(0, positionMs);
        if (!isReady()) {
            pendingSeekMs = clamped;
            return;
        }
        seekInternal(clamped);
    }

    private void seekInternal(int positionMs) {
        try {
            if (videoPlayer != null && videoPrepared) {
                videoPlayer.seekTo(positionMs);
            }
            if (audioPlayer != null && audioPrepared) {
                audioPlayer.seekTo(positionMs);
            }
            lastSyncSeekTimeMs = System.currentTimeMillis();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Không thể seekTo: " + e.getMessage());
        }
    }

    /**
     * Thực thi chu kỳ 1000ms:
     * - Kiểm tra độ lệch giữa videoPlayer và audioPlayer ở chế độ Dual Adaptive:
     *   Nếu Math.abs(videoPos - audioPos) > 150ms, thực hiện bù lệch bằng cách seekTo()
     *   luồng chạy chậm về vị trí của luồng chạy nhanh.
     * - Gửi cập nhật tiến trình về giao diện Player.
     */
    private void performTickAndAvSync() {
        if (!isReady()) {
            return;
        }
        if (isDualMode()
                && videoPlayer != null
                && audioPlayer != null
                && !videoBuffering
                && !audioBuffering
                && videoPlayer.isPlaying()
                && audioPlayer.isPlaying()) {

            long now = System.currentTimeMillis();
            // Tránh gọi seekTo liên hoàn khi bộ giải mã vừa mới seek xong chưa kịp ổn định keyframe
            if (now - lastSyncSeekTimeMs >= 1500L) {
                int videoPos = videoPlayer.getCurrentPosition();
                int audioPos = audioPlayer.getCurrentPosition();
                int drift = Math.abs(videoPos - audioPos);

                if (drift > Config.AV_SYNC_TOLERANCE_MS) {
                    int targetFastPos = Math.max(videoPos, audioPos);
                    if (videoPos < audioPos) {
                        if (Config.DEBUG_LOG) {
                            Log.d(TAG, "AV Sync: Video chậm hơn Audio " + drift
                                    + "ms -> seekTo videoPlayer về " + targetFastPos + "ms");
                        }
                        videoPlayer.seekTo(targetFastPos);
                    } else {
                        if (Config.DEBUG_LOG) {
                            Log.d(TAG, "AV Sync: Audio chậm hơn Video " + drift
                                    + "ms -> seekTo audioPlayer về " + targetFastPos + "ms");
                        }
                        audioPlayer.seekTo(targetFastPos);
                    }
                    lastSyncSeekTimeMs = now;
                }
            }
        }

        if (listener != null) {
            int pos = getCurrentPosition();
            int dur = getDuration();
            listener.onProgressUpdate(pos, dur, lastBufferedPercent);
        }
    }

    /**
     * Đặt tốc độ phát (1.0x, 1.25x, 1.5x, 2.0x).
     * Trên API 23+ dùng PlaybackParams; trên API 19..22 giữ ở 1.0x an toàn.
     */
    public boolean setPlaybackSpeed(float speed) {
        this.currentPlaybackSpeed = speed;
        if (!isReady()) {
            return Build.VERSION.SDK_INT >= 23;
        }
        return applyPlaybackSpeedInternal(speed);
    }

    public float getPlaybackSpeed() {
        return currentPlaybackSpeed;
    }

    private boolean applyPlaybackSpeedInternal(float speed) {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        try {
            Class<?> paramsClass = Class.forName("android.media.PlaybackParams");
            Object params = paramsClass.newInstance();
            Method setSpeed = paramsClass.getMethod("setSpeed", float.class);
            setSpeed.invoke(params, speed);
            Method setPlaybackParams = MediaPlayer.class.getMethod("setPlaybackParams", paramsClass);
            if (videoPlayer != null && videoPrepared) {
                setPlaybackParams.invoke(videoPlayer, params);
            }
            if (audioPlayer != null && audioPrepared) {
                setPlaybackParams.invoke(audioPlayer, params);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Thiết bị không hỗ trợ đổi tốc độ phát: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Trạng thái & Giải phóng bộ nhớ
    // ------------------------------------------------------------------

    public boolean isReady() {
        return !preparing && videoPrepared && audioPrepared && currentSource != null;
    }

    public boolean isDualMode() {
        return currentSource != null && currentSource.isDualPlayerRequired();
    }

    public boolean isAudioOnlyMode() {
        return currentSource != null && currentSource.isAudioOnly();
    }

    public boolean isPlaying() {
        if (!isReady()) {
            return false;
        }
        try {
            if (videoPlayer != null) {
                return videoPlayer.isPlaying();
            }
            if (audioPlayer != null) {
                return audioPlayer.isPlaying();
            }
        } catch (IllegalStateException ignored) {
        }
        return false;
    }

    public int getCurrentPosition() {
        if (!isReady()) {
            return pendingSeekMs;
        }
        try {
            if (audioPlayer != null && audioPrepared && (videoPlayer == null || isDualMode())) {
                return audioPlayer.getCurrentPosition();
            }
            if (videoPlayer != null && videoPrepared) {
                return videoPlayer.getCurrentPosition();
            }
        } catch (IllegalStateException ignored) {
        }
        return 0;
    }

    public int getDuration() {
        if (!isReady()) {
            return 0;
        }
        try {
            if (videoPlayer != null && videoPrepared) {
                int d = videoPlayer.getDuration();
                if (d > 0) {
                    return d;
                }
            }
            if (audioPlayer != null && audioPrepared) {
                int d = audioPlayer.getDuration();
                if (d > 0) {
                    return d;
                }
            }
        } catch (IllegalStateException ignored) {
        }
        return 0;
    }

    public PlaybackSource getCurrentSource() {
        return currentSource;
    }

    private void handleCompletion() {
        stopSyncLoop();
        playWhenReady = false;
        if (audioPlayer != null && audioPrepared) {
            try {
                if (audioPlayer.isPlaying()) {
                    audioPlayer.pause();
                }
                audioPlayer.seekTo(0);
            } catch (Exception ignored) {
            }
        }
        if (videoPlayer != null && videoPrepared) {
            try {
                videoPlayer.seekTo(0);
            } catch (Exception ignored) {
            }
        }
        if (listener != null) {
            listener.onPlaybackStateChanged(false);
            listener.onCompletion();
        }
    }

    private void notifyBuffering(boolean buffering) {
        if (listener != null) {
            listener.onBufferingStateChanged(buffering);
        }
    }

    private void notifyError(String message) {
        stopSyncLoop();
        preparing = false;
        if (listener != null) {
            listener.onBufferingStateChanged(false);
            listener.onError(message);
        }
    }

    private void startSyncLoop() {
        mainHandler.removeCallbacks(syncAndProgressLoop);
        mainHandler.postDelayed(syncAndProgressLoop, Config.AV_SYNC_CHECK_INTERVAL_MS);
    }

    private void stopSyncLoop() {
        mainHandler.removeCallbacks(syncAndProgressLoop);
    }

    private void releasePlayersOnly() {
        if (videoPlayer != null) {
            try {
                videoPlayer.setDisplay(null);
            } catch (Exception ignored) {
            }
            try {
                videoPlayer.reset();
            } catch (Exception ignored) {
            }
            try {
                videoPlayer.release();
            } catch (Exception ignored) {
            }
            videoPlayer = null;
        }
        if (audioPlayer != null) {
            try {
                audioPlayer.reset();
            } catch (Exception ignored) {
            }
            try {
                audioPlayer.release();
            } catch (Exception ignored) {
            }
            audioPlayer = null;
        }
        videoPrepared = false;
        audioPrepared = false;
        preparing = false;
    }

    /** Giải phóng hoàn toàn cả 2 MediaPlayer (gọi khi đóng Mini-player hoặc Activity.onDestroy()). */
    public void release() {
        stopSyncLoop();
        currentSource = null;
        releasePlayersOnly();
    }
}

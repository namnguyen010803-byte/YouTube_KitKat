package com.ytkitkat.app.player;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.R;
import com.ytkitkat.app.adapter.VideoAdapter;
import com.ytkitkat.app.model.PagedResult;
import com.ytkitkat.app.model.PlaybackSource;
import com.ytkitkat.app.model.StreamInfo;
import com.ytkitkat.app.model.VideoItem;
import com.ytkitkat.app.network.ApiCallback;
import com.ytkitkat.app.network.RequestHandle;
import com.ytkitkat.app.network.StreamResolver;
import com.ytkitkat.app.network.YouTubeApi;
import com.ytkitkat.app.ui.AspectRatio16x9FrameLayout;
import com.ytkitkat.app.util.ImageLoader;
import com.ytkitkat.app.util.TextFormat;
import com.ytkitkat.app.util.WatchHistoryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ quản lý toàn diện giao diện Trình phát (Full Player) & Mini-player cho Android 4.4.4:
 *
 * 1. Chế độ Toàn màn hình ({@link #STATE_FULL_EXPANDED}):
 *    - Phía trên: Khung {@link SurfaceView} 16:9 kèm lớp phủ điều khiển bán trong suốt
 *      (Nút thu nhỏ {@code v}, Nút tốc độ {@code 1x}, Nút chọn chất lượng {@link PopupWindow}
 *      hiển thị {@code MPEG-4 1080p60}, {@code MPEG-4 720p60}, {@code MPEG-4 720p}, {@code MPEG-4 480p},
 *      {@code MPEG-4 360p}, {@code MPEG-4 144p}, {@code M4A Audio Only}, Nút Play/Pause lớn,
 *      {@link SeekBar} đỏ {@code #FF0033} và nhãn thời gian).
 *    - Phía dưới: Danh sách cuộn chứa Header chi tiết video (Tiêu đề, Lượt xem, Kênh, Thích, Chia sẻ,
 *      Chuyển nhanh Audio-Only, Mô tả thu gọn/mở rộng) và danh sách Video đề xuất tiếp theo.
 *
 * 2. Chế độ Mini-player ({@link #STATE_MINI_PLAYER}):
 *    - Thanh nổi cao 62dp neo ngay trên Bottom Navigation Bar ({@code #272727}).
 *    - Bên trái: Khung {@link SurfaceView} nhỏ 106x60dp tiếp tục phát hình mượt mà không bị ngắt.
 *    - Ở giữa: Tiêu đề chạy chữ (marquee) + Tên kênh & chất lượng đang phát.
 *    - Bên phải: Nút Play/Pause nhanh và nút Đóng {@code X}.
 *    - Chạm vào thanh Mini-player sẽ phóng to lại màn hình Player chính mà không làm gián đoạn luồng phát.
 */
public final class PlayerOverlayManager implements DualMediaPlayerController.PlayerEventListener {

    public static final int STATE_HIDDEN = 0;
    public static final int STATE_FULL_EXPANDED = 1;
    public static final int STATE_MINI_PLAYER = 2;

    private static final long CONTROLS_AUTO_HIDE_DELAY_MS = 4000L;

    public interface StateChangeListener {
        void onPlayerStateChanged(int newState);
    }

    private final Activity activity;
    private final FrameLayout containerView;
    private final StateChangeListener stateChangeListener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final DualMediaPlayerController playerController;

    // Thành phần giao diện gốc
    private View overlayRoot;
    private View fullPlayerPanel;
    private View miniPlayerBar;

    // Thành phần Full Player 16:9
    private AspectRatio16x9FrameLayout fullVideoStage;
    private SurfaceView surfaceFullVideo;
    private View audioOnlyBanner;
    private ProgressBar progressBuffering;
    private View playerControlsOverlay;
    private ImageButton btnMinimizePlayer;
    private TextView txtOverlayCompactTitle;
    private TextView btnPlaybackSpeed;
    private TextView btnQualitySelector;
    private ImageButton btnCenterPlayPause;
    private TextView txtCurrentTime;
    private TextView txtTotalDuration;
    private SeekBar seekPlayerProgress;

    // Thành phần Header chi tiết & Danh sách video đề xuất
    private ListView listRelatedVideos;
    private View detailsHeaderView;
    private TextView txtPlayerFullTitle;
    private TextView txtPlayerFullStats;
    private ImageView imgPlayerChannelAvatar;
    private TextView txtPlayerChannelName;
    private TextView txtPlayerChannelSubs;
    private Button btnPlayerSubscribe;
    private View btnActionLike;
    private TextView txtActionLikeCount;
    private View btnActionShare;
    private View btnActionAudioOnly;
    private TextView txtActionAudioOnly;
    private View btnActionDownload;
    private View cardPlayerDescription;
    private TextView txtPlayerStreamTech;
    private TextView txtPlayerDescription;
    private VideoAdapter relatedVideoAdapter;

    // Thành phần Mini-player
    private SurfaceView surfaceMiniVideo;
    private ImageView imgMiniAudioBadge;
    private View miniPlayerInfoArea;
    private TextView txtMiniTitle;
    private TextView txtMiniChannel;
    private ImageButton btnMiniPlayPause;
    private ImageButton btnMiniClose;
    private ProgressBar progressMiniBar;

    // Trạng thái SurfaceHolder
    private SurfaceHolder fullSurfaceHolder;
    private SurfaceHolder miniSurfaceHolder;

    // Trạng thái phát hiện tại
    private int currentState = STATE_HIDDEN;
    private VideoItem currentVideo;
    private StreamInfo currentStreamInfo;
    private PlaybackSource currentPlaybackSource;
    private String preferredQuality = Config.DEFAULT_QUALITY;
    private boolean preferAdaptiveForHd = Config.PREFER_ADAPTIVE_OVER_HLS_FOR_HD;
    private boolean userDraggingSeekBar = false;
    private boolean descriptionExpanded = false;
    private boolean isLiked = false;
    private boolean isSubscribed = false;

    private RequestHandle resolveRequestHandle;
    private RequestHandle relatedRequestHandle;
    private PopupWindow activePopupWindow;

    private final Runnable autoHideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == STATE_FULL_EXPANDED
                    && playerController.isPlaying()
                    && !userDraggingSeekBar
                    && (activePopupWindow == null || !activePopupWindow.isShowing())) {
                playerControlsOverlay.setVisibility(View.GONE);
            }
        }
    };

    public PlayerOverlayManager(Activity activity,
                                FrameLayout containerView,
                                StateChangeListener stateChangeListener) {
        this.activity = activity;
        this.containerView = containerView;
        this.stateChangeListener = stateChangeListener;
        this.playerController = new DualMediaPlayerController(activity, this);

        inflateAndBindViews();
        setupSurfaceCallbacks();
        setupControlListeners();
    }

    // ------------------------------------------------------------------
    // Khởi tạo View & Sự kiện
    // ------------------------------------------------------------------

    private void inflateAndBindViews() {
        LayoutInflater inflater = LayoutInflater.from(activity);
        overlayRoot = inflater.inflate(R.layout.layout_player_overlay, containerView, false);
        containerView.removeAllViews();
        containerView.addView(overlayRoot);

        fullPlayerPanel = overlayRoot.findViewById(R.id.full_player_panel);
        miniPlayerBar = overlayRoot.findViewById(R.id.mini_player_bar);

        // Full Video Stage
        fullVideoStage = (AspectRatio16x9FrameLayout) overlayRoot.findViewById(R.id.full_video_stage);
        surfaceFullVideo = (SurfaceView) overlayRoot.findViewById(R.id.surface_full_video);
        audioOnlyBanner = overlayRoot.findViewById(R.id.audio_only_banner);
        progressBuffering = (ProgressBar) overlayRoot.findViewById(R.id.progress_player_buffering);
        playerControlsOverlay = overlayRoot.findViewById(R.id.player_controls_overlay);
        btnMinimizePlayer = (ImageButton) overlayRoot.findViewById(R.id.btn_minimize_player);
        txtOverlayCompactTitle = (TextView) overlayRoot.findViewById(R.id.txt_overlay_compact_title);
        btnPlaybackSpeed = (TextView) overlayRoot.findViewById(R.id.btn_playback_speed);
        btnQualitySelector = (TextView) overlayRoot.findViewById(R.id.btn_quality_selector);
        btnCenterPlayPause = (ImageButton) overlayRoot.findViewById(R.id.btn_center_play_pause);
        txtCurrentTime = (TextView) overlayRoot.findViewById(R.id.txt_current_time);
        txtTotalDuration = (TextView) overlayRoot.findViewById(R.id.txt_total_duration);
        seekPlayerProgress = (SeekBar) overlayRoot.findViewById(R.id.seek_player_progress);

        // ListView Video đề xuất + Header chi tiết (addHeaderView trước khi setAdapter trên API 19)
        listRelatedVideos = (ListView) overlayRoot.findViewById(R.id.list_related_videos);
        detailsHeaderView = inflater.inflate(R.layout.view_player_details_header, listRelatedVideos, false);
        listRelatedVideos.addHeaderView(detailsHeaderView, null, false);
        relatedVideoAdapter = new VideoAdapter(activity);
        listRelatedVideos.setAdapter(relatedVideoAdapter);

        txtPlayerFullTitle = (TextView) detailsHeaderView.findViewById(R.id.txt_player_full_title);
        txtPlayerFullStats = (TextView) detailsHeaderView.findViewById(R.id.txt_player_full_stats);
        imgPlayerChannelAvatar = (ImageView) detailsHeaderView.findViewById(R.id.img_player_channel_avatar);
        txtPlayerChannelName = (TextView) detailsHeaderView.findViewById(R.id.txt_player_channel_name);
        txtPlayerChannelSubs = (TextView) detailsHeaderView.findViewById(R.id.txt_player_channel_subs);
        btnPlayerSubscribe = (Button) detailsHeaderView.findViewById(R.id.btn_player_subscribe);
        btnActionLike = detailsHeaderView.findViewById(R.id.btn_action_like);
        txtActionLikeCount = (TextView) detailsHeaderView.findViewById(R.id.txt_action_like_count);
        btnActionShare = detailsHeaderView.findViewById(R.id.btn_action_share);
        btnActionAudioOnly = detailsHeaderView.findViewById(R.id.btn_action_audio_only);
        txtActionAudioOnly = (TextView) detailsHeaderView.findViewById(R.id.txt_action_audio_only);
        btnActionDownload = detailsHeaderView.findViewById(R.id.btn_action_download);
        cardPlayerDescription = detailsHeaderView.findViewById(R.id.card_player_description);
        txtPlayerStreamTech = (TextView) detailsHeaderView.findViewById(R.id.txt_player_stream_tech);
        txtPlayerDescription = (TextView) detailsHeaderView.findViewById(R.id.txt_player_description);

        // Mini-player Bar
        surfaceMiniVideo = (SurfaceView) overlayRoot.findViewById(R.id.surface_mini_video);
        imgMiniAudioBadge = (ImageView) overlayRoot.findViewById(R.id.img_mini_audio_badge);
        miniPlayerInfoArea = overlayRoot.findViewById(R.id.mini_player_info_area);
        txtMiniTitle = (TextView) overlayRoot.findViewById(R.id.txt_mini_title);
        txtMiniChannel = (TextView) overlayRoot.findViewById(R.id.txt_mini_channel);
        btnMiniPlayPause = (ImageButton) overlayRoot.findViewById(R.id.btn_mini_play_pause);
        btnMiniClose = (ImageButton) overlayRoot.findViewById(R.id.btn_mini_close);
        progressMiniBar = (ProgressBar) overlayRoot.findViewById(R.id.progress_mini_bar);
        txtMiniTitle.setSelected(true);
    }

    private void setupSurfaceCallbacks() {
        SurfaceHolder fullHolder = surfaceFullVideo.getHolder();
        fullHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                fullSurfaceHolder = holder;
                if (currentState == STATE_FULL_EXPANDED && !playerController.isAudioOnlyMode()) {
                    playerController.attachSurfaceHolder(holder);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (fullSurfaceHolder == holder) {
                    fullSurfaceHolder = null;
                }
                playerController.detachSurfaceHolder(holder);
            }
        });

        SurfaceHolder miniHolder = surfaceMiniVideo.getHolder();
        miniHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                miniSurfaceHolder = holder;
                if (currentState == STATE_MINI_PLAYER && !playerController.isAudioOnlyMode()) {
                    playerController.attachSurfaceHolder(holder);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (miniSurfaceHolder == holder) {
                    miniSurfaceHolder = null;
                }
                playerController.detachSurfaceHolder(holder);
            }
        });
    }

    private void setupControlListeners() {
        // Chạm vào khung video 16:9 -> ẩn/hiện lớp điều khiển
        fullVideoStage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleControlsVisibility();
            }
        });

        // Nút thu nhỏ (v) góc trái trên -> về Mini-player
        btnMinimizePlayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                minimizeToMiniPlayer();
            }
        });

        // Nút Play/Pause ở chính giữa Full Player
        btnCenterPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playerController.togglePlayPause();
                scheduleAutoHideControls();
            }
        });

        // Nút chọn chất lượng (PopupWindow)
        btnQualitySelector.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQualitySelectorPopup(v);
            }
        });

        // Nút chọn tốc độ phát (PopupWindow)
        btnPlaybackSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPlaybackSpeedPopup(v);
            }
        });

        // SeekBar tua video
        seekPlayerProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int durationMs = getEffectiveDurationMs();
                    if (durationMs > 0) {
                        int targetMs = (int) ((durationMs * (long) progress) / 1000L);
                        txtCurrentTime.setText(TextFormat.formatDuration(targetMs / 1000L));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userDraggingSeekBar = true;
                uiHandler.removeCallbacks(autoHideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userDraggingSeekBar = false;
                int durationMs = getEffectiveDurationMs();
                if (durationMs > 0) {
                    int targetMs = (int) ((durationMs * (long) seekBar.getProgress()) / 1000L);
                    playerController.seekTo(targetMs);
                }
                scheduleAutoHideControls();
            }
        });

        // Các nút tương tác dưới phần chi tiết video
        btnPlayerSubscribe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isSubscribed = !isSubscribed;
                btnPlayerSubscribe.setText(isSubscribed ? "Đã đăng ký" : activity.getString(R.string.action_subscribe));
                btnPlayerSubscribe.setAlpha(isSubscribed ? 0.75f : 1.0f);
            }
        });

        btnActionLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isLiked = !isLiked;
                updateLikeButtonLabel();
            }
        });

        btnActionShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareCurrentVideo();
            }
        });

        btnActionAudioOnly.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentStreamInfo == null) {
                    return;
                }
                if (playerController.isAudioOnlyMode()) {
                    switchQuality(Config.DEFAULT_QUALITY);
                } else {
                    switchQuality(StreamInfo.QUALITY_AUDIO_ONLY);
                }
            }
        });

        btnActionDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyStreamUrlToClipboard();
            }
        });

        cardPlayerDescription.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                descriptionExpanded = !descriptionExpanded;
                txtPlayerDescription.setMaxLines(descriptionExpanded ? 200 : 3);
            }
        });

        // Bấm vào một video trong danh sách đề xuất -> phát ngay video đó
        listRelatedVideos.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int adapterPos = position - listRelatedVideos.getHeaderViewsCount();
                if (adapterPos >= 0 && adapterPos < relatedVideoAdapter.getCount()) {
                    VideoItem nextVideo = relatedVideoAdapter.getItem(adapterPos);
                    if (nextVideo != null) {
                        WatchHistoryManager.getInstance(activity).recordWatched(nextVideo);
                        openAndPlayVideo(nextVideo);
                    }
                }
            }
        });

        // Sự kiện trên thanh Mini-player
        View.OnClickListener expandClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expandFromMiniPlayer();
            }
        };
        miniPlayerBar.setOnClickListener(expandClickListener);
        miniPlayerInfoArea.setOnClickListener(expandClickListener);
        surfaceMiniVideo.setOnClickListener(expandClickListener);

        btnMiniPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playerController.togglePlayPause();
            }
        });

        btnMiniClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePlayer();
            }
        });
    }

    // ------------------------------------------------------------------
    // API mở Video, Thu nhỏ Mini-player, Phóng to & Đóng
    // ------------------------------------------------------------------

    /**
     * Mở trình phát toàn màn hình và bắt đầu phân giải + phát luồng cho {@code video}.
     */
    public void openAndPlayVideo(final VideoItem video) {
        if (video == null || video.getId() == null || video.getId().length() == 0) {
            return;
        }
        dismissPopupIfShowing();
        cancelPendingRequests();

        this.currentVideo = video;
        this.currentStreamInfo = null;
        this.currentPlaybackSource = null;
        this.descriptionExpanded = false;
        this.isLiked = false;

        // Hiển thị ngay khung Full Player
        setState(STATE_FULL_EXPANDED);
        showControlsOverlay(true);
        bindVideoMetadataToUi(video, null, null);

        // Reset trạng thái tiến trình
        seekPlayerProgress.setProgress(0);
        seekPlayerProgress.setSecondaryProgress(0);
        progressMiniBar.setProgress(0);
        txtCurrentTime.setText("0:00");
        txtTotalDuration.setText(video.getDurationSeconds() > 0
                ? TextFormat.formatDuration(video.getDurationSeconds())
                : "0:00");
        progressBuffering.setVisibility(View.VISIBLE);
        txtPlayerStreamTech.setText(R.string.player_resolving_stream);

        // Nếu đang ở chế độ hình, đảm bảo gắn fullSurfaceHolder
        updateSurfaceVisibilityForMode(false);
        if (fullSurfaceHolder != null) {
            playerController.attachSurfaceHolder(fullSurfaceHolder);
        }

        // 1. Gọi StreamResolver bóc tách cả 3 nguồn (IOS HLS .m3u8 con + ANDROID_VR Adaptive 60fps + Progressive)
        resolveRequestHandle = StreamResolver.getInstance().resolveAsync(
                video.getId(),
                false,
                new ApiCallback<StreamInfo>() {
                    @Override
                    public void onSuccess(StreamInfo info) {
                        currentStreamInfo = info;
                        PlaybackSource selected = info.selectPlaybackSource(preferredQuality, preferAdaptiveForHd);
                        if (selected == null) {
                            onError(new Exception(activity.getString(R.string.player_error_resolve)));
                            return;
                        }
                        startPlaybackWithSource(selected, 0, true);
                    }

                    @Override
                    public void onError(Exception error) {
                        progressBuffering.setVisibility(View.GONE);
                        String msg = error != null && error.getMessage() != null
                                ? error.getMessage()
                                : activity.getString(R.string.player_error_resolve);
                        txtPlayerStreamTech.setText(msg);
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    }
                }
        );

        // 2. Tải danh sách video đề xuất liên quan ở phía dưới
        loadRelatedVideosFor(video);
    }

    private void startPlaybackWithSource(PlaybackSource source, int resumePositionMs, boolean autoStart) {
        if (source == null) {
            return;
        }
        this.currentPlaybackSource = source;
        this.preferredQuality = source.getQualityLabel();

        // Cập nhật nhãn nút chất lượng (ví dụ: "MPEG-4 720p60", "MPEG-4 1080p60", "M4A 128kbps")
        btnQualitySelector.setText(source.getDisplayLabel());

        boolean audioOnly = source.isAudioOnly();
        updateSurfaceVisibilityForMode(audioOnly);

        if (!audioOnly) {
            if (currentState == STATE_FULL_EXPANDED && fullSurfaceHolder != null) {
                playerController.attachSurfaceHolder(fullSurfaceHolder);
            } else if (currentState == STATE_MINI_PLAYER && miniSurfaceHolder != null) {
                playerController.attachSurfaceHolder(miniSurfaceHolder);
            }
        }

        bindVideoMetadataToUi(currentVideo, currentStreamInfo, source);
        playerController.playSource(source, resumePositionMs, autoStart);
    }

    private void updateSurfaceVisibilityForMode(boolean audioOnly) {
        if (audioOnly) {
            audioOnlyBanner.setVisibility(View.VISIBLE);
            imgMiniAudioBadge.setVisibility(View.VISIBLE);
            txtActionAudioOnly.setText("Bật lại hình (720p60)");
        } else {
            audioOnlyBanner.setVisibility(View.GONE);
            imgMiniAudioBadge.setVisibility(View.GONE);
            txtActionAudioOnly.setText(R.string.quality_audio);
        }
    }

    /**
     * Đổi độ phân giải ngay lúc đang xem:
     * Lưu lại vị trí {@code currentPosition} hiện tại, nạp URL mới vào {@link DualMediaPlayerController}
     * và {@code seekTo(currentPosition)} để tiếp tục phát mượt mà tại đúng giây đang xem.
     */
    public void switchQuality(String newQualityLabel) {
        if (currentStreamInfo == null) {
            return;
        }
        int resumePosMs = playerController.getCurrentPosition();
        boolean shouldAutoStart = playerController.isPlaying() || !playerController.isReady();
        PlaybackSource nextSource = currentStreamInfo.selectPlaybackSource(newQualityLabel, preferAdaptiveForHd);
        if (nextSource == null) {
            Toast.makeText(activity, "Không có luồng cho mức " + newQualityLabel, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity,
                activity.getString(R.string.player_quality_switched, nextSource.getDisplayLabel()),
                Toast.LENGTH_SHORT).show();
        startPlaybackWithSource(nextSource, resumePosMs, shouldAutoStart);
    }

    /**
     * Thu nhỏ trình phát toàn màn hình xuống thanh Mini-player 62dp ở đáy màn hình
     * (ngay trên Bottom Navigation Bar) mà không làm gián đoạn luồng video/audio đang phát.
     */
    public void minimizeToMiniPlayer() {
        if (currentState != STATE_FULL_EXPANDED) {
            return;
        }
        dismissPopupIfShowing();
        uiHandler.removeCallbacks(autoHideControlsRunnable);
        setState(STATE_MINI_PLAYER);
        if (!playerController.isAudioOnlyMode() && miniSurfaceHolder != null) {
            playerController.attachSurfaceHolder(miniSurfaceHolder);
        }
    }

    /**
     * Phóng to từ thanh Mini-player lên lại màn hình Full Player mà không làm gián đoạn việc phát.
     */
    public void expandFromMiniPlayer() {
        if (currentState != STATE_MINI_PLAYER) {
            return;
        }
        setState(STATE_FULL_EXPANDED);
        showControlsOverlay(true);
        if (!playerController.isAudioOnlyMode() && fullSurfaceHolder != null) {
            playerController.attachSurfaceHolder(fullSurfaceHolder);
        }
    }

    /**
     * Đóng hoàn toàn Mini-player / Full Player và giải phóng {@link DualMediaPlayerController}.
     */
    public void closePlayer() {
        dismissPopupIfShowing();
        uiHandler.removeCallbacks(autoHideControlsRunnable);
        cancelPendingRequests();
        playerController.release();
        currentVideo = null;
        currentStreamInfo = null;
        currentPlaybackSource = null;
        setState(STATE_HIDDEN);
    }

    private void setState(int newState) {
        this.currentState = newState;
        if (newState == STATE_HIDDEN) {
            fullPlayerPanel.setVisibility(View.GONE);
            miniPlayerBar.setVisibility(View.GONE);
            containerView.setVisibility(View.GONE);
        } else if (newState == STATE_FULL_EXPANDED) {
            containerView.setVisibility(View.VISIBLE);
            miniPlayerBar.setVisibility(View.GONE);
            fullPlayerPanel.setVisibility(View.VISIBLE);
        } else if (newState == STATE_MINI_PLAYER) {
            containerView.setVisibility(View.VISIBLE);
            fullPlayerPanel.setVisibility(View.GONE);
            miniPlayerBar.setVisibility(View.VISIBLE);
            txtMiniTitle.setSelected(true);
        }
        if (stateChangeListener != null) {
            stateChangeListener.onPlayerStateChanged(newState);
        }
    }

    public int getState() {
        return currentState;
    }

    public boolean isFullExpanded() {
        return currentState == STATE_FULL_EXPANDED;
    }

    public boolean isMiniPlayerVisible() {
        return currentState == STATE_MINI_PLAYER;
    }

    public boolean isActive() {
        return currentState != STATE_HIDDEN;
    }

    // ------------------------------------------------------------------
    // PopupWindow chọn Độ phân giải (MPEG-4 1080p60 / 720p60 / 360p / Audio Only)
    // ------------------------------------------------------------------

    private void showQualitySelectorPopup(View anchorView) {
        if (currentStreamInfo == null) {
            Toast.makeText(activity, R.string.player_resolving_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        dismissPopupIfShowing();
        uiHandler.removeCallbacks(autoHideControlsRunnable);

        List<String> availableQualities = currentStreamInfo.getAvailableQualities();
        if (availableQualities.isEmpty()) {
            return;
        }

        final List<QualityOptionItem> optionItems = new ArrayList<QualityOptionItem>();
        for (int i = 0; i < availableQualities.size(); i++) {
            String q = availableQualities.get(i);
            PlaybackSource src = currentStreamInfo.resolveExactQuality(q, preferAdaptiveForHd);
            if (src != null) {
                String tag = buildShortSourceTag(src);
                boolean checked = currentPlaybackSource != null
                        && q.equalsIgnoreCase(currentPlaybackSource.getQualityLabel());
                optionItems.add(new QualityOptionItem(q, src.getDisplayLabel(), tag, checked, false));
            }
        }

        // Thêm tùy chọn chuyển đổi chế độ ưu tiên HLS .m3u8 con vs Dual Adaptive H.264 cho máy KitKat
        String engineTitle = preferAdaptiveForHd
                ? "Chế độ HD: Dual H.264+AAC"
                : "Chế độ HD: HLS .m3u8 (Mượt)";
        String engineTag = "Đổi";
        optionItems.add(new QualityOptionItem("__TOGGLE_ENGINE__", engineTitle, engineTag, false, true));

        View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_quality_selector, null);
        TextView txtHeader = (TextView) popupView.findViewById(R.id.txt_popup_header);
        txtHeader.setText(R.string.player_select_quality);
        ListView listOptions = (ListView) popupView.findViewById(R.id.list_popup_options);

        PopupOptionsAdapter adapter = new PopupOptionsAdapter(activity, optionItems);
        listOptions.setAdapter(adapter);

        int widthPx = (int) ImageLoader.getInstance().dpToPx(265f);
        final PopupWindow popup = new PopupWindow(
                popupView,
                widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                scheduleAutoHideControls();
            }
        });

        listOptions.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                popup.dismiss();
                if (position < 0 || position >= optionItems.size()) {
                    return;
                }
                QualityOptionItem picked = optionItems.get(position);
                if (picked.isEngineToggle) {
                    preferAdaptiveForHd = !preferAdaptiveForHd;
                    String currentQ = currentPlaybackSource != null
                            ? currentPlaybackSource.getQualityLabel()
                            : preferredQuality;
                    switchQuality(currentQ);
                } else {
                    switchQuality(picked.qualityCode);
                }
            }
        });

        activePopupWindow = popup;
        popup.showAsDropDown(anchorView, -widthPx + anchorView.getWidth(), 8);
    }

    private void showPlaybackSpeedPopup(View anchorView) {
        dismissPopupIfShowing();
        uiHandler.removeCallbacks(autoHideControlsRunnable);

        final float[] speeds = new float[] {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        final String[] labels = new String[] {"0.75x", "1x (Chuẩn)", "1.25x", "1.5x", "2x"};
        float currentSpeed = playerController.getPlaybackSpeed();

        final List<QualityOptionItem> optionItems = new ArrayList<QualityOptionItem>();
        for (int i = 0; i < speeds.length; i++) {
            boolean checked = Math.abs(currentSpeed - speeds[i]) < 0.05f;
            optionItems.add(new QualityOptionItem(
                    String.valueOf(speeds[i]),
                    labels[i],
                    "",
                    checked,
                    false
            ));
        }

        View popupView = LayoutInflater.from(activity).inflate(R.layout.popup_quality_selector, null);
        TextView txtHeader = (TextView) popupView.findViewById(R.id.txt_popup_header);
        txtHeader.setText(R.string.player_select_speed);
        ListView listOptions = (ListView) popupView.findViewById(R.id.list_popup_options);

        PopupOptionsAdapter adapter = new PopupOptionsAdapter(activity, optionItems);
        listOptions.setAdapter(adapter);

        int widthPx = (int) ImageLoader.getInstance().dpToPx(200f);
        final PopupWindow popup = new PopupWindow(
                popupView,
                widthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                scheduleAutoHideControls();
            }
        });

        listOptions.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                popup.dismiss();
                if (position < 0 || position >= speeds.length) {
                    return;
                }
                float chosenSpeed = speeds[position];
                boolean supported = playerController.setPlaybackSpeed(chosenSpeed);
                if (supported || chosenSpeed == 1.0f) {
                    String shortText = chosenSpeed == 1.0f ? "1x" : (chosenSpeed + "x");
                    btnPlaybackSpeed.setText(shortText);
                } else {
                    btnPlaybackSpeed.setText("1x");
                    Toast.makeText(activity,
                            "MediaPlayer gốc của Android 4.4 giữ tốc độ chuẩn 1x",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        activePopupWindow = popup;
        popup.showAsDropDown(anchorView, -widthPx + anchorView.getWidth(), 8);
    }

    private static String buildShortSourceTag(PlaybackSource src) {
        switch (src.getMode()) {
            case PlaybackSource.MODE_HLS:
                return src.isHlsVariantSpecific() ? "HLS .m3u8" : "HLS Auto";
            case PlaybackSource.MODE_DUAL_ADAPTIVE:
                return "H.264+AAC";
            case PlaybackSource.MODE_PROGRESSIVE:
                return "MP4 Progressive";
            case PlaybackSource.MODE_AUDIO_ONLY:
                return "AAC Original";
            default:
                return "";
        }
    }

    private void dismissPopupIfShowing() {
        if (activePopupWindow != null && activePopupWindow.isShowing()) {
            try {
                activePopupWindow.dismiss();
            } catch (Exception ignored) {
            }
        }
        activePopupWindow = null;
    }

    // ------------------------------------------------------------------
    // Cập nhật thông tin Video & Danh sách đề xuất
    // ------------------------------------------------------------------

    private void bindVideoMetadataToUi(VideoItem video, StreamInfo streamInfo, PlaybackSource source) {
        if (video == null) {
            return;
        }
        String title = (streamInfo != null && streamInfo.getTitle().length() > 0)
                ? streamInfo.getTitle()
                : video.getTitle();
        String author = (streamInfo != null && streamInfo.getAuthor().length() > 0)
                ? streamInfo.getAuthor()
                : video.getChannelTitle();

        txtOverlayCompactTitle.setText(title);
        txtPlayerFullTitle.setText(title);
        txtPlayerChannelName.setText(author != null && author.length() > 0 ? author : "YouTube Channel");

        long views = video.getViewCount() > 0
                ? video.getViewCount()
                : (streamInfo != null ? streamInfo.getViewCount() : -1L);
        StringBuilder statsSb = new StringBuilder();
        String formattedViews = TextFormat.formatViews(views);
        if (formattedViews.length() > 0) {
            statsSb.append(formattedViews);
        }
        String relTime = video.isLive()
                ? "Đang phát trực tiếp"
                : TextFormat.formatRelativeTime(video.getPublishedAtMillis());
        if (relTime.length() > 0) {
            if (statsSb.length() > 0) {
                statsSb.append(" · ");
            }
            statsSb.append(relTime);
        }
        if (source != null) {
            if (statsSb.length() > 0) {
                statsSb.append(" · ");
            }
            statsSb.append(source.getDisplayLabel());
        }
        txtPlayerFullStats.setText(statsSb.toString());

        long subs = video.getChannelSubscriberCount();
        txtPlayerChannelSubs.setText(subs > 0
                ? TextFormat.formatSubscribers(subs)
                : "Kênh YouTube chính thức");

        ImageLoader.getInstance().loadCircleAvatar(
                imgPlayerChannelAvatar,
                video.getChannelAvatarUrl(),
                36,
                R.drawable.bg_avatar_placeholder
        );

        updateLikeButtonLabel();

        if (source != null) {
            txtPlayerStreamTech.setText("Luồng đang phát: " + source.getModeDescription());
        }

        String desc = video.getDescription();
        if (desc != null && desc.trim().length() > 0) {
            txtPlayerDescription.setText(desc);
        } else {
            txtPlayerDescription.setText("Chạm vào thẻ này để xem thông tin kỹ thuật luồng H.264/AAC trên Android 4.4.4.");
        }

        // Cập nhật Mini-player
        txtMiniTitle.setText(title);
        String miniSub = author != null ? author : "";
        if (source != null) {
            miniSub = miniSub.length() > 0
                    ? (miniSub + " · " + source.getDisplayLabel())
                    : source.getDisplayLabel();
        }
        txtMiniChannel.setText(miniSub);
    }

    private void updateLikeButtonLabel() {
        long baseLikes = currentVideo != null ? currentVideo.getLikeCount() : -1L;
        if (baseLikes > 0) {
            long count = isLiked ? baseLikes + 1 : baseLikes;
            txtActionLikeCount.setText((isLiked ? "✓ " : "") + TextFormat.formatCompactCount(count));
        } else {
            txtActionLikeCount.setText(isLiked ? "✓ Đã thích" : activity.getString(R.string.action_like));
        }
    }

    private void loadRelatedVideosFor(final VideoItem video) {
        relatedVideoAdapter.clear();
        String query = video.getChannelTitle();
        if (query == null || query.trim().length() < 2) {
            query = video.getTitle();
        }
        if (query != null && query.length() > 36) {
            query = query.substring(0, 36);
        }
        if (query == null || query.trim().length() == 0) {
            return;
        }

        relatedRequestHandle = YouTubeApi.getInstance().searchVideos(
                query,
                null,
                "relevance",
                new ApiCallback<PagedResult<VideoItem>>() {
                    @Override
                    public void onSuccess(PagedResult<VideoItem> result) {
                        List<VideoItem> filtered = new ArrayList<VideoItem>();
                        List<VideoItem> raw = result.getItems();
                        for (int i = 0; i < raw.size(); i++) {
                            VideoItem item = raw.get(i);
                            if (item != null && !item.getId().equals(video.getId())) {
                                filtered.add(item);
                            }
                        }
                        relatedVideoAdapter.setItems(filtered);
                    }

                    @Override
                    public void onError(Exception error) {
                        // Bỏ qua lỗi danh sách đề xuất phụ để không làm phiền việc xem video chính
                    }
                }
        );
    }

    // ------------------------------------------------------------------
    // Callbacks từ DualMediaPlayerController
    // ------------------------------------------------------------------

    @Override
    public void onPrepared(int durationMs, int videoWidth, int videoHeight) {
        progressBuffering.setVisibility(View.GONE);
        int effectiveDurationMs = getEffectiveDurationMs();
        if (effectiveDurationMs > 0) {
            txtTotalDuration.setText(TextFormat.formatDuration(effectiveDurationMs / 1000L));
        }
        scheduleAutoHideControls();
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        int iconRes = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
        btnCenterPlayPause.setImageResource(iconRes);
        btnMiniPlayPause.setImageResource(iconRes);
        if (isPlaying) {
            scheduleAutoHideControls();
        } else {
            uiHandler.removeCallbacks(autoHideControlsRunnable);
        }
    }

    @Override
    public void onBufferingStateChanged(boolean isBuffering) {
        progressBuffering.setVisibility(isBuffering ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onProgressUpdate(int currentPositionMs, int durationMs, int bufferedPercent) {
        if (userDraggingSeekBar) {
            return;
        }
        int effectiveDurationMs = durationMs > 0 ? durationMs : getEffectiveDurationMs();
        txtCurrentTime.setText(TextFormat.formatDuration(currentPositionMs / 1000L));
        if (effectiveDurationMs > 0) {
            txtTotalDuration.setText(TextFormat.formatDuration(effectiveDurationMs / 1000L));
            int progressPermille = (int) ((currentPositionMs * 1000L) / effectiveDurationMs);
            progressPermille = Math.max(0, Math.min(1000, progressPermille));
            seekPlayerProgress.setProgress(progressPermille);
            seekPlayerProgress.setSecondaryProgress(Math.max(0, Math.min(1000, bufferedPercent * 10)));
            progressMiniBar.setProgress(progressPermille);
        }
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
    }

    @Override
    public void onCompletion() {
        showControlsOverlay(true);
        seekPlayerProgress.setProgress(1000);
        progressMiniBar.setProgress(1000);
    }

    @Override
    public void onError(String message) {
        progressBuffering.setVisibility(View.GONE);
        showControlsOverlay(true);

        // Tự động chuyển đổi sang Progressive 360p (itag 18) hoặc Dual Adaptive nếu HLS .m3u8 gặp sự cố
        if (currentStreamInfo != null && currentPlaybackSource != null) {
            if (currentPlaybackSource.isHls() && !preferAdaptiveForHd) {
                preferAdaptiveForHd = true;
                PlaybackSource fallback = currentStreamInfo.selectPlaybackSource(
                        currentPlaybackSource.getQualityLabel(), true);
                if (fallback != null && !fallback.isHls()) {
                    Toast.makeText(activity,
                            "Tự động chuyển sang luồng Dual Adaptive H.264 (" + fallback.getDisplayLabel() + ")",
                            Toast.LENGTH_SHORT).show();
                    startPlaybackWithSource(fallback, playerController.getCurrentPosition(), true);
                    return;
                }
            }
            if (!StreamInfo.QUALITY_360P.equalsIgnoreCase(currentPlaybackSource.getQualityLabel())) {
                PlaybackSource prog360 = currentStreamInfo.resolveExactQuality(StreamInfo.QUALITY_360P, false);
                if (prog360 != null) {
                    Toast.makeText(activity,
                            "Tự động lùi về MPEG-4 360p ổn định",
                            Toast.LENGTH_SHORT).show();
                    startPlaybackWithSource(prog360, playerController.getCurrentPosition(), true);
                    return;
                }
            }
        }

        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Tiện ích Điều khiển & Vòng đời Activity
    // ------------------------------------------------------------------

    private int getEffectiveDurationMs() {
        int playerDur = playerController.getDuration();
        if (playerDur > 0) {
            return playerDur;
        }
        if (currentStreamInfo != null && currentStreamInfo.getDurationSeconds() > 0) {
            return (int) (currentStreamInfo.getDurationSeconds() * 1000L);
        }
        if (currentVideo != null && currentVideo.getDurationSeconds() > 0) {
            return (int) (currentVideo.getDurationSeconds() * 1000L);
        }
        return 0;
    }

    private void toggleControlsVisibility() {
        if (playerControlsOverlay.getVisibility() == View.VISIBLE) {
            uiHandler.removeCallbacks(autoHideControlsRunnable);
            playerControlsOverlay.setVisibility(View.GONE);
        } else {
            showControlsOverlay(true);
        }
    }

    private void showControlsOverlay(boolean autoHide) {
        playerControlsOverlay.setVisibility(View.VISIBLE);
        if (autoHide) {
            scheduleAutoHideControls();
        } else {
            uiHandler.removeCallbacks(autoHideControlsRunnable);
        }
    }

    private void scheduleAutoHideControls() {
        uiHandler.removeCallbacks(autoHideControlsRunnable);
        if (playerController.isPlaying()) {
            uiHandler.postDelayed(autoHideControlsRunnable, CONTROLS_AUTO_HIDE_DELAY_MS);
        }
    }

    private void shareCurrentVideo() {
        if (currentVideo == null) {
            return;
        }
        String url = "https://www.youtube.com/watch?v=" + currentVideo.getId();
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, currentVideo.getTitle());
        shareIntent.putExtra(Intent.EXTRA_TEXT, currentVideo.getTitle() + "\n" + url);
        try {
            activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.action_share)));
        } catch (Exception ignored) {
        }
    }

    private void copyStreamUrlToClipboard() {
        if (currentPlaybackSource == null || currentPlaybackSource.getPrimaryUrl() == null) {
            Toast.makeText(activity, R.string.player_resolving_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText(
                        currentPlaybackSource.getDisplayLabel(),
                        currentPlaybackSource.getPrimaryUrl()
                );
                clipboard.setPrimaryClip(clip);
                Toast.makeText(activity,
                        "Đã sao chép URL luồng " + currentPlaybackSource.getDisplayLabel(),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private void cancelPendingRequests() {
        if (resolveRequestHandle != null) {
            resolveRequestHandle.cancel();
            resolveRequestHandle = null;
        }
        if (relatedRequestHandle != null) {
            relatedRequestHandle.cancel();
            relatedRequestHandle = null;
        }
    }

    /**
     * Gọi từ {@code MainActivity.onPause()}:
     * Tạm dừng phát hình khi ứng dụng vào nền (trừ khi người dùng đang bật chế độ Audio-Only nghe nhạc nền).
     */
    public void onActivityPause() {
        if (playerController.isPlaying() && !playerController.isAudioOnlyMode()) {
            playerController.pause();
        }
    }

    /**
     * Gọi từ {@code MainActivity.onDestroy()}:
     * Giải phóng toàn bộ MediaPlayer kép, Handler và hủy các request mạng.
     */
    public void onActivityDestroy() {
        dismissPopupIfShowing();
        uiHandler.removeCallbacks(autoHideControlsRunnable);
        cancelPendingRequests();
        playerController.release();
    }

    // ------------------------------------------------------------------
    // Adapter hiển thị danh sách chọn Độ phân giải / Tốc độ trong PopupWindow
    // ------------------------------------------------------------------

    private static final class QualityOptionItem {
        final String qualityCode;
        final String displayTitle;
        final String sourceTag;
        final boolean checked;
        final boolean isEngineToggle;

        QualityOptionItem(String qualityCode,
                          String displayTitle,
                          String sourceTag,
                          boolean checked,
                          boolean isEngineToggle) {
            this.qualityCode = qualityCode;
            this.displayTitle = displayTitle;
            this.sourceTag = sourceTag;
            this.checked = checked;
            this.isEngineToggle = isEngineToggle;
        }
    }

    private static final class PopupOptionsAdapter extends BaseAdapter {

        private final LayoutInflater inflater;
        private final List<QualityOptionItem> items;

        PopupOptionsAdapter(Context context, List<QualityOptionItem> items) {
            this.inflater = LayoutInflater.from(context);
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public QualityOptionItem getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_quality_option, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            QualityOptionItem item = items.get(position);
            holder.txtCheck.setVisibility(item.checked ? View.VISIBLE : View.INVISIBLE);
            holder.txtTitle.setText(item.displayTitle);
            if (item.sourceTag != null && item.sourceTag.length() > 0) {
                holder.txtTag.setVisibility(View.VISIBLE);
                holder.txtTag.setText(item.sourceTag);
            } else {
                holder.txtTag.setVisibility(View.GONE);
            }

            return convertView;
        }

        static final class ViewHolder {
            final TextView txtCheck;
            final TextView txtTitle;
            final TextView txtTag;

            ViewHolder(View root) {
                this.txtCheck = (TextView) root.findViewById(R.id.txt_quality_check);
                this.txtTitle = (TextView) root.findViewById(R.id.txt_quality_title);
                this.txtTag = (TextView) root.findViewById(R.id.txt_quality_tag);
            }
        }
    }
}

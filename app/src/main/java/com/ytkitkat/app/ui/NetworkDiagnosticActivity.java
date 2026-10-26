package com.ytkitkat.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.R;
import com.ytkitkat.app.model.PagedResult;
import com.ytkitkat.app.model.PlaybackSource;
import com.ytkitkat.app.model.StreamFormat;
import com.ytkitkat.app.model.StreamInfo;
import com.ytkitkat.app.model.VideoItem;
import com.ytkitkat.app.network.ApiCallback;
import com.ytkitkat.app.network.HttpClient;
import com.ytkitkat.app.network.RequestHandle;
import com.ytkitkat.app.network.StreamResolver;
import com.ytkitkat.app.network.TLSSocketFactory;
import com.ytkitkat.app.network.YouTubeApi;
import com.ytkitkat.app.util.TextFormat;

import java.util.List;
import java.util.Map;

/**
 * Màn hình kiểm tra trực tiếp trên thiết bị Android 4.4.4 (Sharp Aquos 305SH):
 * - Chẩn đoán TLS 1.2 & Cipher Suites.
 * - Kiểm tra YouTube Data API v3 (videos.list Thịnh hành VN).
 * - Kiểm tra StreamResolver:
 *   + Nguồn 1: HLS Master & các URL .m3u8 con đã tách từ #EXT-X-STREAM-INF (1080p60, 720p60, 720p, 480p, 360p).
 *   + Nguồn 2: Adaptive H.264 (itag 299 1080p60, 137 1080p, 298 720p60, 136 720p, 135 480p, 134 360p, 160 144p)
 *              kèm nhãn "MPEG-4 " + qualityLabel và Audio AAC itag 140 (ưu tiên Original Audio).
 *   + Nguồn 3: Progressive MP4 360p (itag 18) & Audio Only.
 */
public class NetworkDiagnosticActivity extends Activity {

    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    public static final String EXTRA_VIDEO_TITLE = "extra_video_title";

    private EditText editVideoId;
    private TextView txtLog;
    private ScrollView scrollLog;

    private TextView chip1080p60;
    private TextView chip1080p;
    private TextView chip720p60;
    private TextView chip720p;
    private TextView chip480p;
    private TextView chip360p;
    private TextView chip144p;
    private TextView chipAudio;

    private String selectedQuality = Config.DEFAULT_QUALITY;
    private StreamInfo lastStreamInfo;
    private RequestHandle activeHandle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_diagnostic);

        editVideoId = (EditText) findViewById(R.id.edit_video_id);
        txtLog = (TextView) findViewById(R.id.txt_log);
        scrollLog = (ScrollView) findViewById(R.id.scroll_log);

        Button btnTls = (Button) findViewById(R.id.btn_test_tls);
        Button btnTrending = (Button) findViewById(R.id.btn_test_trending);
        Button btnStream = (Button) findViewById(R.id.btn_resolve_stream);
        Button btnClear = (Button) findViewById(R.id.btn_clear_log);

        chip1080p60 = (TextView) findViewById(R.id.chip_q_1080_60);
        chip1080p = (TextView) findViewById(R.id.chip_q_1080);
        chip720p60 = (TextView) findViewById(R.id.chip_q_720_60);
        chip720p = (TextView) findViewById(R.id.chip_q_720);
        chip480p = (TextView) findViewById(R.id.chip_q_480);
        chip360p = (TextView) findViewById(R.id.chip_q_360);
        chip144p = (TextView) findViewById(R.id.chip_q_144);
        chipAudio = (TextView) findViewById(R.id.chip_q_audio);

        setupQualityChips();

        btnTls.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTlsDiagnostic();
            }
        });

        btnTrending.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTrendingTest();
            }
        });

        btnStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runStreamResolverTest();
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                txtLog.setText("");
            }
        });

        runTlsDiagnostic();

        // Nếu được mở từ MainActivity khi người dùng chạm vào 1 video -> tự động giải mã luồng ngay
        Intent intent = getIntent();
        if (intent != null) {
            String passedVideoId = intent.getStringExtra(EXTRA_VIDEO_ID);
            if (passedVideoId != null && passedVideoId.trim().length() > 0) {
                editVideoId.setText(passedVideoId.trim());
                runStreamResolverTest();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelActiveRequest();
    }

    private void cancelActiveRequest() {
        if (activeHandle != null) {
            activeHandle.cancel();
            activeHandle = null;
        }
    }

    private void setupQualityChips() {
        final TextView[] allChips = new TextView[] {
                chip1080p60, chip1080p, chip720p60, chip720p, chip480p, chip360p, chip144p, chipAudio
        };
        final String[] qualities = new String[] {
                StreamInfo.QUALITY_1080P60,
                StreamInfo.QUALITY_1080P,
                StreamInfo.QUALITY_720P60,
                StreamInfo.QUALITY_720P,
                StreamInfo.QUALITY_480P,
                StreamInfo.QUALITY_360P,
                StreamInfo.QUALITY_144P,
                StreamInfo.QUALITY_AUDIO_ONLY
        };

        for (int i = 0; i < allChips.length; i++) {
            final String q = qualities[i];
            allChips[i].setSelected(q.equalsIgnoreCase(selectedQuality));
            allChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedQuality = q;
                    for (int j = 0; j < allChips.length; j++) {
                        allChips[j].setSelected(qualities[j].equalsIgnoreCase(selectedQuality));
                    }
                    if (lastStreamInfo != null) {
                        logSelectedPlaybackSource(lastStreamInfo, selectedQuality);
                    }
                }
            });
        }
    }

    private void runTlsDiagnostic() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== THIẾT BỊ & TLS 1.2 ===\n");
        sb.append("Thiết bị: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE)
                .append(", API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("TLSSocketFactory đang bật: ").append(HttpClient.isCustomTlsActive()).append('\n');
        sb.append(TLSSocketFactory.describeDeviceTlsSupport()).append('\n');
        sb.append("DEFAULT_QUALITY: ").append(Config.DEFAULT_QUALITY).append('\n');
        sb.append("API Key đã cấu hình: ").append(Config.isApiKeyConfigured()).append('\n');
        appendLog(sb.toString());
    }

    private void runTrendingTest() {
        cancelActiveRequest();
        appendLog("\n>>> Đang tải danh sách Thịnh hành VN...");
        activeHandle = YouTubeApi.getInstance().fetchTrendingVideos(
                null,
                null,
                new ApiCallback<PagedResult<VideoItem>>() {
                    @Override
                    public void onSuccess(PagedResult<VideoItem> result) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Nhận ").append(result.size()).append(" video (nextPageToken=")
                                .append(result.getNextPageToken()).append("):\n");
                        List<VideoItem> items = result.getItems();
                        int limit = Math.min(5, items.size());
                        for (int i = 0; i < limit; i++) {
                            VideoItem v = items.get(i);
                            sb.append(i + 1).append(". [").append(v.getId()).append("] ")
                                    .append(v.getTitle()).append(" (")
                                    .append(v.getFormattedDuration()).append(") - ")
                                    .append(v.getMetaLine()).append('\n');
                        }
                        if (!items.isEmpty()) {
                            editVideoId.setText(items.get(0).getId());
                        }
                        appendLog(sb.toString());
                    }

                    @Override
                    public void onError(Exception error) {
                        appendLog("LỖI Data API: " + error.getMessage());
                    }
                }
        );
    }

    private void runStreamResolverTest() {
        cancelActiveRequest();
        String videoId = editVideoId.getText() != null ? editVideoId.getText().toString().trim() : "";
        if (videoId.length() == 0) {
            appendLog("Vui lòng nhập Video ID.");
            return;
        }
        appendLog("\n>>> Đang bóc tách luồng (kèm 720p60/1080p60 & .m3u8 con) cho videoId=" + videoId + "...");
        activeHandle = StreamResolver.getInstance().resolveAsync(
                videoId,
                true,
                new ApiCallback<StreamInfo>() {
                    @Override
                    public void onSuccess(StreamInfo info) {
                        lastStreamInfo = info;
                        StringBuilder sb = new StringBuilder();
                        sb.append("=== KẾT QUẢ STREAM RESOLVER ===\n");
                        sb.append("Tiêu đề: ").append(info.getTitle()).append('\n');
                        sb.append("Kênh: ").append(info.getAuthor())
                                .append(" | Thời lượng: ")
                                .append(TextFormat.formatDuration(info.getDurationSeconds())).append('\n');
                        sb.append("Tóm tắt: ").append(info.getResolverSummary()).append('\n');
                        sb.append("Chất lượng khả dụng: ").append(info.getAvailableQualities()).append('\n');

                        sb.append("\n[Nguồn 1 - HLS Master & Variant .m3u8 con (IOS)]:\n");
                        if (info.getHlsManifestUrl() != null) {
                            sb.append("  • Master .m3u8: CÓ\n");
                            for (Map.Entry<String, String> entry : info.getHlsVariantUrls().entrySet()) {
                                sb.append("  • MPEG-4 ").append(entry.getKey())
                                        .append(" -> .m3u8 con đã mux H.264+AAC\n");
                            }
                        } else {
                            sb.append("  • KHÔNG\n");
                        }

                        sb.append("\n[Nguồn 2 - Adaptive Video H.264 (ANDROID_VR - itag 299/137/298/136/135/134/160)]:\n");
                        for (StreamFormat f : info.getAdaptiveAvcVideos()) {
                            sb.append("  • ").append(f.toTechnicalSummary()).append('\n');
                        }

                        sb.append("\n[Nguồn 2/3 - Adaptive Audio AAC (Ưu tiên Original Audio)]:\n");
                        for (StreamFormat f : info.getAdaptiveAacAudios()) {
                            sb.append("  • ").append(f.toTechnicalSummary()).append('\n');
                        }

                        sb.append("\n[Nguồn 3 - Progressive MP4 (itag 18)]:\n");
                        for (StreamFormat f : info.getProgressiveFormats()) {
                            sb.append("  • ").append(f.toTechnicalSummary()).append('\n');
                        }

                        appendLog(sb.toString());
                        logSelectedPlaybackSource(info, selectedQuality);
                    }

                    @Override
                    public void onError(Exception error) {
                        appendLog("LỖI StreamResolver: " + error.getMessage());
                    }
                }
        );
    }

    private void logSelectedPlaybackSource(StreamInfo info, String quality) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Nguồn phát cho [MPEG-4 ").append(quality).append("] ---\n");
        PlaybackSource defaultSource = info.selectPlaybackSource(quality, false);
        if (defaultSource != null) {
            sb.append("• Ưu tiên HLS .m3u8 con: ").append(defaultSource.getModeDescription()).append('\n');
        }
        PlaybackSource adaptiveSource = info.selectPlaybackSource(quality, true);
        if (adaptiveSource != null) {
            sb.append("• Ưu tiên Dual Adaptive (BravePipe): ").append(adaptiveSource.getModeDescription()).append('\n');
            if (adaptiveSource.getVideoFormat() != null) {
                sb.append("    Video: ").append(adaptiveSource.getVideoFormat().toTechnicalSummary()).append('\n');
            }
            if (adaptiveSource.getAudioFormat() != null) {
                sb.append("    Audio: ").append(adaptiveSource.getAudioFormat().toTechnicalSummary()).append('\n');
            }
        }
        appendLog(sb.toString());
    }

    private void appendLog(String message) {
        CharSequence current = txtLog.getText();
        if (current == null || current.length() == 0) {
            txtLog.setText(message);
        } else {
            txtLog.append("\n" + message);
        }
        scrollLog.post(new Runnable() {
            @Override
            public void run() {
                scrollLog.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}

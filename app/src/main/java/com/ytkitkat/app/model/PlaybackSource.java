package com.ytkitkat.app.model;

import com.ytkitkat.app.network.HttpClient;

import java.io.Serializable;

/**
 * Nguồn phát đã được chọn cho một mức chất lượng cụ thể
 * ("1080p60", "1080p", "720p60", "720p", "480p", "360p", "144p", "Audio Only").
 *
 * Lưu kèm chính xác {@code primaryUserAgent} và {@code secondaryAudioUserAgent} của Client
 * đã giải mã ra URL để truyền vào {@code MediaPlayer.setDataSource(context, uri, headers)}.
 */
public final class PlaybackSource implements Serializable {

    private static final long serialVersionUID = 3L;

    public static final int MODE_HLS = 1;
    public static final int MODE_DUAL_ADAPTIVE = 2;
    public static final int MODE_PROGRESSIVE = 3;
    public static final int MODE_AUDIO_ONLY = 4;

    private final int mode;
    private final String qualityLabel;
    private final String displayLabel;
    private final String primaryUrl;
    private final String secondaryAudioUrl;
    private final String primaryUserAgent;
    private final String secondaryAudioUserAgent;
    private final StreamFormat videoFormat;
    private final StreamFormat audioFormat;
    private final boolean hlsVariantSpecific;

    private PlaybackSource(int mode,
                           String qualityLabel,
                           String displayLabel,
                           String primaryUrl,
                           String secondaryAudioUrl,
                           String primaryUserAgent,
                           String secondaryAudioUserAgent,
                           StreamFormat videoFormat,
                           StreamFormat audioFormat,
                           boolean hlsVariantSpecific) {
        this.mode = mode;
        this.qualityLabel = qualityLabel != null ? qualityLabel : "";
        this.displayLabel = displayLabel != null && displayLabel.length() > 0
                ? displayLabel
                : ("MPEG-4 " + this.qualityLabel);
        this.primaryUrl = primaryUrl;
        this.secondaryAudioUrl = secondaryAudioUrl;
        this.primaryUserAgent = (primaryUserAgent != null && primaryUserAgent.length() > 0)
                ? primaryUserAgent
                : HttpClient.DEFAULT_USER_AGENT;
        this.secondaryAudioUserAgent = (secondaryAudioUserAgent != null && secondaryAudioUserAgent.length() > 0)
                ? secondaryAudioUserAgent
                : this.primaryUserAgent;
        this.videoFormat = videoFormat;
        this.audioFormat = audioFormat;
        this.hlsVariantSpecific = hlsVariantSpecific;
    }

    /**
     * Nguồn 1: HLS (.m3u8) từ client IOS (kèm User-Agent của iOS).
     */
    public static PlaybackSource forHls(String qualityLabel,
                                        String hlsUrl,
                                        boolean variantSpecific,
                                        String hlsUserAgent) {
        return new PlaybackSource(
                MODE_HLS,
                qualityLabel,
                "MPEG-4 " + qualityLabel,
                hlsUrl,
                null,
                hlsUserAgent,
                null,
                null,
                null,
                variantSpecific
        );
    }

    public static PlaybackSource forHls(String qualityLabel, String hlsUrl, boolean variantSpecific) {
        return forHls(qualityLabel, hlsUrl, variantSpecific, HttpClient.DEFAULT_USER_AGENT);
    }

    /** Nguồn 2: Adaptive tách rời Video H.264 + Audio AAC gốc (itag 140), giữ riêng User-Agent của từng luồng. */
    public static PlaybackSource forDualAdaptive(String qualityLabel,
                                                 StreamFormat videoFormat,
                                                 StreamFormat audioFormat) {
        return new PlaybackSource(
                MODE_DUAL_ADAPTIVE,
                qualityLabel,
                videoFormat.getDisplayLabel(),
                videoFormat.getUrl(),
                audioFormat.getUrl(),
                videoFormat.getUserAgent(),
                audioFormat.getUserAgent(),
                videoFormat,
                audioFormat,
                false
        );
    }

    /** Nguồn 3a: Progressive MP4 có sẵn cả hình và tiếng (itag 18 - 360p). */
    public static PlaybackSource forProgressive(String qualityLabel, StreamFormat progressiveFormat) {
        return new PlaybackSource(
                MODE_PROGRESSIVE,
                qualityLabel,
                progressiveFormat.getDisplayLabel(),
                progressiveFormat.getUrl(),
                null,
                progressiveFormat.getUserAgent(),
                null,
                progressiveFormat,
                null,
                false
        );
    }

    /** Nguồn 3b: Chỉ phát âm thanh (itag 140 m4a Original Audio). */
    public static PlaybackSource forAudioOnly(StreamFormat audioFormat) {
        return new PlaybackSource(
                MODE_AUDIO_ONLY,
                StreamInfo.QUALITY_AUDIO_ONLY,
                audioFormat.getDisplayLabel(),
                audioFormat.getUrl(),
                null,
                audioFormat.getUserAgent(),
                null,
                null,
                audioFormat,
                false
        );
    }

    public int getMode() {
        return mode;
    }

    public String getQualityLabel() {
        return qualityLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public String getPrimaryUrl() {
        return primaryUrl;
    }

    public String getSecondaryAudioUrl() {
        return secondaryAudioUrl;
    }

    public String getPrimaryUserAgent() {
        return primaryUserAgent;
    }

    public String getSecondaryAudioUserAgent() {
        return secondaryAudioUserAgent;
    }

    public StreamFormat getVideoFormat() {
        return videoFormat;
    }

    public StreamFormat getAudioFormat() {
        return audioFormat;
    }

    public boolean isDualPlayerRequired() {
        return mode == MODE_DUAL_ADAPTIVE;
    }

    public boolean isAudioOnly() {
        return mode == MODE_AUDIO_ONLY;
    }

    public boolean isHls() {
        return mode == MODE_HLS;
    }

    public boolean isHlsVariantSpecific() {
        return hlsVariantSpecific;
    }

    public String getModeDescription() {
        switch (mode) {
            case MODE_HLS:
                return hlsVariantSpecific
                        ? displayLabel + " [HLS Variant .m3u8 con - Mux sẵn H.264+AAC]"
                        : displayLabel + " [HLS Master .m3u8 - Single MediaPlayer]";
            case MODE_DUAL_ADAPTIVE:
                return displayLabel + " [Dual Adaptive H.264 + AAC Original]";
            case MODE_PROGRESSIVE:
                return displayLabel + " [Progressive MP4 - Single MediaPlayer]";
            case MODE_AUDIO_ONLY:
                return displayLabel + " [Audio-Only AAC - Single MediaPlayer]";
            default:
                return displayLabel;
        }
    }
}

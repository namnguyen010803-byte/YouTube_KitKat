package com.ytkitkat.app.network;

import android.util.Log;
import android.util.LruCache;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.model.StreamFormat;
import com.ytkitkat.app.model.StreamInfo;
import com.ytkitkat.app.util.TextFormat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ bóc tách luồng phát video đa nguồn chuẩn NewPipeExtractor / BravePipe / yt-dlp
 * tối ưu cho Android 4.4.4 (Sharp Aquos 305SH):
 *
 * 1. Tự động trích xuất và đính kèm {@code visitorData} vào {@code context.client}
 *    kèm {@code "contentCheckOk": true, "racyCheckOk": true} để vượt qua anti-bot check
 *    ("Đăng nhập để xác nhận bạn không phải là robot").
 *
 * 2. Chuỗi 4 Client InnerTube với {@code User-Agent} và {@code X-YouTube-Client-Name} đồng bộ tuyệt đối:
 *    - Client 1 ({@code IOS} v19.45.4, iOS 18.1.0): Lấy {@code hlsManifestUrl} (.m3u8) và tách các
 *      URL {@code .m3u8} con cho {@code 1080p60}, {@code 720p60}, {@code 720p}, {@code 480p}, {@code 360p}.
 *    - Client 2 ({@code ANDROID_VR} v1.56.21, Oculus Quest 3): Lấy {@code adaptiveFormats} H.264 60fps/30fps
 *      ({@code itag 299/137/298/136/135/134/160}) + Audio gốc {@code itag 140} + Progressive {@code itag 18}.
 *    - Client 3 ({@code ANDROID_TESTSUITE} v1.9, {@code params: "2AMB"}): Client dự phòng cực mạnh
 *      không bị dính signatureCipher và ít bị chặn bot nhất.
 *    - Client 4 ({@code ANDROID} v19.44.38, {@code params: "CgIQBg=="}): Client Android tiêu chuẩn.
 *
 * 3. Chuỗi Fallback Invidious / Piped đang hoạt động nếu mạng chặn IP YouTube:
 *    - {@code https://inv.nadeko.net/api/v1/videos/{videoId}}
 *    - {@code https://invidious.nerdvpn.de/api/v1/videos/{videoId}}
 *    - {@code https://pipedapi.adminforge.de/streams/{videoId}}
 *
 * 4. Mỗi {@link StreamFormat} và luồng HLS đều lưu chính xác {@code User-Agent} của Client đã tạo ra nó
 *    để {@code DualMediaPlayerController} truyền vào {@code MediaPlayer.setDataSource(context, uri, headers)},
 *    tránh lỗi {@code HTTP 403 Forbidden} từ CDN {@code googlevideo.com}.
 */
public final class StreamResolver {

    private static final String TAG = "StreamResolver";

    // ---------------- Client 1: IOS (v20.10.4 - Sửa triệt để lỗi HTTP 400 Precondition check failed) ----------------
    public static final String IOS_CLIENT_NAME = "IOS";
    private static final String IOS_CLIENT_ID = "5";
    private static final String IOS_CLIENT_VERSION = "20.10.4";
    private static final String IOS_DEVICE_MAKE = "Apple";
    private static final String IOS_DEVICE_MODEL = "iPhone16,2";
    private static final String IOS_OS_NAME = "iPhone";
    private static final String IOS_OS_VERSION = "18.3.2.22D82";
    public static final String IOS_USER_AGENT =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";

    // ---------------- Client 2: ANDROID_VR (Chuẩn Oculus Quest 3 vượt bot check) ----------------
    public static final String VR_CLIENT_NAME = "ANDROID_VR";
    private static final String VR_CLIENT_ID = "28";
    private static final String VR_CLIENT_VERSION = "1.56.21";
    private static final String VR_DEVICE_MAKE = "Oculus";
    private static final String VR_DEVICE_MODEL = "Quest 3";
    private static final String VR_OS_NAME = "Android";
    private static final String VR_OS_VERSION = "12L";
    private static final int VR_ANDROID_SDK = 32;
    public static final String VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    // ---------------- Client 3: ANDROID (v20.10.38 - Progressive 360p + Adaptive 1080p/720p/M4A ổn định) ----------------
    public static final String ANDROID_CLIENT_NAME = "ANDROID";
    private static final String ANDROID_CLIENT_ID = "3";
    private static final String ANDROID_CLIENT_VERSION = "20.10.38";
    private static final String ANDROID_OS_NAME = "Android";
    private static final String ANDROID_OS_VERSION = "14";
    private static final int ANDROID_ANDROID_SDK = 34;
    public static final String ANDROID_USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";

    // ---------------- Client 4: ANDROID_TESTSUITE (Dự phòng) ----------------
    public static final String TESTSUITE_CLIENT_NAME = "ANDROID_TESTSUITE";
    private static final String TESTSUITE_CLIENT_ID = "30";
    private static final String TESTSUITE_CLIENT_VERSION = "1.9";
    private static final String TESTSUITE_OS_NAME = "Android";
    private static final String TESTSUITE_OS_VERSION = "12";
    private static final int TESTSUITE_ANDROID_SDK = 32;
    private static final String TESTSUITE_PARAMS = "2AMB";
    public static final String TESTSUITE_USER_AGENT =
            "com.google.android.youtube/1.9 (Linux; U; Android 12; US) gzip";

    private static final Pattern EXPIRE_QUERY_PATTERN = Pattern.compile("[?&]expire=(\\d+)");
    private static final Pattern EXPIRE_PATH_PATTERN = Pattern.compile("/expire/(\\d+)");

    // Regex phân tích thẻ #EXT-X-STREAM-INF trong master .m3u8
    private static final Pattern M3U8_RESOLUTION_PATTERN =
            Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_FRAMERATE_PATTERN =
            Pattern.compile("FRAME-RATE=([0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_BANDWIDTH_PATTERN =
            Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_CODECS_PATTERN =
            Pattern.compile("CODECS=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_ITAG_PATTERN =
            Pattern.compile("/itag/(\\d+)/");

    private static final StreamResolver INSTANCE = new StreamResolver();

    private final LruCache<String, StreamInfo> streamCache =
            new LruCache<String, StreamInfo>(Config.STREAM_CACHE_SIZE);

    private StreamResolver() {
    }

    public static StreamResolver getInstance() {
        return INSTANCE;
    }

    public void clearCache() {
        synchronized (streamCache) {
            streamCache.evictAll();
        }
    }

    // ------------------------------------------------------------------
    // 1. Quản lý & Tự động lấy visitorData chính thống qua /youtubei/v1/visitor_id
    // ------------------------------------------------------------------

    /**
     * Đảm bảo đã có {@code visitorData} hợp lệ trong {@link Config#getVisitorData()}
     * trước khi gọi {@code /youtubei/v1/player}.
     */
    public String ensureVisitorData() {
        String existing = Config.getVisitorData();
        if (existing != null && existing.length() > 0) {
            return existing;
        }
        return fetchOfficialVisitorData();
    }

    /**
     * Tương thích ngược: chuyển tiếp sang {@link #fetchOfficialVisitorData()}.
     */
    public synchronized String fetchFreshVisitorData() {
        return fetchOfficialVisitorData();
    }

    /**
     * Lấy {@code visitorData} chính thống từ API tạo phiên của YouTube (chuẩn NewPipeExtractor):
     * {@code POST https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false}
     * với định danh {@code ANDROID_VR} (Oculus Quest 3, v1.56.21, hl=en, gl=US),
     * chỉ gửi đúng {@code User-Agent} và {@code Content-Type: application/json} (không gửi Origin/Referer).
     */
    public synchronized String fetchOfficialVisitorData() {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", VR_CLIENT_NAME);
            client.put("clientVersion", VR_CLIENT_VERSION);
            client.put("deviceMake", VR_DEVICE_MAKE);
            client.put("deviceModel", VR_DEVICE_MODEL);
            client.put("androidSdkVersion", VR_ANDROID_SDK);
            client.put("osName", VR_OS_NAME);
            client.put("osVersion", VR_OS_VERSION);
            client.put("hl", "en");
            client.put("gl", "US");

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject body = new JSONObject();
            body.put("context", context);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", VR_USER_AGENT);
            headers.put("Content-Type", "application/json");

            HttpResponse resp = HttpClient.postJson(
                    Config.INNERTUBE_VISITOR_ID_URL,
                    body.toString(),
                    headers
            );
            JSONObject json = resp.asJsonObject();
            JSONObject rc = json.optJSONObject("responseContext");
            if (rc != null) {
                String vd = rc.optString("visitorData", "").trim();
                if (vd.length() > 0) {
                    Config.setVisitorData(vd);
                    if (Config.DEBUG_LOG) {
                        Log.d(TAG, "Lấy visitorData chính thống thành công từ /visitor_id: " + vd);
                    }
                    return vd;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Không lấy được visitorData từ /visitor_id (ANDROID_VR): " + e.getMessage());
        }

        // Dự phòng: gọi /visitor_id bằng client ANDROID tiêu chuẩn nếu ANDROID_VR gặp lỗi mạng tạm thời
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", ANDROID_CLIENT_NAME);
            client.put("clientVersion", ANDROID_CLIENT_VERSION);
            client.put("androidSdkVersion", ANDROID_ANDROID_SDK);
            client.put("osName", ANDROID_OS_NAME);
            client.put("osVersion", ANDROID_OS_VERSION);
            client.put("hl", "en");
            client.put("gl", "US");

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject body = new JSONObject();
            body.put("context", context);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", ANDROID_USER_AGENT);
            headers.put("Content-Type", "application/json");

            HttpResponse resp = HttpClient.postJson(
                    Config.INNERTUBE_VISITOR_ID_URL,
                    body.toString(),
                    headers
            );
            JSONObject json = resp.asJsonObject();
            JSONObject rc = json.optJSONObject("responseContext");
            if (rc != null) {
                String vd = rc.optString("visitorData", "").trim();
                if (vd.length() > 0) {
                    Config.setVisitorData(vd);
                    return vd;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Không lấy được visitorData dự phòng từ /visitor_id (ANDROID): " + e.getMessage());
        }

        return Config.getVisitorData();
    }

    // ------------------------------------------------------------------
    // 2. API công khai giải mã luồng
    // ------------------------------------------------------------------

    public RequestHandle resolveAsync(final String videoId,
                                      final boolean forceRefresh,
                                      ApiCallback<StreamInfo> callback) {
        return NetworkExecutor.get().execute(new Callable<StreamInfo>() {
            @Override
            public StreamInfo call() throws Exception {
                return resolveSync(videoId, forceRefresh);
            }
        }, callback);
    }

    public StreamInfo resolveSync(String videoId, boolean forceRefresh) throws IOException {
        if (videoId == null || videoId.trim().length() == 0) {
            throw new IOException("videoId không hợp lệ");
        }
        final String cleanId = videoId.trim();

        if (!forceRefresh) {
            StreamInfo cached;
            synchronized (streamCache) {
                cached = streamCache.get(cleanId);
            }
            if (cached != null && !cached.isExpired(Config.STREAM_EXPIRY_SAFETY_MARGIN_MS)) {
                return cached;
            }
        }

        // Bước 1: Đảm bảo đã có visitorData chính thống từ /youtubei/v1/visitor_id
        String visitorData = ensureVisitorData();

        List<ClientFetchResult> results = new ArrayList<ClientFetchResult>();

        // Bước 2: Thử Client 1 (IOS v20.10.4) để lấy HLS .m3u8 (1080p60 / 720p60) + Adaptive H.264/AAC
        ClientFetchResult iosResult = fetchInnerTubeIos(cleanId, visitorData);
        results.add(iosResult);

        // Nếu IOS bị dính bot check -> làm mới visitorData chính thống ngay
        if (!iosResult.isOk() && isBotCheckError(iosResult.error)) {
            String refreshed = fetchOfficialVisitorData();
            if (refreshed != null && !refreshed.equals(visitorData)) {
                visitorData = refreshed;
                iosResult = fetchInnerTubeIos(cleanId, visitorData);
                results.set(0, iosResult);
            }
        }

        // Bước 3: Thử Client 2 (ANDROID_VR v1.56.21) để lấy Adaptive H.264 60fps (itag 299/298/137/136) + AAC Original (itag 140) + Progressive 360p (itag 18)
        ClientFetchResult vrResult = fetchInnerTubeAndroidVr(cleanId, visitorData);
        results.add(vrResult);

        // Nếu ANDROID_VR chưa lấy được Progressive (itag 18) hoặc bị LOGIN_REQUIRED trên một số MV nhạc -> gọi tiếp Client 3 (ANDROID v20.10.38)
        boolean hasProgressive = !vrResult.progressiveFormats.isEmpty() || !iosResult.progressiveFormats.isEmpty();
        boolean hasAdaptive = (!vrResult.adaptiveAvcVideos.isEmpty() && !vrResult.adaptiveAacAudios.isEmpty())
                || (!iosResult.adaptiveAvcVideos.isEmpty() && !iosResult.adaptiveAacAudios.isEmpty());

        if (!hasProgressive || !hasAdaptive) {
            ClientFetchResult androidResult = fetchInnerTubeAndroidStandard(cleanId, visitorData);
            results.add(androidResult);
            hasProgressive = hasProgressive || !androidResult.progressiveFormats.isEmpty();
            hasAdaptive = hasAdaptive || (!androidResult.adaptiveAvcVideos.isEmpty() && !androidResult.adaptiveAacAudios.isEmpty());
        }

        // Bước 5: Dự phòng thêm ANDROID_TESTSUITE nếu cả 3 client trên chưa có luồng trực tiếp
        if (!hasProgressive && !hasAdaptive && iosResult.hlsManifestUrl == null) {
            ClientFetchResult testSuiteResult = fetchInnerTubeAndroidTestSuite(cleanId, visitorData);
            results.add(testSuiteResult);
        }

        StreamInfo merged = mergeClientResults(cleanId, results);
        if (merged != null && merged.hasPlayableStreams()) {
            synchronized (streamCache) {
                streamCache.put(cleanId, merged);
            }
            return merged;
        }

        // Bước 6: Nếu cả 4 Client InnerTube đều thất bại -> thử chuỗi Invidious / Piped còn sống
        if (Config.ENABLE_PIPED_FALLBACK) {
            try {
                StreamInfo fallbackInfo = fetchFromInvidiousAndPipedFallbacks(cleanId);
                if (fallbackInfo != null && fallbackInfo.hasPlayableStreams()) {
                    synchronized (streamCache) {
                        streamCache.put(cleanId, fallbackInfo);
                    }
                    return fallbackInfo;
                }
            } catch (IOException fallbackErr) {
                Log.w(TAG, "Chuỗi Invidious/Piped fallback thất bại: " + fallbackErr.getMessage());
            }
        }

        StringBuilder errMsg = new StringBuilder("Không bóc tách được luồng phát cho video ")
                .append(cleanId).append('.');
        for (int i = 0; i < results.size(); i++) {
            ClientFetchResult r = results.get(i);
            if (r != null && r.error != null) {
                errMsg.append(" [").append(r.clientName).append(": ").append(r.error).append(']');
            }
        }
        throw new IOException(errMsg.toString());
    }

    private static boolean isBotCheckError(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("robot") || lower.contains("sign in") || lower.contains("đăng nhập");
    }

    // ------------------------------------------------------------------
    // 3. Cấu hình chi tiết 4 Client InnerTube (IOS, ANDROID_VR, ANDROID, ANDROID_TESTSUITE)
    //    Tuyệt đối KHÔNG gửi header Origin hay Referer của trình duyệt web!
    // ------------------------------------------------------------------

    /**
     * Client 1: {@code IOS} (v20.10.4, iPhone16,2, iOS 18.3.2.22D82).
     * Không gửi {@code Origin}/{@code Referer} để sửa triệt để lỗi {@code HTTP 400 Precondition check failed}.
     */
    private ClientFetchResult fetchInnerTubeIos(String videoId, String visitorData) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", IOS_CLIENT_NAME);
            client.put("clientVersion", IOS_CLIENT_VERSION);
            client.put("deviceMake", IOS_DEVICE_MAKE);
            client.put("deviceModel", IOS_DEVICE_MODEL);
            client.put("osName", IOS_OS_NAME);
            client.put("osVersion", IOS_OS_VERSION);
            client.put("hl", "en");
            client.put("gl", "US");
            if (visitorData != null && visitorData.length() > 0) {
                client.put("visitorData", visitorData);
            }

            JSONObject payload = buildPlayerPayload(videoId, client, null);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", IOS_USER_AGENT);
            headers.put("Content-Type", "application/json");
            headers.put("X-YouTube-Client-Name", IOS_CLIENT_ID);
            headers.put("X-YouTube-Client-Version", IOS_CLIENT_VERSION);
            if (visitorData != null && visitorData.length() > 0) {
                headers.put("X-Goog-Visitor-Id", visitorData);
            }

            HttpResponse response = HttpClient.postJson(
                    Config.INNERTUBE_PLAYER_URL,
                    payload.toString(),
                    headers
            );
            ClientFetchResult result = parseInnerTubeResponse(
                    response.asJsonObject(),
                    IOS_CLIENT_NAME,
                    IOS_USER_AGENT
            );
            if (result.hlsManifestUrl != null && result.hlsManifestUrl.length() > 0) {
                result.hlsVariantUrls.putAll(
                        fetchAndParseHlsMasterPlaylist(result.hlsManifestUrl, IOS_USER_AGENT)
                );
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Client IOS lỗi: " + e.getMessage());
            return ClientFetchResult.failure(IOS_CLIENT_NAME, IOS_USER_AGENT, e.getMessage());
        }
    }

    /**
     * Client 2: {@code ANDROID_VR} (v1.56.21, Oculus Quest 3, Android 12L SDK 32).
     * Không gửi {@code Origin}/{@code Referer}, đồng bộ hoàn toàn với {@code visitorData} từ {@code /visitor_id}.
     */
    private ClientFetchResult fetchInnerTubeAndroidVr(String videoId, String visitorData) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", VR_CLIENT_NAME);
            client.put("clientVersion", VR_CLIENT_VERSION);
            client.put("deviceMake", VR_DEVICE_MAKE);
            client.put("deviceModel", VR_DEVICE_MODEL);
            client.put("androidSdkVersion", VR_ANDROID_SDK);
            client.put("osName", VR_OS_NAME);
            client.put("osVersion", VR_OS_VERSION);
            client.put("hl", "en");
            client.put("gl", "US");
            if (visitorData != null && visitorData.length() > 0) {
                client.put("visitorData", visitorData);
            }

            JSONObject payload = buildPlayerPayload(videoId, client, null);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", VR_USER_AGENT);
            headers.put("Content-Type", "application/json");
            headers.put("X-YouTube-Client-Name", VR_CLIENT_ID);
            headers.put("X-YouTube-Client-Version", VR_CLIENT_VERSION);
            if (visitorData != null && visitorData.length() > 0) {
                headers.put("X-Goog-Visitor-Id", visitorData);
            }

            HttpResponse response = HttpClient.postJson(
                    Config.INNERTUBE_PLAYER_URL,
                    payload.toString(),
                    headers
            );
            return parseInnerTubeResponse(
                    response.asJsonObject(),
                    VR_CLIENT_NAME,
                    VR_USER_AGENT
            );
        } catch (Exception e) {
            Log.w(TAG, "Client ANDROID_VR lỗi: " + e.getMessage());
            return ClientFetchResult.failure(VR_CLIENT_NAME, VR_USER_AGENT, e.getMessage());
        }
    }

    /**
     * Client 3: {@code ANDROID} (v20.10.38, SDK 34, Android 14).
     * Trả về cả Progressive 360p ({@code itag 18}) lẫn Adaptive 1080p/720p/M4A không bị chặn trên các MV âm nhạc.
     */
    private ClientFetchResult fetchInnerTubeAndroidStandard(String videoId, String visitorData) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", ANDROID_CLIENT_NAME);
            client.put("clientVersion", ANDROID_CLIENT_VERSION);
            client.put("androidSdkVersion", ANDROID_ANDROID_SDK);
            client.put("osName", ANDROID_OS_NAME);
            client.put("osVersion", ANDROID_OS_VERSION);
            client.put("hl", "en");
            client.put("gl", "US");
            if (visitorData != null && visitorData.length() > 0) {
                client.put("visitorData", visitorData);
            }

            JSONObject payload = buildPlayerPayload(videoId, client, null);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", ANDROID_USER_AGENT);
            headers.put("Content-Type", "application/json");
            headers.put("X-YouTube-Client-Name", ANDROID_CLIENT_ID);
            headers.put("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION);
            if (visitorData != null && visitorData.length() > 0) {
                headers.put("X-Goog-Visitor-Id", visitorData);
            }

            HttpResponse response = HttpClient.postJson(
                    Config.INNERTUBE_PLAYER_URL,
                    payload.toString(),
                    headers
            );
            return parseInnerTubeResponse(
                    response.asJsonObject(),
                    ANDROID_CLIENT_NAME,
                    ANDROID_USER_AGENT
            );
        } catch (Exception e) {
            Log.w(TAG, "Client ANDROID lỗi: " + e.getMessage());
            return ClientFetchResult.failure(ANDROID_CLIENT_NAME, ANDROID_USER_AGENT, e.getMessage());
        }
    }

    /**
     * Client 4: {@code ANDROID_TESTSUITE} (v1.9, SDK 32, {@code params: "2AMB"}).
     */
    private ClientFetchResult fetchInnerTubeAndroidTestSuite(String videoId, String visitorData) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", TESTSUITE_CLIENT_NAME);
            client.put("clientVersion", TESTSUITE_CLIENT_VERSION);
            client.put("androidSdkVersion", TESTSUITE_ANDROID_SDK);
            client.put("osName", TESTSUITE_OS_NAME);
            client.put("osVersion", TESTSUITE_OS_VERSION);
            client.put("hl", "en");
            client.put("gl", "US");
            if (visitorData != null && visitorData.length() > 0) {
                client.put("visitorData", visitorData);
            }

            JSONObject payload = buildPlayerPayload(videoId, client, TESTSUITE_PARAMS);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", TESTSUITE_USER_AGENT);
            headers.put("Content-Type", "application/json");
            headers.put("X-YouTube-Client-Name", TESTSUITE_CLIENT_ID);
            headers.put("X-YouTube-Client-Version", TESTSUITE_CLIENT_VERSION);
            if (visitorData != null && visitorData.length() > 0) {
                headers.put("X-Goog-Visitor-Id", visitorData);
            }

            HttpResponse response = HttpClient.postJson(
                    Config.INNERTUBE_PLAYER_URL,
                    payload.toString(),
                    headers
            );
            return parseInnerTubeResponse(
                    response.asJsonObject(),
                    TESTSUITE_CLIENT_NAME,
                    TESTSUITE_USER_AGENT
            );
        } catch (Exception e) {
            Log.w(TAG, "Client ANDROID_TESTSUITE lỗi: " + e.getMessage());
            return ClientFetchResult.failure(TESTSUITE_CLIENT_NAME, TESTSUITE_USER_AGENT, e.getMessage());
        }
    }

    private static JSONObject buildPlayerPayload(String videoId, JSONObject clientObj, String params)
            throws JSONException {
        JSONObject context = new JSONObject();
        context.put("client", clientObj);

        JSONObject root = new JSONObject();
        root.put("context", context);
        root.put("videoId", videoId);
        root.put("contentCheckOk", true);
        root.put("racyCheckOk", true);
        if (params != null && params.length() > 0) {
            root.put("params", params);
        }
        return root;
    }

    private ClientFetchResult parseInnerTubeResponse(JSONObject root,
                                                     String clientName,
                                                     String clientUserAgent) {
        // Lưu lại visitorData mới nếu server trả về trong responseContext
        JSONObject responseContext = root.optJSONObject("responseContext");
        if (responseContext != null) {
            String vd = responseContext.optString("visitorData", "").trim();
            if (vd.length() > 0) {
                Config.setVisitorData(vd);
            }
        }

        JSONObject playability = root.optJSONObject("playabilityStatus");
        String status = playability != null ? playability.optString("status", "") : "";
        if (!"OK".equalsIgnoreCase(status)) {
            String reason = playability != null
                    ? playability.optString("reason", status.length() > 0 ? status : "UNPLAYABLE")
                    : "Thiếu playabilityStatus";
            return ClientFetchResult.failure(clientName, clientUserAgent, reason);
        }

        JSONObject streamingData = root.optJSONObject("streamingData");
        if (streamingData == null) {
            return ClientFetchResult.failure(clientName, clientUserAgent, "Thiếu streamingData");
        }

        ClientFetchResult res = new ClientFetchResult(clientName, clientUserAgent);

        JSONObject details = root.optJSONObject("videoDetails");
        if (details != null) {
            res.title = details.optString("title", "");
            res.author = details.optString("author", "");
            res.channelId = details.optString("channelId", "");
            res.durationSeconds = TextFormat.parseLongSafe(details.optString("lengthSeconds", ""), 0L);
            res.viewCount = TextFormat.parseLongSafe(details.optString("viewCount", ""), -1L);
            res.liveContent = details.optBoolean("isLiveContent", false);
            JSONObject thumbRoot = details.optJSONObject("thumbnail");
            JSONArray thumbs = thumbRoot != null ? thumbRoot.optJSONArray("thumbnails") : null;
            if (thumbs != null && thumbs.length() > 0) {
                JSONObject last = thumbs.optJSONObject(thumbs.length() - 1);
                if (last != null) {
                    res.thumbnailUrl = last.optString("url", null);
                }
            }
        }

        res.expiresInSeconds = TextFormat.parseLongSafe(
                streamingData.optString("expiresInSeconds", ""), 0L);

        // Nguồn 1: HLS Manifest URL (.m3u8)
        String hls = streamingData.optString("hlsManifestUrl", "");
        if (hls.length() > 0) {
            res.hlsManifestUrl = hls;
        }

        // Nguồn 3: Progressive formats (itag 18 - 360p, itag 22 - 720p)
        JSONArray formats = streamingData.optJSONArray("formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                StreamFormat fmt = StreamFormat.fromInnerTubeJson(
                        formats.optJSONObject(i),
                        false,
                        clientName,
                        clientUserAgent
                );
                if (fmt != null && fmt.isAvcMp4Video()) {
                    res.progressiveFormats.add(fmt);
                }
            }
        }

        // Nguồn 2: Adaptive formats (tách riêng H.264 Video & AAC Audio)
        JSONArray adaptive = streamingData.optJSONArray("adaptiveFormats");
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                StreamFormat fmt = StreamFormat.fromInnerTubeJson(
                        adaptive.optJSONObject(i),
                        true,
                        clientName,
                        clientUserAgent
                );
                if (fmt == null) {
                    continue;
                }
                if (fmt.isAvcMp4Video()) {
                    res.adaptiveAvcVideos.add(fmt);
                } else if (fmt.isAacMp4Audio()) {
                    res.adaptiveAacAudios.add(fmt);
                }
            }
        }

        return res;
    }

    // ------------------------------------------------------------------
    // 4. Phân tích Master Playlist HLS (.m3u8) -> tách các .m3u8 con theo chất lượng
    // ------------------------------------------------------------------

    public static Map<String, String> fetchAndParseHlsMasterPlaylist(String masterUrl, String userAgent) {
        if (masterUrl == null || masterUrl.length() == 0) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            if (userAgent != null && userAgent.length() > 0) {
                headers.put("User-Agent", userAgent);
            }
            HttpResponse resp = HttpClient.get(masterUrl, headers);
            if (!resp.isSuccessful()) {
                return Collections.emptyMap();
            }
            return parseHlsMasterPlaylistText(masterUrl, resp.getBody());
        } catch (Exception e) {
            Log.w(TAG, "Không tải/phân tích được master .m3u8: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    public static Map<String, String> parseHlsMasterPlaylistText(String masterUrl, String content) {
        Map<String, String> bestUrlByQuality = new LinkedHashMap<String, String>();
        Map<String, Long> bestBandwidthByQuality = new LinkedHashMap<String, Long>();

        if (content == null || content.length() == 0) {
            return bestUrlByQuality;
        }

        String[] lines = content.split("\\r?\\n");
        String pendingStreamInf = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingStreamInf = line.substring("#EXT-X-STREAM-INF:".length());
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (pendingStreamInf != null) {
                String attr = pendingStreamInf;
                pendingStreamInf = null;

                Matcher codecsMatcher = M3U8_CODECS_PATTERN.matcher(attr);
                if (codecsMatcher.find()) {
                    String codecs = codecsMatcher.group(1).toLowerCase();
                    if (codecs.contains("vp09") || codecs.contains("vp9")
                            || codecs.contains("av01") || codecs.contains("hev1")
                            || codecs.contains("hvc1")) {
                        continue;
                    }
                }

                int width = 0;
                int height = 0;
                Matcher resMatcher = M3U8_RESOLUTION_PATTERN.matcher(attr);
                if (resMatcher.find()) {
                    width = TextFormat.parseIntSafe(resMatcher.group(1), 0);
                    height = TextFormat.parseIntSafe(resMatcher.group(2), 0);
                }

                int fps = 30;
                Matcher fpsMatcher = M3U8_FRAMERATE_PATTERN.matcher(attr);
                if (fpsMatcher.find()) {
                    try {
                        fps = (int) Math.round(Double.parseDouble(fpsMatcher.group(1)));
                    } catch (NumberFormatException ignored) {
                        fps = 30;
                    }
                }

                long bandwidth = 0L;
                Matcher bwMatcher = M3U8_BANDWIDTH_PATTERN.matcher(attr);
                if (bwMatcher.find()) {
                    bandwidth = TextFormat.parseLongSafe(bwMatcher.group(1), 0L);
                }

                int itag = -1;
                Matcher itagMatcher = M3U8_ITAG_PATTERN.matcher(line);
                if (itagMatcher.find()) {
                    itag = TextFormat.parseIntSafe(itagMatcher.group(1), -1);
                }

                int canonicalHeight = normalizeDimensionToHeight(width, height, itag);
                if (canonicalHeight <= 0) {
                    continue;
                }

                String qualityKey = StreamFormat.normalizeVideoQualityLabel("", canonicalHeight, fps, itag);
                if (qualityKey.length() == 0) {
                    continue;
                }

                String resolvedVariantUrl = resolveRelativeUrl(masterUrl, line);
                Long existingBw = bestBandwidthByQuality.get(qualityKey);
                if (existingBw == null || bandwidth > existingBw.longValue()) {
                    bestBandwidthByQuality.put(qualityKey, Long.valueOf(bandwidth));
                    bestUrlByQuality.put(qualityKey, resolvedVariantUrl);
                }
            }
        }

        Map<String, String> ordered = new LinkedHashMap<String, String>();
        for (String q : StreamInfo.ALL_QUALITIES_ORDERED) {
            String u = bestUrlByQuality.get(q);
            if (u != null) {
                ordered.put(q, u);
            }
        }
        for (Map.Entry<String, String> entry : bestUrlByQuality.entrySet()) {
            if (!ordered.containsKey(entry.getKey())) {
                ordered.put(entry.getKey(), entry.getValue());
            }
        }
        return ordered;
    }

    private static int normalizeDimensionToHeight(int width, int height, int itag) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int shortSide = Math.min(width, height);
        if (shortSide >= 960) {
            return 1080;
        }
        if (shortSide >= 640) {
            return 720;
        }
        if (shortSide >= 420) {
            return 480;
        }
        if (shortSide >= 300) {
            return 360;
        }
        if (shortSide >= 200) {
            return 240;
        }
        return 144;
    }

    private static String resolveRelativeUrl(String baseUrl, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return ref;
        }
        try {
            return URI.create(baseUrl).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    // ------------------------------------------------------------------
    // 5. Hợp nhất kết quả từ chuỗi 4 Client InnerTube
    // ------------------------------------------------------------------

    private StreamInfo mergeClientResults(String videoId, List<ClientFetchResult> results) {
        String title = "";
        String author = "";
        String channelId = "";
        long duration = 0L;
        long views = -1L;
        boolean live = false;
        String thumbnail = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        for (int i = 0; i < results.size(); i++) {
            ClientFetchResult r = results.get(i);
            if (r != null && r.title != null && r.title.length() > 0) {
                title = r.title;
                author = r.author != null ? r.author : "";
                channelId = r.channelId != null ? r.channelId : "";
                duration = r.durationSeconds;
                views = r.viewCount;
                live = r.liveContent;
                if (r.thumbnailUrl != null) {
                    thumbnail = r.thumbnailUrl;
                }
                break;
            }
        }

        String hlsManifestUrl = null;
        String hlsUserAgent = IOS_USER_AGENT;
        Map<String, String> hlsVariantUrls = new LinkedHashMap<String, String>();

        List<StreamFormat> adaptiveVideos = new ArrayList<StreamFormat>();
        List<StreamFormat> adaptiveAudios = new ArrayList<StreamFormat>();
        List<StreamFormat> progressive = new ArrayList<StreamFormat>();

        StringBuilder summary = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {
            ClientFetchResult r = results.get(i);
            if (r == null) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(r.clientName).append('=').append(r.isOk() ? "OK" : r.error);

            if (hlsManifestUrl == null && r.hlsManifestUrl != null) {
                hlsManifestUrl = r.hlsManifestUrl;
                hlsUserAgent = r.userAgent;
                hlsVariantUrls.putAll(r.hlsVariantUrls);
            }

            addUniqueVideoFormats(adaptiveVideos, r.adaptiveAvcVideos);
            addUniqueAudioFormats(adaptiveAudios, r.adaptiveAacAudios);
            addUniqueVideoFormats(progressive, r.progressiveFormats);
        }

        sortVideosByQualityDesc(adaptiveVideos);
        sortAudiosByOriginalPreference(adaptiveAudios);
        sortVideosByQualityDesc(progressive);

        long now = System.currentTimeMillis();
        long expiresAt = computeExpiresAtMillis(
                now, hlsManifestUrl, adaptiveVideos, adaptiveAudios, progressive, results);

        summary.append(" | HLS=").append(hlsManifestUrl != null
                ? ("Có (" + hlsVariantUrls.size() + " .m3u8 con: " + hlsVariantUrls.keySet() + ")")
                : "Không");
        summary.append(" | AVC=").append(adaptiveVideos.size());
        summary.append(" | AAC=").append(adaptiveAudios.size());
        summary.append(" | Prog=").append(progressive.size());

        return new StreamInfo(
                videoId,
                title,
                author,
                channelId,
                duration,
                views,
                live,
                thumbnail,
                hlsManifestUrl,
                hlsUserAgent,
                hlsVariantUrls,
                adaptiveVideos,
                adaptiveAudios,
                progressive,
                now,
                expiresAt,
                summary.toString()
        );
    }

    // ------------------------------------------------------------------
    // 6. Chuỗi Fallback Invidious / Piped đang hoạt động
    // ------------------------------------------------------------------

    private StreamInfo fetchFromInvidiousAndPipedFallbacks(String videoId) throws IOException {
        IOException lastError = null;
        String[] endpoints = Config.FALLBACK_INVIDIOUS_AND_PIPED_ENDPOINTS;

        for (int i = 0; i < endpoints.length; i++) {
            String baseEndpoint = endpoints[i];
            if (baseEndpoint == null || baseEndpoint.length() == 0) {
                continue;
            }
            String fullUrl = baseEndpoint + HttpClient.encode(videoId);
            try {
                JSONObject root = HttpClient.get(fullUrl, null).asJsonObject();
                StreamInfo info;
                if (baseEndpoint.contains("/api/v1/videos/")) {
                    info = parseInvidiousVideoResponse(videoId, baseEndpoint, root);
                } else {
                    info = parsePipedVideoResponse(videoId, baseEndpoint, root);
                }
                if (info != null && info.hasPlayableStreams()) {
                    return info;
                }
            } catch (Exception e) {
                Log.w(TAG, "Fallback endpoint " + baseEndpoint + " lỗi: " + e.getMessage());
                lastError = (e instanceof IOException) ? (IOException) e : new IOException(e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new IOException("Tất cả Invidious/Piped fallback đều thất bại");
    }

    private StreamInfo parseInvidiousVideoResponse(String videoId, String endpointName, JSONObject root) {
        String title = root.optString("title", "");
        String author = root.optString("author", "");
        String channelId = root.optString("authorId", "");
        long duration = root.optLong("lengthSeconds", 0L);
        long views = root.optLong("viewCount", -1L);
        boolean live = root.optBoolean("liveNow", false);
        String thumbnail = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        String hls = root.optString("hlsUrl", null);
        if (hls != null && hls.length() == 0) {
            hls = null;
        }
        Map<String, String> hlsVariants = new LinkedHashMap<String, String>();
        if (hls != null) {
            hlsVariants.putAll(fetchAndParseHlsMasterPlaylist(hls, HttpClient.DEFAULT_USER_AGENT));
        }

        List<StreamFormat> adaptiveVideos = new ArrayList<StreamFormat>();
        List<StreamFormat> adaptiveAudios = new ArrayList<StreamFormat>();
        List<StreamFormat> progressive = new ArrayList<StreamFormat>();

        JSONArray formatStreams = root.optJSONArray("formatStreams");
        if (formatStreams != null) {
            for (int i = 0; i < formatStreams.length(); i++) {
                StreamFormat fmt = StreamFormat.fromInvidiousJson(formatStreams.optJSONObject(i), false);
                if (fmt != null && fmt.isAvcMp4Video()) {
                    progressive.add(fmt);
                }
            }
        }

        JSONArray adaptiveFormats = root.optJSONArray("adaptiveFormats");
        if (adaptiveFormats != null) {
            for (int i = 0; i < adaptiveFormats.length(); i++) {
                StreamFormat fmt = StreamFormat.fromInvidiousJson(adaptiveFormats.optJSONObject(i), true);
                if (fmt == null) {
                    continue;
                }
                if (fmt.isAvcMp4Video()) {
                    adaptiveVideos.add(fmt);
                } else if (fmt.isAacMp4Audio()) {
                    adaptiveAudios.add(fmt);
                }
            }
        }

        sortVideosByQualityDesc(adaptiveVideos);
        sortAudiosByOriginalPreference(adaptiveAudios);
        sortVideosByQualityDesc(progressive);

        long now = System.currentTimeMillis();
        long expiresAt = now + Config.STREAM_CACHE_FALLBACK_TTL_MS;
        String summary = "INVIDIOUS (" + endpointName + ")=OK"
                + " | AVC=" + adaptiveVideos.size()
                + " | AAC=" + adaptiveAudios.size()
                + " | Prog=" + progressive.size();

        return new StreamInfo(
                videoId,
                title,
                author,
                channelId,
                duration,
                views,
                live,
                thumbnail,
                hls,
                HttpClient.DEFAULT_USER_AGENT,
                hlsVariants,
                adaptiveVideos,
                adaptiveAudios,
                progressive,
                now,
                expiresAt,
                summary
        );
    }

    private StreamInfo parsePipedVideoResponse(String videoId, String endpointName, JSONObject root) {
        String title = root.optString("title", "");
        String uploader = root.optString("uploader", "");
        String uploaderUrl = root.optString("uploaderUrl", "");
        String channelId = uploaderUrl.startsWith("/channel/")
                ? uploaderUrl.substring("/channel/".length())
                : "";
        long duration = root.optLong("duration", 0L);
        long views = root.optLong("views", -1L);
        boolean live = root.optBoolean("livestream", false);
        String thumbnail = root.optString("thumbnailUrl",
                "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
        String hls = root.optString("hls", null);
        if (hls != null && hls.length() == 0) {
            hls = null;
        }
        Map<String, String> hlsVariants = new LinkedHashMap<String, String>();
        if (hls != null) {
            hlsVariants.putAll(fetchAndParseHlsMasterPlaylist(hls, HttpClient.DEFAULT_USER_AGENT));
        }

        List<StreamFormat> adaptiveVideos = new ArrayList<StreamFormat>();
        List<StreamFormat> adaptiveAudios = new ArrayList<StreamFormat>();
        List<StreamFormat> progressive = new ArrayList<StreamFormat>();

        JSONArray videoStreams = root.optJSONArray("videoStreams");
        if (videoStreams != null) {
            for (int i = 0; i < videoStreams.length(); i++) {
                JSONObject v = videoStreams.optJSONObject(i);
                if (v == null) {
                    continue;
                }
                boolean videoOnly = v.optBoolean("videoOnly", true);
                StreamFormat fmt = StreamFormat.fromPipedJson(v, false, videoOnly);
                if (fmt != null && fmt.isAvcMp4Video()) {
                    if (videoOnly) {
                        adaptiveVideos.add(fmt);
                    } else {
                        progressive.add(fmt);
                    }
                }
            }
        }

        JSONArray audioStreams = root.optJSONArray("audioStreams");
        if (audioStreams != null) {
            for (int i = 0; i < audioStreams.length(); i++) {
                JSONObject a = audioStreams.optJSONObject(i);
                StreamFormat fmt = StreamFormat.fromPipedJson(a, true, false);
                if (fmt != null && fmt.isAacMp4Audio()) {
                    adaptiveAudios.add(fmt);
                }
            }
        }

        sortVideosByQualityDesc(adaptiveVideos);
        sortAudiosByOriginalPreference(adaptiveAudios);
        sortVideosByQualityDesc(progressive);

        long now = System.currentTimeMillis();
        long expiresAt = now + Config.STREAM_CACHE_FALLBACK_TTL_MS;
        String summary = "PIPED (" + endpointName + ")=OK"
                + " | AVC=" + adaptiveVideos.size()
                + " | AAC=" + adaptiveAudios.size()
                + " | Prog=" + progressive.size();

        return new StreamInfo(
                videoId,
                title,
                uploader,
                channelId,
                duration,
                views,
                live,
                thumbnail,
                hls,
                HttpClient.DEFAULT_USER_AGENT,
                hlsVariants,
                adaptiveVideos,
                adaptiveAudios,
                progressive,
                now,
                expiresAt,
                summary
        );
    }

    // ------------------------------------------------------------------
    // 7. Tiện ích sắp xếp & lọc âm thanh gốc (Original Audio)
    // ------------------------------------------------------------------

    private static void addUniqueVideoFormats(List<StreamFormat> target, List<StreamFormat> source) {
        if (source == null) {
            return;
        }
        for (StreamFormat candidate : source) {
            boolean exists = false;
            for (StreamFormat existing : target) {
                if (existing.getItag() == candidate.getItag() && candidate.getItag() > 0) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(candidate);
            }
        }
    }

    private static void addUniqueAudioFormats(List<StreamFormat> target, List<StreamFormat> source) {
        if (source == null) {
            return;
        }
        for (StreamFormat candidate : source) {
            int existingIndex = -1;
            for (int i = 0; i < target.size(); i++) {
                StreamFormat existing = target.get(i);
                if (existing.getItag() == candidate.getItag()
                        && existing.getAudioTrackId().equals(candidate.getAudioTrackId())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex < 0) {
                target.add(candidate);
            } else if (candidate.getAudioPriorityScore() > target.get(existingIndex).getAudioPriorityScore()) {
                target.set(existingIndex, candidate);
            }
        }
    }

    private static void sortVideosByQualityDesc(List<StreamFormat> list) {
        Collections.sort(list, new Comparator<StreamFormat>() {
            @Override
            public int compare(StreamFormat a, StreamFormat b) {
                if (b.getHeight() != a.getHeight()) {
                    return b.getHeight() - a.getHeight();
                }
                if (b.getFps() != a.getFps()) {
                    return b.getFps() - a.getFps();
                }
                return Long.valueOf(b.getBitrate()).compareTo(Long.valueOf(a.getBitrate()));
            }
        });
    }

    private static void sortAudiosByOriginalPreference(List<StreamFormat> list) {
        Collections.sort(list, new Comparator<StreamFormat>() {
            @Override
            public int compare(StreamFormat a, StreamFormat b) {
                int scoreA = a.getAudioPriorityScore();
                int scoreB = b.getAudioPriorityScore();
                if (scoreB != scoreA) {
                    return scoreB - scoreA;
                }
                return Long.valueOf(b.getBitrate()).compareTo(Long.valueOf(a.getBitrate()));
            }
        });
    }

    private static long computeExpiresAtMillis(long now,
                                               String hlsUrl,
                                               List<StreamFormat> videos,
                                               List<StreamFormat> audios,
                                               List<StreamFormat> progressive,
                                               List<ClientFetchResult> results) {
        String sampleUrl = hlsUrl;
        if (sampleUrl == null && !videos.isEmpty()) {
            sampleUrl = videos.get(0).getUrl();
        }
        if (sampleUrl == null && !audios.isEmpty()) {
            sampleUrl = audios.get(0).getUrl();
        }
        if (sampleUrl == null && !progressive.isEmpty()) {
            sampleUrl = progressive.get(0).getUrl();
        }
        long fromUrl = extractExpiryMillisFromUrl(sampleUrl);
        if (fromUrl > now) {
            return fromUrl;
        }
        for (int i = 0; i < results.size(); i++) {
            ClientFetchResult r = results.get(i);
            if (r != null && r.expiresInSeconds > 0) {
                return now + r.expiresInSeconds * 1000L;
            }
        }
        return now + Config.STREAM_CACHE_FALLBACK_TTL_MS;
    }

    static long extractExpiryMillisFromUrl(String url) {
        if (url == null) {
            return -1L;
        }
        Matcher m1 = EXPIRE_QUERY_PATTERN.matcher(url);
        if (m1.find()) {
            long sec = TextFormat.parseLongSafe(m1.group(1), -1L);
            if (sec > 0) {
                return sec * 1000L;
            }
        }
        Matcher m2 = EXPIRE_PATH_PATTERN.matcher(url);
        if (m2.find()) {
            long sec = TextFormat.parseLongSafe(m2.group(1), -1L);
            if (sec > 0) {
                return sec * 1000L;
            }
        }
        return -1L;
    }

    // ------------------------------------------------------------------
    // Cấu trúc kết quả nội bộ của mỗi client
    // ------------------------------------------------------------------

    private static final class ClientFetchResult {
        final String clientName;
        final String userAgent;
        String error;
        String title;
        String author;
        String channelId;
        long durationSeconds;
        long viewCount = -1L;
        boolean liveContent;
        String thumbnailUrl;
        long expiresInSeconds;
        String hlsManifestUrl;
        final Map<String, String> hlsVariantUrls = new LinkedHashMap<String, String>();
        final List<StreamFormat> adaptiveAvcVideos = new ArrayList<StreamFormat>();
        final List<StreamFormat> adaptiveAacAudios = new ArrayList<StreamFormat>();
        final List<StreamFormat> progressiveFormats = new ArrayList<StreamFormat>();

        ClientFetchResult(String clientName, String userAgent) {
            this.clientName = clientName;
            this.userAgent = userAgent;
        }

        static ClientFetchResult failure(String clientName, String userAgent, String error) {
            ClientFetchResult r = new ClientFetchResult(clientName, userAgent);
            r.error = error != null ? error : "Unknown error";
            return r;
        }

        boolean hasDirectStreams() {
            return (!adaptiveAvcVideos.isEmpty() && !adaptiveAacAudios.isEmpty())
                    || !progressiveFormats.isEmpty();
        }

        boolean isOk() {
            return error == null && (hlsManifestUrl != null || hasDirectStreams());
        }
    }
}

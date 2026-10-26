package com.ytkitkat.app.network;

import android.content.Context;
import android.util.Log;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.model.ChannelInfo;
import com.ytkitkat.app.model.CommentItem;
import com.ytkitkat.app.model.PagedResult;
import com.ytkitkat.app.model.VideoCategory;
import com.ytkitkat.app.model.VideoItem;
import com.ytkitkat.app.util.AppSignature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Tầng giao tiếp dữ liệu trung tâm của ứng dụng:
 *
 * 1. Nguồn chính / Fallback số 1 không cần API Key: **YouTube InnerTube API chính chủ**
 *    ({@code https://www.youtube.com/youtubei/v1/search} và {@code /youtubei/v1/browse})
 *    giống cơ chế của BravePipe:
 *    - Tìm kiếm trực tiếp mọi từ khóa (ví dụ {@code "lingyuan yousa"}) qua client {@code WEB}
 *      ({@code clientVersion: "2.20240726.00.00"}, {@code hl: "vi"}, {@code gl: "VN"}).
 *    - Quét đệ quy toàn bộ cây JSON bằng {@link #findJsonObjectsByKey(Object, String, List)}
 *      để lấy danh sách {@code videoRenderer} (đầy đủ {@code videoId}, {@code title}, {@code channelTitle},
 *      {@code channelAvatar}, {@code duration}, {@code viewCount}, {@code publishedAt}, {@code mqdefault.jpg})
 *      và {@code continuationCommand.token} trong {@code continuationItemRenderer} để phân trang vô hạn.
 *    - Trang chủ / Thịnh hành / Chip danh mục hoạt động trực tiếp qua InnerTube mà không phụ thuộc vào
 *      server Piped bên thứ ba.
 *
 * 2. Hỗ trợ song song YouTube Data API v3 nếu người dùng có điền {@code YOUTUBE_API_KEY} hợp lệ
 *    trong {@link Config#YOUTUBE_API_KEY} (tự động chuyển sang InnerTube ngay nếu key trống hoặc hết quota).
 */
public final class YouTubeApi {

    private static final String TAG = "YouTubeApi";

    private static final String INNERTUBE_WEB_CLIENT_NAME = "WEB";
    private static final String INNERTUBE_WEB_CLIENT_VERSION = "2.20240726.00.00";
    private static final String INNERTUBE_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    /** Danh sách mirror Piped dự phòng cuối cùng (chỉ gọi nếu cả Data API v3 lẫn InnerTube đều lỗi). */
    private static final String[] LAST_RESORT_PIPED_MIRRORS = new String[] {
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
            Config.FALLBACK_PIPED_API
    };

    private static final YouTubeApi INSTANCE = new YouTubeApi();

    /** visitorData riêng cho phiên tìm kiếm/duyệt trang WEB, không trộn lẫn với visitorData của Mobile Player. */
    private static volatile String webVisitorData = null;

    private volatile String packageName;
    private volatile String certSha1Hex;

    private YouTubeApi() {
    }

    public static YouTubeApi getInstance() {
        return INSTANCE;
    }

    /**
     * Khởi tạo thông tin chữ ký ứng dụng để đính kèm X-Android-Package & X-Android-Cert
     * khi gọi Google APIs.
     */
    public void init(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        this.packageName = app.getPackageName();
        this.certSha1Hex = AppSignature.getSha1Hex(app);
        if (Config.DEBUG_LOG) {
            Log.d(TAG, "Khởi tạo YouTubeApi cho package=" + packageName
                    + ", SHA1=" + (certSha1Hex != null ? certSha1Hex : "(none)"));
        }
    }

    private Map<String, String> buildGoogleHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        if (packageName != null && packageName.length() > 0) {
            headers.put("X-Android-Package", packageName);
        }
        if (certSha1Hex != null && certSha1Hex.length() > 0) {
            headers.put("X-Android-Cert", certSha1Hex);
        }
        return headers;
    }

    private static Map<String, String> buildInnerTubeWebHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", INNERTUBE_WEB_USER_AGENT);
        headers.put("X-YouTube-Client-Name", "1");
        headers.put("X-YouTube-Client-Version", INNERTUBE_WEB_CLIENT_VERSION);
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Referer", "https://www.youtube.com/");
        headers.put("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7");
        return headers;
    }

    private static void ensureApiKeyConfigured() throws IOException {
        if (!Config.isApiKeyConfigured()) {
            throw new IOException("Chưa cấu hình YOUTUBE_API_KEY trong Config.java.");
        }
    }

    // ==================================================================
    // 1. Danh sách video Trang chủ / Thịnh hành / Chip danh mục
    //    Ưu tiên Data API v3 (nếu có key) -> Tự động dùng InnerTube chính chủ
    // ==================================================================

    public PagedResult<VideoItem> fetchTrendingVideosSync(String categoryId, String pageToken)
            throws IOException {
        if (Config.isApiKeyConfigured()) {
            try {
                return fetchTrendingFromDataApiV3Sync(categoryId, pageToken);
            } catch (IOException e) {
                Log.w(TAG, "Data API v3 lỗi (" + e.getMessage() + ") -> chuyển sang InnerTube");
            }
        }
        return fetchTrendingFromInnerTubeSync(categoryId, pageToken);
    }

    private PagedResult<VideoItem> fetchTrendingFromDataApiV3Sync(String categoryId, String pageToken)
            throws IOException {
        String url = HttpClient.buildUrl(
                Config.YOUTUBE_DATA_API_BASE + "videos",
                "part", "snippet,contentDetails,statistics",
                "chart", "mostPopular",
                "regionCode", Config.REGION_CODE,
                "hl", Config.LANGUAGE_CODE,
                "maxResults", String.valueOf(Config.PAGE_SIZE),
                "videoCategoryId", categoryId,
                "pageToken", pageToken,
                "key", Config.YOUTUBE_API_KEY
        );
        JSONObject root = HttpClient.get(url, buildGoogleHeaders()).asJsonObject();
        String nextToken = root.optString("nextPageToken", null);
        int total = extractTotalResults(root);

        JSONArray items = root.optJSONArray("items");
        List<VideoItem> videos = new ArrayList<VideoItem>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                VideoItem video = VideoItem.fromVideoJson(items.optJSONObject(i));
                if (video != null) {
                    videos.add(video);
                }
            }
        }
        enrichVideosWithChannelAvatars(videos);
        return new PagedResult<VideoItem>(videos, nextToken, total);
    }

    public RequestHandle fetchTrendingVideos(final String categoryId,
                                             final String pageToken,
                                             ApiCallback<PagedResult<VideoItem>> callback) {
        return NetworkExecutor.get().execute(new Callable<PagedResult<VideoItem>>() {
            @Override
            public PagedResult<VideoItem> call() throws Exception {
                return fetchTrendingVideosSync(categoryId, pageToken);
            }
        }, callback);
    }

    // ==================================================================
    // 2. Tìm kiếm video
    //    Ưu tiên Data API v3 (nếu có key) -> Tự động dùng InnerTube Search chính chủ
    // ==================================================================

    public PagedResult<VideoItem> searchVideosSync(String query, String pageToken, String order)
            throws IOException {
        if (query == null || query.trim().length() == 0) {
            return PagedResult.empty();
        }
        if (Config.isApiKeyConfigured()) {
            try {
                return searchVideosFromDataApiV3Sync(query.trim(), pageToken, order);
            } catch (IOException e) {
                Log.w(TAG, "Data API v3 search lỗi (" + e.getMessage() + ") -> chuyển sang InnerTube Search");
            }
        }
        return searchVideosFromInnerTubeSync(query.trim(), pageToken);
    }

    private PagedResult<VideoItem> searchVideosFromDataApiV3Sync(String query, String pageToken, String order)
            throws IOException {
        String effectiveOrder = (order != null && order.length() > 0) ? order : "relevance";
        String url = HttpClient.buildUrl(
                Config.YOUTUBE_DATA_API_BASE + "search",
                "part", "snippet",
                "type", "video",
                "q", query,
                "regionCode", Config.REGION_CODE,
                "relevanceLanguage", Config.LANGUAGE_CODE,
                "maxResults", String.valueOf(Config.PAGE_SIZE),
                "order", effectiveOrder,
                "pageToken", pageToken,
                "key", Config.YOUTUBE_API_KEY
        );
        JSONObject root = HttpClient.get(url, buildGoogleHeaders()).asJsonObject();
        String nextToken = root.optString("nextPageToken", null);
        int total = extractTotalResults(root);

        JSONArray items = root.optJSONArray("items");
        List<VideoItem> results = new ArrayList<VideoItem>();
        List<String> videoIds = new ArrayList<String>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                VideoItem video = VideoItem.fromSearchJson(items.optJSONObject(i));
                if (video != null) {
                    results.add(video);
                    videoIds.add(video.getId());
                }
            }
        }

        if (!videoIds.isEmpty()) {
            try {
                Map<String, VideoItem> detailsMap = fetchVideosByIdsMapSync(videoIds);
                for (VideoItem item : results) {
                    VideoItem detailed = detailsMap.get(item.getId());
                    if (detailed != null) {
                        item.mergeDetails(detailed);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Không gộp được chi tiết video cho kết quả tìm kiếm: " + e.getMessage());
            }
            enrichVideosWithChannelAvatars(results);
        }

        return new PagedResult<VideoItem>(results, nextToken, total);
    }

    public RequestHandle searchVideos(final String query,
                                      final String pageToken,
                                      final String order,
                                      ApiCallback<PagedResult<VideoItem>> callback) {
        return NetworkExecutor.get().execute(new Callable<PagedResult<VideoItem>>() {
            @Override
            public PagedResult<VideoItem> call() throws Exception {
                return searchVideosSync(query, pageToken, order);
            }
        }, callback);
    }

    // ==================================================================
    // 3. Tầng YouTube InnerTube API chính chủ (POST /youtubei/v1/search & /browse)
    // ==================================================================

    /**
     * Tìm kiếm trực tiếp qua endpoint chính chủ của YouTube:
     * {@code POST https://www.youtube.com/youtubei/v1/search?prettyPrint=false}
     *
     * Hỗ trợ cả trang đầu ({@code "query": "..."}) lẫn phân trang tiếp theo ({@code "continuation": "..."}).
     */
    public PagedResult<VideoItem> searchVideosFromInnerTubeSync(String query, String continuationToken)
            throws IOException {
        try {
            JSONObject payload = buildInnerTubeWebContextPayload();
            if (continuationToken != null && continuationToken.trim().length() > 0) {
                payload.put("continuation", continuationToken.trim());
            } else {
                payload.put("query", query);
            }

            HttpResponse response = HttpClient.postJson(
                    Config.INNERTUBE_SEARCH_URL,
                    buildInnerTubeWebHeaders(),
                    payload.toString()
            );
            JSONObject root = response.asJsonObject();
            PagedResult<VideoItem> parsed = parseInnerTubeVideoResponse(root);
            if (!parsed.getItems().isEmpty()) {
                return parsed;
            }
        } catch (Exception e) {
            Log.w(TAG, "InnerTube search lỗi cho query='" + query + "': " + e.getMessage());
            // Nếu InnerTube gặp sự cố bất ngờ, thử nốt các mirror Piped dự phòng cuối cùng
            if (Config.ENABLE_PIPED_FALLBACK) {
                try {
                    return searchVideosFromPipedMirrorsSync(query, continuationToken);
                } catch (IOException ignored) {
                }
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Lỗi InnerTube Search: " + e.getMessage(), e);
        }
        return PagedResult.empty();
    }

    /**
     * Tải danh sách Trang chủ / Thịnh hành / Chip danh mục trực tiếp qua InnerTube:
     * - Nếu chọn Chip danh mục (Âm nhạc, Trò chơi, Tin tức, Thể thao, Giải trí, Công nghệ):
     *   gọi {@code /youtubei/v1/search} với từ khóa danh mục tương ứng tại Việt Nam.
     * - Nếu ở Trang chủ / Thịnh hành chung ({@code categoryId == null}):
     *   + Thử gọi {@code POST https://www.youtube.com/youtubei/v1/browse?prettyPrint=false}
     *     với {@code browseId: "FEtrending"}.
     *   + Nếu {@code FEtrending} trống hoặc đang phân trang bằng continuation token tìm kiếm,
     *     tự động gọi {@code /youtubei/v1/search} với từ khóa thịnh hành Việt Nam.
     */
    public PagedResult<VideoItem> fetchTrendingFromInnerTubeSync(String categoryId, String continuationToken)
            throws IOException {
        // Nếu người dùng đang chọn một Chip danh mục cụ thể hoặc đang cuộn trang tiếp theo (continuation)
        if (categoryId != null && categoryId.length() > 0) {
            String categoryQuery = mapCategoryIdToInnerTubeQuery(categoryId);
            return searchVideosFromInnerTubeSync(categoryQuery, continuationToken);
        }

        if (continuationToken != null && continuationToken.trim().length() > 0) {
            return searchVideosFromInnerTubeSync("xu hướng thịnh hành Việt Nam mới nhất", continuationToken);
        }

        // Thử lấy bảng FEtrending từ /youtubei/v1/browse trước
        try {
            JSONObject browsePayload = buildInnerTubeWebContextPayload();
            browsePayload.put("browseId", "FEtrending");

            HttpResponse response = HttpClient.postJson(
                    Config.INNERTUBE_BROWSE_URL,
                    buildInnerTubeWebHeaders(),
                    browsePayload.toString()
            );
            PagedResult<VideoItem> browseResult = parseInnerTubeVideoResponse(response.asJsonObject());
            if (!browseResult.getItems().isEmpty()) {
                return browseResult;
            }
        } catch (Exception e) {
            Log.w(TAG, "InnerTube browse FEtrending không trả dữ liệu (" + e.getMessage()
                    + ") -> dùng InnerTube search thịnh hành VN");
        }

        // Nguồn trang chủ / thịnh hành ổn định 100% qua InnerTube search
        return searchVideosFromInnerTubeSync("xu hướng thịnh hành Việt Nam mới nhất", null);
    }

    private static String mapCategoryIdToInnerTubeQuery(String categoryId) {
        if ("10".equals(categoryId)) {
            return "MV nhạc trẻ Việt Nam thịnh hành mới nhất";
        }
        if ("20".equals(categoryId)) {
            return "Gaming Việt Nam nổi bật mới nhất";
        }
        if ("25".equals(categoryId)) {
            return "Tin tức thời sự Việt Nam 24h mới nhất";
        }
        if ("17".equals(categoryId)) {
            return "Bóng đá thể thao Việt Nam highlight mới nhất";
        }
        if ("24".equals(categoryId)) {
            return "Giải trí chương trình thực tế Việt Nam thịnh hành";
        }
        if ("28".equals(categoryId)) {
            return "Đánh giá công nghệ smartphone mới nhất";
        }
        return "xu hướng thịnh hành Việt Nam mới nhất";
    }

    /**
     * Khởi tạo cấu trúc JSON Context chuẩn cho client {@code WEB} của InnerTube:
     * <pre>
     * {
     *   "context": {
     *     "client": {
     *       "clientName": "WEB",
     *       "clientVersion": "2.20240726.00.00",
     *       "hl": "vi",
     *       "gl": "VN"
     *     }
     *   }
     * }
     * </pre>
     */
    private static JSONObject buildInnerTubeWebContextPayload() throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", INNERTUBE_WEB_CLIENT_NAME);
        client.put("clientVersion", INNERTUBE_WEB_CLIENT_VERSION);
        client.put("hl", Config.LANGUAGE_CODE);
        client.put("gl", Config.REGION_CODE);
        String vd = webVisitorData;
        if (vd != null && vd.length() > 0) {
            client.put("visitorData", vd);
        }

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject root = new JSONObject();
        root.put("context", context);
        return root;
    }

    /**
     * Phân tích cây JSON trả về từ InnerTube ({@code /youtubei/v1/search} hoặc {@code /youtubei/v1/browse}):
     * 1. Quét đệ quy toàn bộ các object {@code "videoRenderer"} bằng {@link #findJsonObjectsByKey(Object, String, List)}.
     * 2. Chuyển đổi từng {@code videoRenderer} thành {@link VideoItem} (lọc trùng {@code videoId}).
     * 3. Tìm {@code continuationCommand.token} trong {@code continuationItemRenderer} để gán vào {@code nextPageToken}.
     */
    public static PagedResult<VideoItem> parseInnerTubeVideoResponse(JSONObject root) {
        if (root == null) {
            return PagedResult.empty();
        }

        JSONObject responseContext = root.optJSONObject("responseContext");
        if (responseContext != null) {
            String vd = responseContext.optString("visitorData", "").trim();
            if (vd.length() > 0) {
                webVisitorData = vd;
            }
        }

        List<JSONObject> videoRenderers = new ArrayList<JSONObject>();
        findJsonObjectsByKey(root, "videoRenderer", videoRenderers);

        List<VideoItem> videos = new ArrayList<VideoItem>();
        Set<String> seenIds = new LinkedHashSet<String>();

        for (int i = 0; i < videoRenderers.size(); i++) {
            VideoItem item = VideoItem.fromInnerTubeVideoRenderer(videoRenderers.get(i));
            if (item != null && item.getId().length() > 0 && !seenIds.contains(item.getId())) {
                seenIds.add(item.getId());
                videos.add(item);
            }
        }

        String nextContinuationToken = extractContinuationToken(root);
        return new PagedResult<VideoItem>(videos, nextContinuationToken, videos.size());
    }

    /**
     * Hàm đệ quy nhẹ quét toàn bộ cây {@link JSONObject} / {@link JSONArray} để thu thập
     * tất cả các {@link JSONObject} được gắn với khóa {@code targetKey} (ví dụ {@code "videoRenderer"}).
     */
    public static void findJsonObjectsByKey(Object node, String targetKey, List<JSONObject> outList) {
        if (node == null || targetKey == null || outList == null) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has(targetKey)) {
                JSONObject matched = obj.optJSONObject(targetKey);
                if (matched != null) {
                    outList.add(matched);
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object child = obj.opt(k);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    findJsonObjectsByKey(child, targetKey, outList);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object elem = arr.opt(i);
                if (elem instanceof JSONObject || elem instanceof JSONArray) {
                    findJsonObjectsByKey(elem, targetKey, outList);
                }
            }
        }
    }

    /**
     * Tìm token phân trang ({@code continuationCommand.token}) bên trong {@code continuationItemRenderer}.
     */
    private static String extractContinuationToken(JSONObject root) {
        List<JSONObject> continuationRenderers = new ArrayList<JSONObject>();
        findJsonObjectsByKey(root, "continuationItemRenderer", continuationRenderers);

        for (int i = 0; i < continuationRenderers.size(); i++) {
            JSONObject cir = continuationRenderers.get(i);
            List<JSONObject> commands = new ArrayList<JSONObject>();
            findJsonObjectsByKey(cir, "continuationCommand", commands);
            for (int j = 0; j < commands.size(); j++) {
                String token = commands.get(j).optString("token", "").trim();
                if (token.length() > 0) {
                    return token;
                }
            }
        }

        // Dự phòng: tìm trực tiếp continuationCommand trong toàn bộ phản hồi nếu không bọc trong continuationItemRenderer
        List<JSONObject> allCommands = new ArrayList<JSONObject>();
        findJsonObjectsByKey(root, "continuationCommand", allCommands);
        for (int i = 0; i < allCommands.size(); i++) {
            String token = allCommands.get(i).optString("token", "").trim();
            if (token.length() > 0) {
                return token;
            }
        }
        return null;
    }

    // ==================================================================
    // 4. Chi tiết video theo ID (videos.list?id=...)
    // ==================================================================

    public VideoItem fetchVideoDetailsSync(String videoId) throws IOException {
        if (videoId == null || videoId.trim().length() == 0) {
            throw new IOException("videoId rỗng");
        }
        List<String> single = Collections.singletonList(videoId.trim());
        Map<String, VideoItem> map = fetchVideosByIdsMapSync(single);
        VideoItem item = map.get(videoId.trim());
        if (item == null) {
            throw new IOException("Không tìm thấy video: " + videoId);
        }
        List<VideoItem> list = Collections.singletonList(item);
        enrichVideosWithChannelAvatars(list);
        return item;
    }

    public RequestHandle fetchVideoDetails(final String videoId, ApiCallback<VideoItem> callback) {
        return NetworkExecutor.get().execute(new Callable<VideoItem>() {
            @Override
            public VideoItem call() throws Exception {
                return fetchVideoDetailsSync(videoId);
            }
        }, callback);
    }

    public Map<String, VideoItem> fetchVideosByIdsMapSync(List<String> videoIds) throws IOException {
        ensureApiKeyConfigured();
        Map<String, VideoItem> map = new LinkedHashMap<String, VideoItem>();
        if (videoIds == null || videoIds.isEmpty()) {
            return map;
        }
        String joinedIds = joinCsv(videoIds, 50);
        String url = HttpClient.buildUrl(
                Config.YOUTUBE_DATA_API_BASE + "videos",
                "part", "snippet,contentDetails,statistics",
                "id", joinedIds,
                "hl", Config.LANGUAGE_CODE,
                "key", Config.YOUTUBE_API_KEY
        );
        JSONObject root = HttpClient.get(url, buildGoogleHeaders()).asJsonObject();
        JSONArray items = root.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                VideoItem video = VideoItem.fromVideoJson(items.optJSONObject(i));
                if (video != null) {
                    map.put(video.getId(), video);
                }
            }
        }
        return map;
    }

    // ==================================================================
    // 5. Thông tin kênh & Avatar (channels.list?id=...)
    // ==================================================================

    public Map<String, ChannelInfo> fetchChannelsMapSync(List<String> channelIds) throws IOException {
        ensureApiKeyConfigured();
        Map<String, ChannelInfo> map = new LinkedHashMap<String, ChannelInfo>();
        if (channelIds == null || channelIds.isEmpty()) {
            return map;
        }
        String joined = joinUniqueCsv(channelIds, 50);
        if (joined.length() == 0) {
            return map;
        }
        String url = HttpClient.buildUrl(
                Config.YOUTUBE_DATA_API_BASE + "channels",
                "part", "snippet,statistics",
                "id", joined,
                "key", Config.YOUTUBE_API_KEY
        );
        JSONObject root = HttpClient.get(url, buildGoogleHeaders()).asJsonObject();
        JSONArray items = root.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                ChannelInfo channel = ChannelInfo.fromJson(items.optJSONObject(i));
                if (channel != null) {
                    map.put(channel.getId(), channel);
                }
            }
        }
        return map;
    }

    private void enrichVideosWithChannelAvatars(List<VideoItem> videos) {
        if (videos == null || videos.isEmpty() || !Config.isApiKeyConfigured()) {
            return;
        }
        List<String> channelIds = new ArrayList<String>();
        for (VideoItem video : videos) {
            if (video.getChannelId() != null && video.getChannelId().length() > 0) {
                channelIds.add(video.getChannelId());
            }
        }
        if (channelIds.isEmpty()) {
            return;
        }
        try {
            Map<String, ChannelInfo> channels = fetchChannelsMapSync(channelIds);
            for (VideoItem video : videos) {
                ChannelInfo info = channels.get(video.getChannelId());
                if (info != null) {
                    video.setChannelAvatarUrl(info.getAvatarUrl());
                    video.setChannelSubscriberCount(info.getSubscriberCount());
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Không tải được avatar kênh: " + e.getMessage());
        }
    }

    // ==================================================================
    // 6. Danh mục video & Bình luận (Data API v3)
    // ==================================================================

    public List<VideoCategory> fetchVideoCategoriesSync() throws IOException {
        ensureApiKeyConfigured();
        String url = HttpClient.buildUrl(
                Config.YOUTUBE_DATA_API_BASE + "videoCategories",
                "part", "snippet",
                "regionCode", Config.REGION_CODE,
                "hl", Config.LANGUAGE_CODE,
                "key", Config.YOUTUBE_API_KEY
        );
        JSONObject root = HttpClient.get(url, buildGoogleHeaders()).asJsonObject();
        JSONArray items = root.optJSONArray("items");
        List<VideoCategory> categories = new ArrayList<VideoCategory>();
        categories.add(VideoCategory.all());
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                VideoCategory cat = VideoCategory.fromJson(items.optJSONObject(i));
                if (cat != null && cat.isAssignable()) {
                    categories.add(cat);
                }
            }
        }
        return categories;
    }

    public RequestHandle fetchVideoCategories(ApiCallback<List<VideoCategory>> callback) {
        return NetworkExecutor.get().execute(new Callable<List<VideoCategory>>() {
            @Override
            public List<VideoCategory> call() throws Exception {
                return fetchVideoCategoriesSync();
            }
        }, callback);
    }

    public PagedResult<CommentItem> fetchCommentsSync(String videoId, String pageToken)
            throws IOException {
        ensureApiKeyConfigured();
        String url = HttpClient.buildUrl(
                Config.YOUTUBE_DATA_API_BASE + "commentThreads",
                "part", "snippet",
                "videoId", videoId,
                "maxResults", String.valueOf(Config.PAGE_SIZE),
                "order", "relevance",
                "textFormat", "plainText",
                "pageToken", pageToken,
                "key", Config.YOUTUBE_API_KEY
        );
        JSONObject root = HttpClient.get(url, buildGoogleHeaders()).asJsonObject();
        String nextToken = root.optString("nextPageToken", null);
        int total = extractTotalResults(root);

        JSONArray items = root.optJSONArray("items");
        List<CommentItem> comments = new ArrayList<CommentItem>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                CommentItem comment = CommentItem.fromThreadJson(items.optJSONObject(i));
                if (comment != null) {
                    comments.add(comment);
                }
            }
        }
        return new PagedResult<CommentItem>(comments, nextToken, total);
    }

    public RequestHandle fetchComments(final String videoId,
                                       final String pageToken,
                                       ApiCallback<PagedResult<CommentItem>> callback) {
        return NetworkExecutor.get().execute(new Callable<PagedResult<CommentItem>>() {
            @Override
            public PagedResult<CommentItem> call() throws Exception {
                return fetchCommentsSync(videoId, pageToken);
            }
        }, callback);
    }

    // ==================================================================
    // 7. Gợi ý tìm kiếm (suggestqueries.google.com)
    // ==================================================================

    public List<String> fetchSearchSuggestionsSync(String query) throws IOException {
        return SearchSuggestionClient.fetchSuggestionsSync(query);
    }

    public RequestHandle fetchSearchSuggestions(final String query,
                                                ApiCallback<List<String>> callback) {
        return NetworkExecutor.get().execute(new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                return fetchSearchSuggestionsSync(query);
            }
        }, callback);
    }

    // ==================================================================
    // 8. Dự phòng cuối cùng qua các Mirror Piped (chỉ khi InnerTube lỗi)
    // ==================================================================

    private PagedResult<VideoItem> searchVideosFromPipedMirrorsSync(String query, String nextpage)
            throws IOException {
        IOException lastError = null;
        for (int m = 0; m < LAST_RESORT_PIPED_MIRRORS.length; m++) {
            String base = LAST_RESORT_PIPED_MIRRORS[m];
            if (base == null || base.length() == 0) {
                continue;
            }
            try {
                String url;
                if (nextpage != null && nextpage.length() > 0) {
                    url = HttpClient.buildUrl(
                            base + "/nextpage/search",
                            "q", query,
                            "filter", "videos",
                            "nextpage", nextpage
                    );
                } else {
                    url = HttpClient.buildUrl(
                            base + "/search",
                            "q", query,
                            "filter", "videos"
                    );
                }
                JSONObject root = HttpClient.get(url, null).asJsonObject();
                String nextToken = root.optString("nextpage", null);
                if ("null".equalsIgnoreCase(nextToken) || (nextToken != null && nextToken.length() == 0)) {
                    nextToken = null;
                }
                JSONArray items = root.optJSONArray("items");
                List<VideoItem> videos = new ArrayList<VideoItem>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        VideoItem v = VideoItem.fromPipedStreamItemJson(items.optJSONObject(i));
                        if (v != null) {
                            videos.add(v);
                        }
                    }
                }
                if (!videos.isEmpty()) {
                    return new PagedResult<VideoItem>(videos, nextToken, videos.size());
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("Không tìm kiếm được từ tất cả các nguồn");
    }

    // ==================================================================
    // Tiện ích nội bộ
    // ==================================================================

    private static int extractTotalResults(JSONObject root) {
        JSONObject pageInfo = root.optJSONObject("pageInfo");
        return pageInfo != null ? pageInfo.optInt("totalResults", 0) : 0;
    }

    private static String joinCsv(List<String> items, int maxCount) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(items.size(), maxCount);
        for (int i = 0; i < count; i++) {
            String value = items.get(i);
            if (value == null || value.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static String joinUniqueCsv(List<String> items, int maxCount) {
        List<String> unique = new ArrayList<String>();
        for (String item : items) {
            if (item != null && item.length() > 0 && !unique.contains(item)) {
                unique.add(item);
                if (unique.size() >= maxCount) {
                    break;
                }
            }
        }
        return joinCsv(unique, maxCount);
    }
}

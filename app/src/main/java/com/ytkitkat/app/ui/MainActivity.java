package com.ytkitkat.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ytkitkat.app.R;
import com.ytkitkat.app.adapter.SearchSuggestionAdapter;
import com.ytkitkat.app.adapter.VideoAdapter;
import com.ytkitkat.app.model.PagedResult;
import com.ytkitkat.app.model.VideoItem;
import com.ytkitkat.app.network.ApiCallback;
import com.ytkitkat.app.network.RequestHandle;
import com.ytkitkat.app.network.SearchSuggestionClient;
import com.ytkitkat.app.network.YouTubeApi;
import com.ytkitkat.app.player.PlayerOverlayManager;
import com.ytkitkat.app.util.ImageLoader;
import com.ytkitkat.app.util.SearchHistoryManager;
import com.ytkitkat.app.util.WatchHistoryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Màn hình chính (Home) mang giao diện YouTube Mobile 2026 (AMOLED Dark #0F0F0F)
 * tích hợp hoàn chỉnh Bước 4 + Bước 5 (Tìm kiếm chuyên sâu & Gợi ý tức thì)
 * + Bước 6 (Trình phát toàn màn hình, Chọn độ phân giải 1080p60/720p60, DualMediaPlayerController & Mini-player):
 *
 * 1. Top Bar: Logo YouTube (Play đỏ #FF0033 + chữ trắng), nút mở nhanh {@link NetworkDiagnosticActivity},
 *    và thanh tìm kiếm chuyên sâu (Nút Back, ô {@link EditText} tự động focus mở bàn phím, nút Clear {@code X}).
 * 2. Tìm kiếm chuyên sâu (Bước 5):
 *    - Kết nối {@link SearchSuggestionClient} (debounce 300ms) lấy gợi ý trực tiếp từ YouTube Suggest API.
 *    - Kết hợp {@link SearchHistoryManager} (lưu tối đa 20 từ khóa bằng SharedPreferences, icon đồng hồ + nút xóa).
 * 3. Filter Chips Bar & ListView danh sách video: Hỗ trợ Infinite Scroll (Phân trang tự động)
 *    cho cả danh sách Thịnh hành, Danh mục và Kết quả tìm kiếm.
 * 4. Trình phát & Mini-player (Bước 6):
 *    - Quản lý bởi {@link PlayerOverlayManager} gắn trong {@code player_container}.
 *    - Điều hướng nút Back cứng mượt mà: Full Player -> Mini-player -> Đóng tìm kiếm -> Đóng Mini-player -> Thoát.
 */
public class MainActivity extends Activity implements
        SearchSuggestionAdapter.SuggestionActionListener,
        PlayerOverlayManager.StateChangeListener {

    private static final int TAB_HOME = 0;
    private static final int TAB_EXPLORE = 1;
    private static final int TAB_LIBRARY = 2;

    // Thành phần Top Bar & Tìm kiếm (Bước 5)
    private ImageButton btnSearchBack;
    private View brandLogoGroup;
    private View topBarSpacer;
    private View searchInputContainer;
    private EditText editSearchQuery;
    private ImageButton btnCloseSearch;
    private ImageButton btnOpenDiagnostic;
    private ImageButton btnToggleSearch;

    // Bảng Gợi ý tìm kiếm & Lịch sử tìm kiếm (Bước 5)
    private View searchSuggestionsPanel;
    private View searchHistoryHeaderRow;
    private TextView txtSearchPanelLabel;
    private TextView btnClearSearchHistory;
    private ListView listSearchSuggestions;
    private SearchSuggestionAdapter suggestionAdapter;
    private SearchSuggestionClient suggestionClient;
    private SearchHistoryManager searchHistoryManager;
    private boolean suppressTextWatcher = false;

    // Hàng Filter Chips & Thanh phụ Thư viện
    private HorizontalScrollView chipsScrollView;
    private LinearLayout chipsContainer;
    private View libraryHeaderBar;
    private Button btnClearHistory;

    // Khung danh sách & Trạng thái
    private ListView listVideos;
    private View loadingFooterView;
    private boolean footerAdded;
    private ProgressBar progressInitial;
    private View stateMessageContainer;
    private TextView txtStateMessage;
    private Button btnStateRetry;

    // Bottom Navigation
    private View navTabHome;
    private View navTabExplore;
    private View navTabLibrary;
    private ImageView iconNavHome;
    private ImageView iconNavExplore;
    private ImageView iconNavLibrary;
    private TextView txtNavHome;
    private TextView txtNavExplore;
    private TextView txtNavLibrary;

    // Khung Trình phát & Mini-player (Bước 6)
    private FrameLayout playerContainer;
    private PlayerOverlayManager playerOverlayManager;

    // Dữ liệu & Phân trang
    private VideoAdapter videoAdapter;
    private final List<FilterChipOption> chipOptions = new ArrayList<FilterChipOption>();
    private final List<TextView> chipViews = new ArrayList<TextView>();
    private int selectedChipIndex = 0;
    private int currentTab = TAB_HOME;

    private String activeSearchQuery = null;
    private String nextPageToken = null;
    private boolean isLoadingPage = false;
    private RequestHandle activeRequestHandle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initFilterChips();
        initListViewAndPagination();
        initSearchComponents();
        initTopBarListeners();
        initBottomNavListeners();
        initPlayerOverlay();

        switchTab(TAB_HOME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (playerOverlayManager != null) {
            playerOverlayManager.onActivityPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelActiveRequest();
        if (suggestionClient != null) {
            suggestionClient.cancelPending();
        }
        if (playerOverlayManager != null) {
            playerOverlayManager.onActivityDestroy();
        }
    }

    /**
     * Điều hướng nút Back vật lý chuẩn YouTube Mobile:
     * 1. Nếu đang mở Player toàn màn hình -> thu nhỏ xuống Mini-player (không ngắt video).
     * 2. Nếu đang mở bảng Gợi ý tìm kiếm -> đóng bảng gợi ý.
     * 3. Nếu đang ở kết quả tìm kiếm -> xóa bộ lọc tìm kiếm và quay lại trang chính.
     * 4. Nếu đang có Mini-player nổi ở đáy -> đóng Mini-player.
     * 5. Nếu đang ở tab Thịnh hành / Thư viện -> quay về tab Trang chủ.
     * 6. Ngược lại -> thoát ứng dụng.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (playerOverlayManager != null && playerOverlayManager.isFullExpanded()) {
            playerOverlayManager.minimizeToMiniPlayer();
            return;
        }
        if (searchSuggestionsPanel.getVisibility() == View.VISIBLE) {
            hideSearchSuggestionsPanel();
            if (activeSearchQuery == null) {
                closeSearchMode(false);
            }
            return;
        }
        if (searchInputContainer.getVisibility() == View.VISIBLE) {
            closeSearchMode(true);
            return;
        }
        if (playerOverlayManager != null && playerOverlayManager.isMiniPlayerVisible()) {
            playerOverlayManager.closePlayer();
            return;
        }
        if (currentTab != TAB_HOME) {
            switchTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Khởi tạo giao diện
    // ------------------------------------------------------------------

    private void bindViews() {
        btnSearchBack = (ImageButton) findViewById(R.id.btn_search_back);
        brandLogoGroup = findViewById(R.id.brand_logo_group);
        topBarSpacer = findViewById(R.id.top_bar_spacer);
        searchInputContainer = findViewById(R.id.search_input_container);
        editSearchQuery = (EditText) findViewById(R.id.edit_search_query);
        btnCloseSearch = (ImageButton) findViewById(R.id.btn_close_search);
        btnOpenDiagnostic = (ImageButton) findViewById(R.id.btn_open_diagnostic);
        btnToggleSearch = (ImageButton) findViewById(R.id.btn_toggle_search);

        searchSuggestionsPanel = findViewById(R.id.search_suggestions_panel);
        searchHistoryHeaderRow = findViewById(R.id.search_history_header_row);
        txtSearchPanelLabel = (TextView) findViewById(R.id.txt_search_panel_label);
        btnClearSearchHistory = (TextView) findViewById(R.id.btn_clear_search_history);
        listSearchSuggestions = (ListView) findViewById(R.id.list_search_suggestions);

        chipsScrollView = (HorizontalScrollView) findViewById(R.id.chips_scroll_view);
        chipsContainer = (LinearLayout) findViewById(R.id.chips_container);
        libraryHeaderBar = findViewById(R.id.library_header_bar);
        btnClearHistory = (Button) findViewById(R.id.btn_clear_history);

        listVideos = (ListView) findViewById(R.id.list_videos);
        progressInitial = (ProgressBar) findViewById(R.id.progress_initial);
        stateMessageContainer = findViewById(R.id.state_message_container);
        txtStateMessage = (TextView) findViewById(R.id.txt_state_message);
        btnStateRetry = (Button) findViewById(R.id.btn_state_retry);

        navTabHome = findViewById(R.id.nav_tab_home);
        navTabExplore = findViewById(R.id.nav_tab_explore);
        navTabLibrary = findViewById(R.id.nav_tab_library);
        iconNavHome = (ImageView) findViewById(R.id.icon_nav_home);
        iconNavExplore = (ImageView) findViewById(R.id.icon_nav_explore);
        iconNavLibrary = (ImageView) findViewById(R.id.icon_nav_library);
        txtNavHome = (TextView) findViewById(R.id.txt_nav_home);
        txtNavExplore = (TextView) findViewById(R.id.txt_nav_explore);
        txtNavLibrary = (TextView) findViewById(R.id.txt_nav_library);

        playerContainer = (FrameLayout) findViewById(R.id.player_container);
    }

    private void initFilterChips() {
        chipOptions.clear();
        chipOptions.add(new FilterChipOption(getString(R.string.chip_all), null, null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_music), "10", null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_gaming), "20", null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_news), "25", null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_sports), "17", null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_entertainment), "24", null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_tech), "28", null));
        chipOptions.add(new FilterChipOption(getString(R.string.chip_live), null, "trực tiếp việt nam"));

        chipsContainer.removeAllViews();
        chipViews.clear();

        int marginPx = (int) ImageLoader.getInstance().dpToPx(8f);
        int padH = (int) ImageLoader.getInstance().dpToPx(12f);
        int heightPx = (int) ImageLoader.getInstance().dpToPx(32f);

        for (int i = 0; i < chipOptions.size(); i++) {
            final int index = i;
            FilterChipOption option = chipOptions.get(i);

            TextView chip = new TextView(this, null, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, heightPx);
            if (i > 0) {
                lp.leftMargin = marginPx;
            }
            chip.setLayoutParams(lp);
            chip.setPadding(padH, 0, padH, 0);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setTextSize(14f);
            chip.setBackgroundResource(R.drawable.selector_chip);
            chip.setTextColor(getResources().getColorStateList(R.color.chip_text));
            chip.setText(option.title);
            chip.setSelected(i == selectedChipIndex);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onChipSelected(index);
                }
            });

            chipsContainer.addView(chip);
            chipViews.add(chip);
        }
    }

    private void onChipSelected(int index) {
        if (index < 0 || index >= chipOptions.size()) {
            return;
        }
        selectedChipIndex = index;
        activeSearchQuery = null;
        if (searchInputContainer.getVisibility() == View.VISIBLE) {
            closeSearchMode(false);
        }
        updateChipSelectionStates();
        reloadCurrentFeed();
    }

    private void updateChipSelectionStates() {
        for (int i = 0; i < chipViews.size(); i++) {
            chipViews.get(i).setSelected(activeSearchQuery == null && i == selectedChipIndex);
        }
    }

    private void initListViewAndPagination() {
        loadingFooterView = LayoutInflater.from(this)
                .inflate(R.layout.view_list_loading_footer, listVideos, false);
        // Thêm footer trước khi setAdapter trên Android 4.4 để tránh lỗi HeaderViewListAdapter
        listVideos.addFooterView(loadingFooterView, null, false);
        footerAdded = true;
        videoAdapter = new VideoAdapter(this);
        listVideos.setAdapter(videoAdapter);
        setLoadingFooterVisible(false);

        listVideos.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= videoAdapter.getCount()) {
                    return;
                }
                VideoItem clicked = videoAdapter.getItem(position);
                onVideoItemClicked(clicked);
            }
        });

        // Infinite Scroll: Khi cuộn tới 3 item cuối cùng và có nextPageToken -> tự động tải trang tiếp
        listVideos.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view,
                                 int firstVisibleItem,
                                 int visibleItemCount,
                                 int totalItemCount) {
                if (currentTab == TAB_LIBRARY || isLoadingPage || nextPageToken == null) {
                    return;
                }
                int adapterCount = videoAdapter.getCount();
                if (adapterCount == 0) {
                    return;
                }
                int lastVisible = firstVisibleItem + visibleItemCount;
                if (lastVisible >= adapterCount - 3) {
                    loadNextPage();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Bước 5: Tìm kiếm chuyên sâu & Gợi ý từ khóa tức thì (Debounce 300ms)
    // ------------------------------------------------------------------

    private void initSearchComponents() {
        searchHistoryManager = SearchHistoryManager.getInstance(this);
        suggestionClient = new SearchSuggestionClient();
        suggestionAdapter = new SearchSuggestionAdapter(this, this);
        listSearchSuggestions.setAdapter(suggestionAdapter);

        // Khi cuộn danh sách gợi ý -> ẩn bàn phím để người dùng nhìn rõ toàn bộ từ khóa
        listSearchSuggestions.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            }
        });

        // Xóa toàn bộ lịch sử tìm kiếm
        btnClearSearchHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchHistoryManager.clearAll();
                String currentText = getSearchInputText();
                refreshSuggestionsForText(currentText);
            }
        });

        // Lắng nghe từng phím gõ với Debounce 300ms qua SearchSuggestionClient
        editSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String text = s != null ? s.toString() : "";
                btnCloseSearch.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
                if (suppressTextWatcher) {
                    return;
                }
                if (searchInputContainer.getVisibility() == View.VISIBLE) {
                    showSearchSuggestionsPanel();
                    refreshSuggestionsForText(text);
                }
            }
        });

        // Khi người dùng chạm lại vào ô EditText lúc đang xem kết quả tìm kiếm -> mở lại bảng gợi ý
        editSearchQuery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (searchInputContainer.getVisibility() == View.VISIBLE
                        && searchSuggestionsPanel.getVisibility() != View.VISIBLE) {
                    showSearchSuggestionsPanel();
                    refreshSuggestionsForText(getSearchInputText());
                }
            }
        });
    }

    private void refreshSuggestionsForText(final String rawText) {
        final String trimmed = rawText != null ? rawText.trim() : "";
        final List<String> historyMatches = searchHistoryManager.getMatchingQueries(trimmed);
        boolean hasAllHistory = !searchHistoryManager.getAllQueries().isEmpty();

        btnClearSearchHistory.setVisibility(hasAllHistory ? View.VISIBLE : View.GONE);
        txtSearchPanelLabel.setText(trimmed.length() == 0
                ? "Lịch sử tìm kiếm gần đây"
                : "Gợi ý cho \"" + trimmed + "\"");

        // Hiển thị tức thì các mục lịch sử khớp trước khi mạng trả về
        suggestionAdapter.updateEntries(historyMatches, Collections.<String>emptyList());

        if (trimmed.length() == 0) {
            suggestionClient.cancelPending();
            return;
        }

        // Gọi YouTube Suggest API với Debounce 300ms
        suggestionClient.requestSuggestionsDebounced(trimmed, new ApiCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> liveSuggestions) {
                if (searchSuggestionsPanel.getVisibility() != View.VISIBLE) {
                    return;
                }
                List<String> latestHistory = searchHistoryManager.getMatchingQueries(trimmed);
                suggestionAdapter.updateEntries(latestHistory, liveSuggestions);
            }

            @Override
            public void onError(Exception error) {
                // Nếu mất mạng vẫn giữ nguyên gợi ý từ lịch sử cục bộ
            }
        });
    }

    @Override
    public void onSuggestionClicked(String query) {
        executeSearchQuery(query);
    }

    @Override
    public void onFillQueryClicked(String query) {
        if (query == null) {
            return;
        }
        suppressTextWatcher = true;
        editSearchQuery.setText(query);
        editSearchQuery.setSelection(query.length());
        suppressTextWatcher = false;
        btnCloseSearch.setVisibility(query.length() > 0 ? View.VISIBLE : View.GONE);
        refreshSuggestionsForText(query);
    }

    @Override
    public void onDeleteHistoryQueryClicked(String query) {
        searchHistoryManager.removeQuery(query);
        refreshSuggestionsForText(getSearchInputText());
    }

    private void openSearchMode() {
        brandLogoGroup.setVisibility(View.GONE);
        topBarSpacer.setVisibility(View.GONE);
        btnSearchBack.setVisibility(View.VISIBLE);
        searchInputContainer.setVisibility(View.VISIBLE);

        String existingQuery = getSearchInputText();
        btnCloseSearch.setVisibility(existingQuery.length() > 0 ? View.VISIBLE : View.GONE);

        showSearchSuggestionsPanel();
        refreshSuggestionsForText(existingQuery);

        editSearchQuery.requestFocus();
        editSearchQuery.selectAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(editSearchQuery, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeSearchMode(boolean reloadFeedIfWasSearching) {
        suggestionClient.cancelPending();
        hideKeyboard();
        hideSearchSuggestionsPanel();

        suppressTextWatcher = true;
        editSearchQuery.setText("");
        suppressTextWatcher = false;

        btnCloseSearch.setVisibility(View.GONE);
        btnSearchBack.setVisibility(View.GONE);
        searchInputContainer.setVisibility(View.GONE);
        brandLogoGroup.setVisibility(View.VISIBLE);
        topBarSpacer.setVisibility(View.VISIBLE);

        if (reloadFeedIfWasSearching && activeSearchQuery != null) {
            activeSearchQuery = null;
            updateChipSelectionStates();
            reloadCurrentFeed();
        }
    }

    private void showSearchSuggestionsPanel() {
        searchSuggestionsPanel.setVisibility(View.VISIBLE);
    }

    private void hideSearchSuggestionsPanel() {
        suggestionClient.cancelPending();
        searchSuggestionsPanel.setVisibility(View.GONE);
    }

    private void executeSearchQuery(String rawQuery) {
        String q = rawQuery != null ? rawQuery.trim() : "";
        if (q.length() == 0) {
            return;
        }
        // Lưu vào lịch sử tìm kiếm (SharedPreferences tối đa 20 mục)
        searchHistoryManager.addQuery(q);

        suppressTextWatcher = true;
        editSearchQuery.setText(q);
        editSearchQuery.setSelection(q.length());
        suppressTextWatcher = false;
        btnCloseSearch.setVisibility(View.VISIBLE);

        hideKeyboard();
        hideSearchSuggestionsPanel();

        if (currentTab == TAB_LIBRARY) {
            currentTab = TAB_HOME;
            updateBottomNavVisuals(TAB_HOME);
            chipsScrollView.setVisibility(View.VISIBLE);
            libraryHeaderBar.setVisibility(View.GONE);
        }
        activeSearchQuery = q;
        updateChipSelectionStates();
        reloadCurrentFeed();
    }

    private String getSearchInputText() {
        return editSearchQuery.getText() != null ? editSearchQuery.getText().toString().trim() : "";
    }

    // ------------------------------------------------------------------
    // Bước 6: Khởi tạo Trình phát & Mini-player
    // ------------------------------------------------------------------

    private void initPlayerOverlay() {
        playerOverlayManager = new PlayerOverlayManager(this, playerContainer, this);
    }

    @Override
    public void onPlayerStateChanged(int newState) {
        // Khi Mini-player đang nổi ở đáy (cao 62dp), cộng thêm khoảng đệm dưới cho ListView
        // để item video cuối cùng không bị thanh Mini-player che khuất.
        int bottomPadPx = (newState == PlayerOverlayManager.STATE_MINI_PLAYER)
                ? (int) ImageLoader.getInstance().dpToPx(64f)
                : 0;
        listVideos.setPadding(
                listVideos.getPaddingLeft(),
                listVideos.getPaddingTop(),
                listVideos.getPaddingRight(),
                bottomPadPx
        );
    }

    // ------------------------------------------------------------------
    // Sự kiện Top Bar & Bottom Navigation
    // ------------------------------------------------------------------

    private void initTopBarListeners() {
        brandLogoGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (videoAdapter.getCount() > 0) {
                    listVideos.smoothScrollToPosition(0);
                }
            }
        });

        btnSearchBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (searchSuggestionsPanel.getVisibility() == View.VISIBLE && activeSearchQuery != null) {
                    hideSearchSuggestionsPanel();
                    hideKeyboard();
                } else {
                    closeSearchMode(true);
                }
            }
        });

        btnOpenDiagnostic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, NetworkDiagnosticActivity.class);
                startActivity(intent);
            }
        });

        btnToggleSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (searchInputContainer.getVisibility() != View.VISIBLE) {
                    openSearchMode();
                } else {
                    String q = getSearchInputText();
                    if (q.length() > 0) {
                        executeSearchQuery(q);
                    } else {
                        showSearchSuggestionsPanel();
                        refreshSuggestionsForText("");
                    }
                }
            }
        });

        // Nút Clear X trong ô tìm kiếm: xóa chữ và hiển thị lại lịch sử tìm kiếm
        btnCloseSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                suppressTextWatcher = true;
                editSearchQuery.setText("");
                suppressTextWatcher = false;
                btnCloseSearch.setVisibility(View.GONE);
                showSearchSuggestionsPanel();
                refreshSuggestionsForText("");
                editSearchQuery.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(editSearchQuery, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });

        editSearchQuery.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    executeSearchQuery(getSearchInputText());
                    return true;
                }
                return false;
            }
        });

        btnStateRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadCurrentFeed();
            }
        });

        btnClearHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WatchHistoryManager.getInstance(MainActivity.this).clearHistory();
                loadLibraryHistory();
            }
        });
    }

    private void initBottomNavListeners() {
        navTabHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_HOME);
            }
        });

        navTabExplore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_EXPLORE);
            }
        });

        navTabLibrary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(TAB_LIBRARY);
            }
        });
    }

    // ------------------------------------------------------------------
    // Chuyển đổi Tab & Tải dữ liệu Video
    // ------------------------------------------------------------------

    private void switchTab(int tab) {
        currentTab = tab;
        hideSearchSuggestionsPanel();
        updateBottomNavVisuals(tab);

        if (tab == TAB_LIBRARY) {
            chipsScrollView.setVisibility(View.GONE);
            libraryHeaderBar.setVisibility(View.VISIBLE);
            loadLibraryHistory();
        } else {
            chipsScrollView.setVisibility(View.VISIBLE);
            libraryHeaderBar.setVisibility(View.GONE);
            if (tab == TAB_EXPLORE) {
                activeSearchQuery = null;
                selectedChipIndex = 0;
                if (searchInputContainer.getVisibility() == View.VISIBLE) {
                    closeSearchMode(false);
                }
                updateChipSelectionStates();
            }
            reloadCurrentFeed();
        }
    }

    private void updateBottomNavVisuals(int activeTab) {
        int colorActive = getResources().getColor(R.color.yt_text_primary);
        int colorInactive = getResources().getColor(R.color.yt_text_secondary);
        int colorAccent = getResources().getColor(R.color.yt_accent);

        txtNavHome.setTextColor(activeTab == TAB_HOME ? colorActive : colorInactive);
        txtNavExplore.setTextColor(activeTab == TAB_EXPLORE ? colorActive : colorInactive);
        txtNavLibrary.setTextColor(activeTab == TAB_LIBRARY ? colorActive : colorInactive);

        iconNavHome.setColorFilter(activeTab == TAB_HOME ? colorAccent : colorInactive);
        iconNavExplore.setColorFilter(activeTab == TAB_EXPLORE ? colorAccent : colorInactive);
        iconNavLibrary.setColorFilter(activeTab == TAB_LIBRARY ? colorAccent : colorInactive);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && editSearchQuery != null) {
            imm.hideSoftInputFromWindow(editSearchQuery.getWindowToken(), 0);
        }
    }

    private void reloadCurrentFeed() {
        if (currentTab == TAB_LIBRARY) {
            loadLibraryHistory();
            return;
        }
        cancelActiveRequest();
        nextPageToken = null;
        isLoadingPage = true;

        videoAdapter.clear();
        setLoadingFooterVisible(false);
        stateMessageContainer.setVisibility(View.GONE);
        progressInitial.setVisibility(View.VISIBLE);

        requestVideoPage(null, true);
    }

    private void loadNextPage() {
        if (isLoadingPage || nextPageToken == null || currentTab == TAB_LIBRARY) {
            return;
        }
        isLoadingPage = true;
        setLoadingFooterVisible(true);
        requestVideoPage(nextPageToken, false);
    }

    private void requestVideoPage(final String pageToken, final boolean isFirstPage) {
        ApiCallback<PagedResult<VideoItem>> callback = new ApiCallback<PagedResult<VideoItem>>() {
            @Override
            public void onSuccess(PagedResult<VideoItem> result) {
                isLoadingPage = false;
                progressInitial.setVisibility(View.GONE);
                setLoadingFooterVisible(false);

                nextPageToken = result.getNextPageToken();
                List<VideoItem> fetched = result.getItems();

                if (isFirstPage) {
                    videoAdapter.setItems(fetched);
                    if (!fetched.isEmpty()) {
                        listVideos.setSelection(0);
                    }
                } else {
                    videoAdapter.appendItems(fetched);
                }

                if (videoAdapter.getCount() == 0) {
                    showStateMessage(getString(R.string.empty_videos), true);
                } else {
                    stateMessageContainer.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(Exception error) {
                isLoadingPage = false;
                progressInitial.setVisibility(View.GONE);
                setLoadingFooterVisible(false);

                if (videoAdapter.getCount() == 0) {
                    showStateMessage(getString(R.string.error_network, error.getMessage()), true);
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.error_network, error.getMessage()),
                            Toast.LENGTH_SHORT).show();
                }
            }
        };

        // 1. Nếu người dùng đang tìm kiếm từ khóa
        if (activeSearchQuery != null && activeSearchQuery.length() > 0) {
            activeRequestHandle = YouTubeApi.getInstance()
                    .searchVideos(activeSearchQuery, pageToken, "relevance", callback);
            return;
        }

        // 2. Lấy theo Chip danh mục đang chọn
        FilterChipOption chip = chipOptions.get(selectedChipIndex);
        if (chip.quickKeyword != null && chip.quickKeyword.length() > 0) {
            activeRequestHandle = YouTubeApi.getInstance()
                    .searchVideos(chip.quickKeyword, pageToken, "relevance", callback);
        } else {
            activeRequestHandle = YouTubeApi.getInstance()
                    .fetchTrendingVideos(chip.categoryId, pageToken, callback);
        }
    }

    private void loadLibraryHistory() {
        cancelActiveRequest();
        isLoadingPage = false;
        nextPageToken = null;
        progressInitial.setVisibility(View.GONE);
        setLoadingFooterVisible(false);

        List<VideoItem> history = WatchHistoryManager.getInstance(this).getHistoryItems();
        videoAdapter.setItems(history);

        if (history.isEmpty()) {
            showStateMessage(getString(R.string.empty_history), false);
        } else {
            stateMessageContainer.setVisibility(View.GONE);
        }
    }

    private void showStateMessage(String message, boolean showRetryButton) {
        txtStateMessage.setText(message);
        btnStateRetry.setVisibility(showRetryButton ? View.VISIBLE : View.GONE);
        stateMessageContainer.setVisibility(View.VISIBLE);
    }

    private void setLoadingFooterVisible(boolean visible) {
        if (!footerAdded || loadingFooterView == null) {
            return;
        }
        loadingFooterView.setVisibility(visible ? View.VISIBLE : View.GONE);
        loadingFooterView.setPadding(0, visible ? 24 : -2000, 0, visible ? 24 : 0);
    }

    private void cancelActiveRequest() {
        if (activeRequestHandle != null) {
            activeRequestHandle.cancel();
            activeRequestHandle = null;
        }
    }

    /**
     * Khi người dùng chạm vào 1 video trong danh sách:
     * - Ghi vào lịch sử xem cục bộ ({@link WatchHistoryManager}).
     * - Ẩn bàn phím / bảng gợi ý tìm kiếm nếu đang mở.
     * - Mở trình phát toàn màn hình ({@link PlayerOverlayManager#openAndPlayVideo(VideoItem)})
     *   để phân giải luồng 1080p60/720p60/360p/Audio-Only và phát trực tiếp.
     */
    private void onVideoItemClicked(VideoItem video) {
        if (video == null) {
            return;
        }
        hideKeyboard();
        hideSearchSuggestionsPanel();
        WatchHistoryManager.getInstance(this).recordWatched(video);
        if (playerOverlayManager != null) {
            playerOverlayManager.openAndPlayVideo(video);
        }
    }

    // ------------------------------------------------------------------
    // Model cấu hình Chip lọc
    // ------------------------------------------------------------------

    private static final class FilterChipOption {
        final String title;
        final String categoryId;
        final String quickKeyword;

        FilterChipOption(String title, String categoryId, String quickKeyword) {
            this.title = title;
            this.categoryId = categoryId;
            this.quickKeyword = quickKeyword;
        }
    }
}

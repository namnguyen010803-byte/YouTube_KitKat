package com.ytkitkat.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Một trang kết quả có phân trang (nextPageToken) của YouTube Data API.
 */
public final class PagedResult<T> {

    private final List<T> items;
    private final String nextPageToken;
    private final int totalResults;

    public PagedResult(List<T> items, String nextPageToken, int totalResults) {
        this.items = items != null ? items : new ArrayList<T>();
        this.nextPageToken = (nextPageToken != null && nextPageToken.length() > 0) ? nextPageToken : null;
        this.totalResults = totalResults;
    }

    public static <T> PagedResult<T> empty() {
        return new PagedResult<T>(new ArrayList<T>(), null, 0);
    }

    public List<T> getItems() {
        return Collections.unmodifiableList(items);
    }

    public String getNextPageToken() {
        return nextPageToken;
    }

    public int getTotalResults() {
        return totalResults;
    }

    public boolean hasMore() {
        return nextPageToken != null;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }
}

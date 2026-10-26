package com.ytkitkat.app.network;

/**
 * Callback cho tác vụ mạng bất đồng bộ. Luôn được gọi trên Main (UI) thread.
 */
public interface ApiCallback<T> {

    void onSuccess(T result);

    void onError(Exception error);
}

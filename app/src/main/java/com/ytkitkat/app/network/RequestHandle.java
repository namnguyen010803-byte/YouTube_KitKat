package com.ytkitkat.app.network;

import java.util.concurrent.Future;

/**
 * Tay cầm để hủy một request đang chạy (gọi trong Activity.onDestroy()).
 * Sau khi cancel(), callback chắc chắn KHÔNG được gọi nữa.
 */
public final class RequestHandle {

    private volatile boolean cancelled;
    private volatile Future<?> future;

    void setFuture(Future<?> future) {
        this.future = future;
        if (cancelled && future != null) {
            future.cancel(true);
        }
    }

    public void cancel() {
        cancelled = true;
        Future<?> f = future;
        if (f != null) {
            f.cancel(true);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isDone() {
        Future<?> f = future;
        return f != null && f.isDone();
    }
}

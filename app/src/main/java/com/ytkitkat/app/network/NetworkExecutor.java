package com.ytkitkat.app.network;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.ytkitkat.app.Config;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool luồng mạng dùng chung + chuyển kết quả về Main thread qua Handler.
 */
public final class NetworkExecutor {

    private static final String TAG = "NetworkExecutor";

    private static volatile NetworkExecutor instance;

    private final ExecutorService executor;
    private final Handler mainHandler;

    private NetworkExecutor() {
        executor = Executors.newFixedThreadPool(Config.NETWORK_THREAD_COUNT, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(final Runnable runnable) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        runnable.run();
                    }
                }, "yt-net-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static NetworkExecutor get() {
        NetworkExecutor local = instance;
        if (local == null) {
            synchronized (NetworkExecutor.class) {
                local = instance;
                if (local == null) {
                    local = new NetworkExecutor();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Chạy task trên luồng nền, trả kết quả về Main thread.
     *
     * @return handle để hủy; callback không được gọi sau khi hủy.
     */
    public <T> RequestHandle execute(final Callable<T> task, final ApiCallback<T> callback) {
        final RequestHandle handle = new RequestHandle();
        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                if (handle.isCancelled()) {
                    return;
                }
                try {
                    T result = task.call();
                    deliverSuccess(handle, callback, result);
                } catch (Exception e) {
                    deliverError(handle, callback, e);
                } catch (OutOfMemoryError oom) {
                    Log.e(TAG, "Hết bộ nhớ khi chạy tác vụ mạng", oom);
                    deliverError(handle, callback, new IOException("Hết bộ nhớ: " + oom.getMessage()));
                }
            }
        });
        handle.setFuture(future);
        return handle;
    }

    /** Chạy một Runnable trên luồng nền (không cần kết quả). */
    public void runInBackground(Runnable runnable) {
        executor.execute(runnable);
    }

    public void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    public Handler getMainHandler() {
        return mainHandler;
    }

    private <T> void deliverSuccess(final RequestHandle handle, final ApiCallback<T> callback, final T result) {
        if (callback == null || handle.isCancelled()) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!handle.isCancelled()) {
                    callback.onSuccess(result);
                }
            }
        });
    }

    private <T> void deliverError(final RequestHandle handle, final ApiCallback<T> callback, final Exception error) {
        if (handle.isCancelled()) {
            return;
        }
        if (Config.DEBUG_LOG) {
            Log.w(TAG, "Tác vụ mạng lỗi: " + error);
        }
        if (callback == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!handle.isCancelled()) {
                    callback.onError(error);
                }
            }
        });
    }
}

package com.ytkitkat.app.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.ytkitkat.app.model.ChannelInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý cơ sở dữ liệu Kênh Đăng Ký cục bộ (Local Subscriptions) bằng SQLite thuần SDK:
 * Không yêu cầu Google Account / OAuth, an toàn và tương thích 100% Android 4.4.4 (KitKat API 19)
 * đến Android 11+.
 */
public final class SubscriptionDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "subscriptions.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_CHANNELS = "channels";
    public static final String COL_CHANNEL_ID = "channel_id";
    public static final String COL_CHANNEL_NAME = "channel_name";
    public static final String COL_AVATAR_URL = "avatar_url";
    public static final String COL_SUBSCRIBED_AT = "subscribed_at";

    private static volatile SubscriptionDatabase instance;

    public static SubscriptionDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (SubscriptionDatabase.class) {
                if (instance == null) {
                    instance = new SubscriptionDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SubscriptionDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_CHANNELS + " ("
                + COL_CHANNEL_ID + " TEXT PRIMARY KEY, "
                + COL_CHANNEL_NAME + " TEXT, "
                + COL_AVATAR_URL + " TEXT, "
                + COL_SUBSCRIBED_AT + " INTEGER"
                + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHANNELS);
        onCreate(db);
    }

    public synchronized boolean isSubscribed(String channelId) {
        if (channelId == null || channelId.trim().length() == 0) {
            return false;
        }
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_CHANNELS, new String[]{COL_CHANNEL_ID},
                    COL_CHANNEL_ID + " = ?", new String[]{channelId.trim()},
                    null, null, null);
            return cursor != null && cursor.moveToFirst();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public synchronized boolean subscribe(String channelId, String channelName, String avatarUrl) {
        if (channelId == null || channelId.trim().length() == 0) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHANNEL_ID, channelId.trim());
        cv.put(COL_CHANNEL_NAME, channelName != null ? channelName.trim() : "");
        cv.put(COL_AVATAR_URL, avatarUrl != null ? avatarUrl.trim() : "");
        cv.put(COL_SUBSCRIBED_AT, System.currentTimeMillis());
        long result = db.insertWithOnConflict(TABLE_CHANNELS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return result != -1;
    }

    public synchronized boolean unsubscribe(String channelId) {
        if (channelId == null || channelId.trim().length() == 0) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.delete(TABLE_CHANNELS, COL_CHANNEL_ID + " = ?", new String[]{channelId.trim()});
        return rows > 0;
    }

    public synchronized boolean toggleSubscription(String channelId, String channelName, String avatarUrl) {
        if (isSubscribed(channelId)) {
            unsubscribe(channelId);
            return false;
        } else {
            subscribe(channelId, channelName, avatarUrl);
            return true;
        }
    }

    public synchronized List<ChannelInfo> getAllSubscribedChannels() {
        List<ChannelInfo> list = new ArrayList<ChannelInfo>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_CHANNELS,
                    new String[]{COL_CHANNEL_ID, COL_CHANNEL_NAME, COL_AVATAR_URL},
                    null, null, null, null, COL_SUBSCRIBED_AT + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String avatar = cursor.getString(2);
                    list.add(new ChannelInfo(id, name, avatar));
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }

    public synchronized List<String> getAllSubscribedChannelIds() {
        List<String> list = new ArrayList<String>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_CHANNELS, new String[]{COL_CHANNEL_ID},
                    null, null, null, null, COL_SUBSCRIBED_AT + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    list.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }

    public synchronized int getSubscribedCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CHANNELS, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }
}

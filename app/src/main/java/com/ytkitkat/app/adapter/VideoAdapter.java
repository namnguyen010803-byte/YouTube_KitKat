package com.ytkitkat.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.ytkitkat.app.R;
import com.ytkitkat.app.model.VideoItem;
import com.ytkitkat.app.util.ImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter hiển thị danh sách Video cho {@link android.widget.ListView} trên Android 4.4.4.
 *
 * Tối ưu hóa cho máy cổ (Sharp Aquos 305SH):
 * - Áp dụng mẫu ViewHolder nghiêm ngặt (không bao giờ gọi findViewById khi cuộn).
 * - Tính sẵn kích thước Thumbnail 16:9 theo màn hình để downsample RGB_565 + bo góc 12dp.
 * - Avatar kênh 36dp cắt tròn bằng BitmapShader thông qua {@link ImageLoader}.
 */
public class VideoAdapter extends BaseAdapter {

    private static final float THUMB_CORNER_DP = 12f;
    private static final int AVATAR_DIAMETER_DP = 36;

    private final LayoutInflater inflater;
    private final List<VideoItem> items = new ArrayList<VideoItem>();
    private final ImageLoader imageLoader;
    private final int thumbTargetWidthPx;
    private final int thumbTargetHeightPx;

    public VideoAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
        this.imageLoader = ImageLoader.getInstance();
        int screenW = imageLoader.getScreenWidthPx();
        int horizontalPaddingPx = (int) imageLoader.dpToPx(24f);
        // Downsample về kích thước vừa khít màn hình (tối đa rộng 640px để tiết kiệm RAM trên KitKat)
        int rawW = Math.max(240, screenW - horizontalPaddingPx);
        this.thumbTargetWidthPx = Math.min(rawW, 640);
        this.thumbTargetHeightPx = (this.thumbTargetWidthPx * 9) / 16;
    }

    public void setItems(List<VideoItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void appendItems(List<VideoItem> moreItems) {
        if (moreItems == null || moreItems.isEmpty()) {
            return;
        }
        for (VideoItem candidate : moreItems) {
            if (!containsVideoId(candidate.getId())) {
                items.add(candidate);
            }
        }
        notifyDataSetChanged();
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    private boolean containsVideoId(String videoId) {
        if (videoId == null) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (videoId.equals(items.get(i).getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public VideoItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_video, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        VideoItem video = items.get(position);
        holder.txtTitle.setText(video.getTitle());
        holder.txtMeta.setText(video.getMetaLine());

        // Badge thời lượng hoặc TRỰC TIẾP
        if (video.isLive()) {
            holder.txtDuration.setVisibility(View.VISIBLE);
            holder.txtDuration.setBackgroundResource(R.drawable.bg_live_badge);
            holder.txtDuration.setText(R.string.badge_live);
        } else {
            String formattedDuration = video.getFormattedDuration();
            if (formattedDuration.length() > 0) {
                holder.txtDuration.setVisibility(View.VISIBLE);
                holder.txtDuration.setBackgroundResource(R.drawable.bg_duration_badge);
                holder.txtDuration.setText(formattedDuration);
            } else {
                holder.txtDuration.setVisibility(View.GONE);
            }
        }

        // Tải Thumbnail 16:9 bo góc 12dp (gắn tag URL bên trong ImageLoader)
        imageLoader.loadRoundedThumbnail(
                holder.imgThumbnail,
                video.getThumbnailUrl(),
                thumbTargetWidthPx,
                thumbTargetHeightPx,
                THUMB_CORNER_DP,
                0
        );

        // Tải Avatar kênh tròn 36dp
        imageLoader.loadCircleAvatar(
                holder.imgAvatar,
                video.getChannelAvatarUrl(),
                AVATAR_DIAMETER_DP,
                R.drawable.bg_avatar_placeholder
        );

        return convertView;
    }

    static final class ViewHolder {
        final ImageView imgThumbnail;
        final TextView txtDuration;
        final ImageView imgAvatar;
        final TextView txtTitle;
        final TextView txtMeta;

        ViewHolder(View root) {
            this.imgThumbnail = (ImageView) root.findViewById(R.id.img_thumbnail);
            this.txtDuration = (TextView) root.findViewById(R.id.txt_duration);
            this.imgAvatar = (ImageView) root.findViewById(R.id.img_channel_avatar);
            this.txtTitle = (TextView) root.findViewById(R.id.txt_video_title);
            this.txtMeta = (TextView) root.findViewById(R.id.txt_video_meta);
        }
    }
}

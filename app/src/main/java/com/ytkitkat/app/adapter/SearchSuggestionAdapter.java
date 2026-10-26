package com.ytkitkat.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.ytkitkat.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter hiển thị kết hợp Lịch sử tìm kiếm (icon đồng hồ, có nút xóa từng mục)
 * và Gợi ý từ khóa trực tiếp từ YouTube Suggest API (icon kính lúp).
 */
public class SearchSuggestionAdapter extends BaseAdapter {

    public interface SuggestionActionListener {
        void onSuggestionClicked(String query);

        void onFillQueryClicked(String query);

        void onDeleteHistoryQueryClicked(String query);
    }

    public static final class SuggestionEntry {
        public final String query;
        public final boolean fromHistory;

        public SuggestionEntry(String query, boolean fromHistory) {
            this.query = query;
            this.fromHistory = fromHistory;
        }
    }

    private final LayoutInflater inflater;
    private final List<SuggestionEntry> entries = new ArrayList<SuggestionEntry>();
    private final SuggestionActionListener listener;
    private final int colorSecondary;

    public SearchSuggestionAdapter(Context context, SuggestionActionListener listener) {
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
        this.colorSecondary = context.getResources().getColor(R.color.yt_text_secondary);
    }

    /**
     * Gộp các từ khóa lịch sử khớp và các gợi ý mới từ mạng (không trùng lặp).
     */
    public void updateEntries(List<String> historyMatches, List<String> networkSuggestions) {
        entries.clear();
        if (historyMatches != null) {
            for (int i = 0; i < historyMatches.size(); i++) {
                String q = historyMatches.get(i);
                if (q != null && q.length() > 0 && !containsQuery(q)) {
                    entries.add(new SuggestionEntry(q, true));
                }
            }
        }
        if (networkSuggestions != null) {
            for (int i = 0; i < networkSuggestions.size(); i++) {
                String q = networkSuggestions.get(i);
                if (q != null && q.length() > 0 && !containsQuery(q)) {
                    entries.add(new SuggestionEntry(q, false));
                }
            }
        }
        notifyDataSetChanged();
    }

    private boolean containsQuery(String query) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).query.equalsIgnoreCase(query)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public SuggestionEntry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_search_suggestion, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final SuggestionEntry entry = entries.get(position);
        holder.txtQuery.setText(entry.query);

        if (entry.fromHistory) {
            holder.imgType.setImageResource(R.drawable.ic_history_clock);
            holder.imgType.setColorFilter(colorSecondary);
            holder.btnDeleteHistory.setVisibility(View.VISIBLE);
        } else {
            holder.imgType.setImageResource(R.drawable.ic_search);
            holder.imgType.setColorFilter(colorSecondary);
            holder.btnDeleteHistory.setVisibility(View.GONE);
        }

        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onSuggestionClicked(entry.query);
                }
            }
        });

        holder.btnFillQuery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onFillQueryClicked(entry.query);
                }
            }
        });

        holder.btnDeleteHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDeleteHistoryQueryClicked(entry.query);
                }
            }
        });

        return convertView;
    }

    static final class ViewHolder {
        final ImageView imgType;
        final TextView txtQuery;
        final ImageButton btnDeleteHistory;
        final ImageButton btnFillQuery;

        ViewHolder(View root) {
            this.imgType = (ImageView) root.findViewById(R.id.img_suggestion_type);
            this.txtQuery = (TextView) root.findViewById(R.id.txt_suggestion_query);
            this.btnDeleteHistory = (ImageButton) root.findViewById(R.id.btn_delete_history_item);
            this.btnFillQuery = (ImageButton) root.findViewById(R.id.btn_fill_query);
        }
    }
}

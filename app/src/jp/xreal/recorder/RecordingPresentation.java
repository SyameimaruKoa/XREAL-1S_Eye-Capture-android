package jp.xreal.recorder;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Compact, high-contrast recording status for an external XREAL display. */
final class RecordingPresentation extends Presentation {
    private TextView headline;
    private TextView elapsed;
    private TextView audio;
    private TextView rate;
    private LinearLayout content;

    RecordingPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.black);
        }
        LinearLayout root = new LinearLayout(getContext());
        content = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int padding = (int) (24 * getContext().getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        headline = label(72, Color.WHITE, true);
        elapsed = label(58, Color.WHITE, true);
        audio = label(32, Color.LTGRAY, false);
        rate = label(32, Color.LTGRAY, false);
        root.addView(headline);
        root.addView(elapsed);
        root.addView(rate);
        root.addView(audio);
        setContentView(root);
    }

    private TextView label(float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    void updateStatus(String state, boolean stopping, boolean silence, long elapsedSeconds,
                      String rateSummary) {
        if (headline == null) {
            return;
        }
        // Shift the illuminated pixels slightly each minute on OLED displays.
        float offset = 6 * getContext().getResources().getDisplayMetrics().density;
        int position = (int) ((elapsedSeconds / 60) % 4);
        content.setTranslationX((position == 0 || position == 3) ? -offset : offset);
        content.setTranslationY(position < 2 ? -offset : offset);
        boolean error = state != null && (state.contains("エラー") || state.contains("失敗")
                || state.contains("停止:"));
        boolean ready = state != null && state.startsWith("録画中");
        if (error) {
            headline.setText("録画エラー");
            headline.setTextColor(Color.rgb(255, 100, 100));
        } else if (stopping) {
            headline.setText("保存中");
            headline.setTextColor(Color.WHITE);
        } else if (ready) {
            headline.setText("● 録画中");
            headline.setTextColor(Color.rgb(255, 95, 95));
        } else {
            headline.setText("準備中");
            headline.setTextColor(Color.WHITE);
        }
        long hours = elapsedSeconds / 3600;
        long minutes = (elapsedSeconds / 60) % 60;
        long seconds = elapsedSeconds % 60;
        elapsed.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds));
        rate.setText(ready ? rateSummary : "");
        if (error) {
            audio.setText("スマートフォンで詳細を確認");
            audio.setTextColor(Color.rgb(255, 185, 80));
        } else if (silence) {
            audio.setText("音声が3秒以上ゼロです");
            audio.setTextColor(Color.rgb(255, 185, 80));
        } else if (ready) {
            audio.setText("USBマイク · モノラル");
            audio.setTextColor(Color.LTGRAY);
        } else {
            audio.setText("");
        }
    }
}

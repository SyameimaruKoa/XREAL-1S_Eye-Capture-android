package jp.xreal.recorder;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.View;
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
    private WaveformView waveform;
    private final boolean sideMode;

    RecordingPresentation(Context context, Display display, boolean sideMode) {
        super(context, display);
        this.sideMode = sideMode;
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
        // Side View shrinks the whole external display into a small floating window,
        // so the same text sizes become hard to read. Center the status in that window
        // and scale it up instead of shrinking it.
        root.setGravity(sideMode ? Gravity.CENTER : Gravity.RIGHT | Gravity.TOP);
        root.setBackgroundColor(Color.BLACK);
        int padding = (int) ((sideMode ? 32 : 24)
                * getContext().getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        headline = label(sideMode ? 156 : 56, Color.WHITE, true);
        elapsed = label(sideMode ? 100 : 34, Color.WHITE, true);
        audio = label(sideMode ? 56 : 20, Color.LTGRAY, false);
        rate = label(sideMode ? 56 : 20, Color.LTGRAY, false);
        int contentWidth = sideMode ? 1320 : 420;
        int waveformHeight = sideMode ? 220 : 64;
        root.addView(headline, new LinearLayout.LayoutParams(contentWidth, -2));
        root.addView(elapsed, new LinearLayout.LayoutParams(contentWidth, -2));
        root.addView(rate, new LinearLayout.LayoutParams(contentWidth, -2));
        waveform = new WaveformView(getContext());
        root.addView(waveform, new LinearLayout.LayoutParams(contentWidth, waveformHeight));
        root.addView(audio, new LinearLayout.LayoutParams(contentWidth, -2));
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

    void updateStatus(String state, boolean stopping, boolean silence, boolean exactZero,
                      int audioPeak, long elapsedSeconds,
                      String rateSummary) {
        if (headline == null) {
            return;
        }
        // Shift the illuminated pixels slightly each minute on OLED displays.
        float offset = (sideMode ? 12 : 6) * getContext().getResources().getDisplayMetrics().density;
        int position = (int) ((elapsedSeconds / 60) % 4);
        content.setTranslationX((position == 0 || position == 3) ? -offset : offset);
        content.setTranslationY(position < 2 ? -offset : offset);
        boolean audioFailed = state != null && state.startsWith("録画中: 音声停止");
        boolean error = !audioFailed && state != null && (state.contains("エラー") || state.contains("失敗")
                || state.contains("停止:"));
        boolean ready = state != null && state.startsWith("録画中");
        if (error) {
            headline.setText("録画エラー");
            headline.setTextColor(Color.rgb(255, 100, 100));
        } else if (stopping) {
            headline.setText("保存中");
            headline.setTextColor(Color.WHITE);
        } else if (ready) {
            headline.setText("● REC");
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
        waveform.addPeak(audioPeak);
        if (audioFailed) {
            audio.setText("音声停止・映像のみ録画中");
            audio.setTextColor(Color.rgb(255, 185, 80));
        } else if (error) {
            audio.setText("スマートフォンで詳細を確認");
            audio.setTextColor(Color.rgb(255, 185, 80));
        } else if (exactZero) {
            audio.setText("音声データが完全にゼロです");
            audio.setTextColor(Color.rgb(255, 185, 80));
        } else if (silence) {
            audio.setText("音声入力が非常に小さい状態です");
            audio.setTextColor(Color.rgb(255, 185, 80));
        } else if (ready) {
            audio.setText("USBマイク · モノラル");
            audio.setTextColor(Color.LTGRAY);
        } else {
            audio.setText("");
        }
    }

    static final class WaveformView extends View {
        private final int[] peaks = new int[48];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int next;

        WaveformView(Context context) {
            super(context);
            paint.setColor(Color.LTGRAY);
            paint.setStrokeWidth(4);
        }

        void addPeak(int peak) {
            peaks[next] = peak;
            next = (next + 1) % peaks.length;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            // Keep the bars visible when the view is scaled up for Side View.
            paint.setStrokeWidth(Math.max(4.0f, width / (float) peaks.length * 0.5f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float center = getHeight() / 2.0f;
            for (int i = 0; i < peaks.length; i++) {
                int peak = peaks[(next + i) % peaks.length];
                float amplitude = (float) Math.sqrt(Math.min(32768, peak) / 32768.0)
                        * (center - 3);
                float x = (i + 0.5f) * getWidth() / peaks.length;
                canvas.drawLine(x, center - amplitude, x, center + amplitude, paint);
            }
        }
    }
}

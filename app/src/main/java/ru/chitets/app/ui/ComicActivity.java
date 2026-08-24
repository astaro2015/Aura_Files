package ru.chitets.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ru.chitets.app.comic.ComicArchive;
import ru.chitets.app.store.LibraryStore;
import ru.chitets.app.store.ReadingPrefs;

public final class ComicActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final List<File> pages = new ArrayList<>();
    private ComicImageView imageView;
    private ProgressBar loading;
    private SeekBar pageSeek;
    private TextView pageView;
    private TextView rtlButton;
    private TextView nightButton;
    private Bitmap currentBitmap;
    private int currentPage;
    private String uriText;
    private String format;
    private boolean rtl;
    private boolean night;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uriText = getIntent().getStringExtra(BookReaderContract.EXTRA_URI);
        format = getIntent().getStringExtra(BookReaderContract.EXTRA_FORMAT);
        if (uriText == null || format == null) { finish(); return; }
        String key = Integer.toHexString(uriText.hashCode());
        rtl = getSharedPreferences("comic_ui_v1", MODE_PRIVATE).getBoolean("rtl_" + key, false);
        night = getSharedPreferences("comic_ui_v1", MODE_PRIVATE).getBoolean("night_" + key, false);
        View content = buildContent();
        setContentView(content);
        Ui.fitSystemBars(this, content);
        loadArchive();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.PAPER);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        toolbar.setBackgroundColor(0xfff1e7d8);
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        TextView back = Ui.action(this, "‹", false);
        back.setTextSize(30);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        TextView title = Ui.text(this, getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE), 16, Ui.INK);
        title.setMaxLines(1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        rtlButton = Ui.action(this, rtl ? "←M" : "M→", false);
        rtlButton.setTextSize(12);
        rtlButton.setContentDescription("Направление чтения");
        rtlButton.setOnClickListener(v -> {
            rtl = !rtl;
            saveUi();
            rtlButton.setText(rtl ? "←M" : "M→");
        });
        toolbar.addView(rtlButton, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 44)));

        nightButton = Ui.action(this, night ? "☾" : "☀", false);
        nightButton.setContentDescription("Инверсия для ночного чтения");
        nightButton.setOnClickListener(v -> {
            night = !night;
            saveUi();
            nightButton.setText(night ? "☾" : "☀");
            applyNight();
        });
        toolbar.addView(nightButton, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        pageView = Ui.text(this, "—", 12, Ui.MUTED);
        pageView.setGravity(Gravity.CENTER);
        toolbar.addView(pageView, new LinearLayout.LayoutParams(Ui.dp(this, 78), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        imageView = new ComicImageView(this);
        imageView.setBackgroundColor(0xff171717);
        imageView.setPageListener(direction -> changePage(direction));
        stage.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(this);
        stage.addView(loading, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        footer.setBackgroundColor(0xfff1e7d8);
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));

        TextView prev = Ui.action(this, "‹", false);
        prev.setOnClickListener(v -> changePage(-1));
        footer.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.MATCH_PARENT));

        pageSeek = new SeekBar(this);
        pageSeek.setMax(0);
        footer.addView(pageSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pageSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !pages.isEmpty()) pageView.setText((progress + 1) + " / " + pages.size());
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { showPage(seekBar.getProgress()); }
        });

        TextView next = Ui.action(this, "›", false);
        next.setOnClickListener(v -> changePage(1));
        footer.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void loadArchive() {
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<File> loaded = ComicArchive.prepare(this, Uri.parse(uriText), format);
                handler.post(() -> {
                    if (isFinishing()) return;
                    pages.clear();
                    pages.addAll(loaded);
                    if (!pages.isEmpty()) {
                        new LibraryStore(this).updateMetadata(uriText, null, null, null, Uri.fromFile(pages.get(0)).toString());
                    }
                    pageSeek.setMax(Math.max(0, pages.size() - 1));
                    currentPage = Math.min(ReadingPrefs.getComicPage(this, uriText), pages.size() - 1);
                    showPage(Math.max(0, currentPage));
                });
            } catch (Exception error) {
                handler.post(() -> showError(error));
            }
        });
    }

    private void showError(Exception error) {
        loading.setVisibility(View.GONE);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        new AlertDialog.Builder(this)
                .setTitle("Не удалось открыть " + format)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Назад", (dialog, which) -> finish())
                .show();
    }

    private void changePage(int visualDirection) {
        if (pages.isEmpty()) return;
        int direction = rtl ? -visualDirection : visualDirection;
        showPage(Math.max(0, Math.min(pages.size() - 1, currentPage + direction)));
    }

    private void showPage(int index) {
        if (index < 0 || index >= pages.size()) return;
        currentPage = index;
        pageSeek.setProgress(index);
        pageView.setText((index + 1) + " / " + pages.size());
        loading.setVisibility(View.VISIBLE);
        int gen = generation.incrementAndGet();
        File file = pages.get(index);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        executor.execute(() -> {
            Bitmap bitmap = decodeSampled(file, Math.min(2600, screenW * 3), Math.min(3600, screenH * 3));
            handler.post(() -> {
                if (gen != generation.get() || bitmap == null || isFinishing()) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                Bitmap old = currentBitmap;
                currentBitmap = bitmap;
                imageView.setImageBitmap(bitmap);
                imageView.resetZoom();
                applyNight();
                if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
                loading.setVisibility(View.GONE);
                ReadingPrefs.setComicPage(this, uriText, currentPage, pages.size());
            });
        });
    }

    private static Bitmap decodeSampled(File file, int reqW, int reqH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    private void applyNight() {
        if (imageView == null) return;
        if (!night) {
            imageView.clearColorFilter();
            return;
        }
        ColorMatrix matrix = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
        imageView.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    private void saveUi() {
        String key = Integer.toHexString(uriText.hashCode());
        getSharedPreferences("comic_ui_v1", MODE_PRIVATE).edit()
                .putBoolean("rtl_" + key, rtl)
                .putBoolean("night_" + key, night)
                .apply();
    }

    @Override
    protected void onPause() {
        if (!pages.isEmpty()) ReadingPrefs.setComicPage(this, uriText, currentPage, pages.size());
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet();
        executor.shutdownNow();
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        super.onDestroy();
    }

    private static final class ComicImageView extends ImageView {
        interface PageListener { void onPage(int direction); }
        private final Matrix imageMatrixValue = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private float scale = 1f;
        private float translateX;
        private float translateY;
        private float lastX;
        private float lastY;
        private PageListener pageListener;

        ComicImageView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.FIT_CENTER);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    scale = Math.max(1f, Math.min(6f, scale * detector.getScaleFactor()));
                    if (scale <= 1.01f) { scale = 1f; translateX = 0; translateY = 0; }
                    applyMatrix();
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (scale > 1.02f || pageListener == null) return false;
                    float third = getWidth() / 3f;
                    if (e.getX() < third) pageListener.onPage(-1);
                    else if (e.getX() > third * 2f) pageListener.onPage(1);
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.02f) resetZoom(); else { scale = 2.3f; applyMatrix(); }
                    return true;
                }
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (scale <= 1.02f && Math.abs(velocityX) > Math.abs(velocityY)
                            && Math.abs(velocityX) > 650f && pageListener != null) {
                        pageListener.onPage(velocityX < 0 ? 1 : -1);
                        return true;
                    }
                    return false;
                }
            });
        }

        void setPageListener(PageListener listener) { pageListener = listener; }

        void resetZoom() {
            scale = 1f; translateX = 0f; translateY = 0f;
            setScaleType(ScaleType.FIT_CENTER);
            setImageMatrix(new Matrix());
        }

        private void applyMatrix() {
            setScaleType(ScaleType.MATRIX);
            imageMatrixValue.reset();
            imageMatrixValue.setScale(scale, scale, getWidth() / 2f, getHeight() / 2f);
            imageMatrixValue.postTranslate(translateX, translateY);
            setImageMatrix(imageMatrixValue);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX(); lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress() && scale > 1f) {
                translateX += event.getX() - lastX;
                translateY += event.getY() - lastY;
                lastX = event.getX(); lastY = event.getY();
                applyMatrix();
            }
            return true;
        }
    }
}

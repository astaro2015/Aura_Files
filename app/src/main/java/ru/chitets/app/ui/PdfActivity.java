package ru.chitets.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ru.chitets.app.model.TocEntry;
import ru.chitets.app.parser.PdfReflowParser;
import ru.chitets.app.store.ReadingPrefs;

public final class PdfActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger renderGeneration = new AtomicInteger();
    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private ZoomImageView imageView;
    private TextView pageView;
    private TextView nightButton;
    private TextView spreadButton;
    private TextView cropButton;
    private SeekBar pageSeek;
    private ProgressBar loading;
    private Bitmap currentBitmap;
    private int currentPage;
    private String uriText;
    private boolean night;
    private boolean spread;
    private int cropPercent;
    private List<TocEntry> tocEntries;
    private boolean tocLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uriText = getIntent().getStringExtra(BookReaderContract.EXTRA_URI);
        if (uriText == null) { finish(); return; }
        loadUiPrefs();
        View content = buildContent();
        setContentView(content);
        Ui.fitSystemBars(this, content);
        openPdf();
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
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 46)));

        TextView title = Ui.text(this, getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE), 16, Ui.INK);
        title.setMaxLines(1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView toc = Ui.action(this, "☰", false);
        toc.setTextSize(20);
        toc.setContentDescription("Оглавление PDF");
        toc.setOnClickListener(v -> showToc());
        toolbar.addView(toc, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 44)));

        TextView textMode = Ui.action(this, "Умный", false);
        textMode.setTextSize(9.8f);
        textMode.setContentDescription("Умный PDF: адаптивный текст с оригинальными сложными фрагментами");
        textMode.setOnClickListener(v -> openTextMode());
        toolbar.addView(textMode, new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 44)));

        cropButton = Ui.action(this, cropLabel(), false);
        cropButton.setTextSize(11);
        cropButton.setContentDescription("Обрезка полей PDF");
        cropButton.setOnClickListener(v -> {
            cropPercent = cropPercent == 0 ? 3 : cropPercent == 3 ? 6 : cropPercent == 6 ? 10 : 0;
            cropButton.setText(cropLabel());
            saveUiPrefs();
            showPage(currentPage);
        });
        toolbar.addView(cropButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));

        spreadButton = Ui.action(this, spread ? "2стр" : "1стр", false);
        spreadButton.setTextSize(11);
        spreadButton.setContentDescription("Одностраничный или разворот");
        spreadButton.setOnClickListener(v -> {
            spread = !spread;
            spreadButton.setText(spread ? "2стр" : "1стр");
            saveUiPrefs();
            showPage(currentPage);
        });
        toolbar.addView(spreadButton, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 44)));

        nightButton = Ui.action(this, night ? "☾" : "☀", false);
        nightButton.setContentDescription("Ночной режим PDF");
        nightButton.setOnClickListener(v -> {
            night = !night;
            nightButton.setText(night ? "☾" : "☀");
            saveUiPrefs();
            applyNight();
        });
        toolbar.addView(nightButton, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 44)));

        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(0xff292724);
        imageView.setSwipeListener(direction -> changePage(direction));
        stage.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(this);
        stage.addView(loading, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        footer.setBackgroundColor(0xfff1e7d8);
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        TextView prev = Ui.action(this, "‹", false);
        prev.setOnClickListener(v -> changePage(-1));
        footer.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.MATCH_PARENT));

        pageView = Ui.text(this, "—", 11.5f, Ui.MUTED);
        pageView.setGravity(Gravity.CENTER);
        footer.addView(pageView, new LinearLayout.LayoutParams(Ui.dp(this, 78), ViewGroup.LayoutParams.MATCH_PARENT));

        pageSeek = new SeekBar(this);
        footer.addView(pageSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pageSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && renderer != null) pageView.setText(pageLabel(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { showPage(seekBar.getProgress()); }
        });

        TextView next = Ui.action(this, "›", false);
        next.setOnClickListener(v -> changePage(1));
        footer.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private String cropLabel() { return cropPercent == 0 ? "поля" : "−" + cropPercent + "%"; }

    private void openTextMode() {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(BookReaderContract.EXTRA_URI, uriText);
        intent.putExtra(BookReaderContract.EXTRA_TITLE, getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE));
        intent.putExtra(BookReaderContract.EXTRA_FORMAT, "PDF");
        intent.putExtra(BookReaderContract.EXTRA_PDF_PAGE, currentPage);
        startActivity(intent);
        finish();
    }

    private String pageLabel(int pageIndex) {
        if (renderer == null) return "—";
        int count = renderer.getPageCount();
        if (spread && pageIndex + 1 < count) return (pageIndex + 1) + "–" + (pageIndex + 2) + " / " + count;
        return (pageIndex + 1) + " / " + count;
    }

    private void openPdf() {
        try {
            descriptor = getContentResolver().openFileDescriptor(Uri.parse(uriText), "r");
            if (descriptor == null) throw new IOException("Не удалось открыть PDF");
            renderer = new PdfRenderer(descriptor);
            if (renderer.getPageCount() == 0) throw new IOException("В PDF нет страниц");
            pageSeek.setMax(Math.max(0, renderer.getPageCount() - 1));
            int requestedPage = getIntent().getIntExtra(BookReaderContract.EXTRA_PDF_PAGE, -1);
            if (requestedPage >= 0) {
                currentPage = Math.max(0, Math.min(renderer.getPageCount() - 1, requestedPage));
            } else {
                int savedPage = Math.min(ReadingPrefs.getPdfPage(this, uriText), renderer.getPageCount() - 1);
                float savedProgress = ReadingPrefs.getProgress(this, uriText);
                int progressPage = Math.round(savedProgress * Math.max(0, renderer.getPageCount() - 1));
                currentPage = Math.max(0, Math.min(renderer.getPageCount() - 1,
                        Math.abs(progressPage - savedPage) > 1 ? progressPage : savedPage));
            }
            showPage(currentPage);
        } catch (Exception error) {
            loading.setVisibility(View.GONE);
            new AlertDialog.Builder(this)
                    .setTitle("Не удалось открыть PDF")
                    .setMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                    .setCancelable(false)
                    .setPositiveButton("Назад", (dialog, which) -> finish())
                    .show();
        }
    }

    private void showToc() {
        if (tocEntries != null) { showTocDialog(tocEntries); return; }
        if (tocLoading) return;
        tocLoading = true;
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<TocEntry> parsed = new ArrayList<>();
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(uriText))) {
                if (in != null) parsed = PdfReflowParser.parseOutline(in);
            } catch (Exception ignored) {}
            List<TocEntry> result = parsed;
            handler.post(() -> {
                tocLoading = false;
                loading.setVisibility(View.GONE);
                tocEntries = result;
                showTocDialog(result);
            });
        });
    }

    private void showTocDialog(List<TocEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Оглавление PDF")
                    .setMessage("Встроенное оглавление (PDF Bookmarks/Outlines) не найдено.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            TocEntry e = entries.get(i);
            StringBuilder indent = new StringBuilder();
            for (int n = 0; n < Math.min(3, e.level); n++) indent.append("    ");
            labels[i] = indent + e.title;
        }
        new AlertDialog.Builder(this).setTitle("Оглавление PDF")
                .setItems(labels, (dialog, which) -> {
                    int page = pageFromAnchor(entries.get(which).anchor);
                    if (page >= 0) showPage(page);
                })
                .setNegativeButton("Закрыть", null).show();
    }

    private static int pageFromAnchor(String anchor) {
        if (anchor == null || !anchor.startsWith("pdf-page-")) return -1;
        try { return Math.max(0, Integer.parseInt(anchor.substring("pdf-page-".length())) - 1); }
        catch (Exception ignored) { return -1; }
    }

    private void changePage(int direction) {
        if (renderer == null) return;
        int step = spread ? 2 : 1;
        showPage(Math.max(0, Math.min(renderer.getPageCount() - 1, currentPage + direction * step)));
    }

    private void showPage(int pageIndex) {
        if (renderer == null || pageIndex < 0 || pageIndex >= renderer.getPageCount()) return;
        currentPage = pageIndex;
        pageSeek.setProgress(pageIndex);
        pageView.setText(pageLabel(pageIndex));
        loading.setVisibility(View.VISIBLE);
        int generation = renderGeneration.incrementAndGet();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        boolean renderSpread = spread && pageIndex + 1 < renderer.getPageCount();
        executor.execute(() -> {
            Bitmap result = null;
            try {
                Bitmap first = renderOne(pageIndex, renderSpread ? Math.max(500, screenWidth) : Math.max(800, screenWidth * 2));
                if (renderSpread) {
                    Bitmap second = renderOne(pageIndex + 1, Math.max(500, screenWidth));
                    result = combine(first, second);
                    first.recycle();
                    second.recycle();
                } else {
                    result = first;
                }
            } catch (Exception ignored) {
            }
            Bitmap finalResult = result;
            handler.post(() -> {
                if (generation != renderGeneration.get() || finalResult == null || isFinishing()) {
                    if (finalResult != null && !finalResult.isRecycled()) finalResult.recycle();
                    return;
                }
                Bitmap old = currentBitmap;
                currentBitmap = finalResult;
                imageView.setImageBitmap(finalResult);
                imageView.onNewPage();
                applyNight();
                if (old != null && old != finalResult && !old.isRecycled()) old.recycle();
                loading.setVisibility(View.GONE);
                ReadingPrefs.setPdfPage(this, uriText, currentPage, renderer.getPageCount());
            });
        });
    }

    private Bitmap renderOne(int pageIndex, int targetWidth) throws Exception {
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            int width = Math.min(2000, Math.max(targetWidth, page.getWidth()));
            int height = Math.max(1, Math.round(width * ((float) page.getHeight() / page.getWidth())));
            if (height > 3400) {
                width = Math.max(1, Math.round(width * (3400f / height)));
                height = 3400;
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            if (cropPercent <= 0) return bitmap;
            int cropX = Math.round(bitmap.getWidth() * cropPercent / 100f);
            int cropY = Math.round(bitmap.getHeight() * cropPercent / 100f);
            int croppedW = bitmap.getWidth() - cropX * 2;
            int croppedH = bitmap.getHeight() - cropY * 2;
            if (croppedW < 50 || croppedH < 50) return bitmap;
            Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, croppedW, croppedH);
            if (cropped != bitmap) bitmap.recycle();
            return cropped;
        }
    }

    private static Bitmap combine(Bitmap left, Bitmap right) {
        int gap = 8;
        int height = Math.max(left.getHeight(), right.getHeight());
        int width = left.getWidth() + gap + right.getWidth();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.eraseColor(Color.DKGRAY);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(left, 0, (height - left.getHeight()) / 2f, null);
        canvas.drawBitmap(right, left.getWidth() + gap, (height - right.getHeight()) / 2f, null);
        return result;
    }

    private void applyNight() {
        if (imageView == null) return;
        if (!night) { imageView.clearColorFilter(); return; }
        ColorMatrix matrix = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
        imageView.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    private void loadUiPrefs() {
        String key = Integer.toHexString(uriText.hashCode());
        android.content.SharedPreferences p = getSharedPreferences("pdf_ui_v2", MODE_PRIVATE);
        night = p.getBoolean("night_" + key, false);
        spread = p.getBoolean("spread_" + key, false);
        cropPercent = p.getInt("crop_" + key, 0);
    }

    private void saveUiPrefs() {
        String key = Integer.toHexString(uriText.hashCode());
        getSharedPreferences("pdf_ui_v2", MODE_PRIVATE).edit()
                .putBoolean("night_" + key, night)
                .putBoolean("spread_" + key, spread)
                .putInt("crop_" + key, cropPercent)
                .apply();
    }

    @Override
    protected void onPause() {
        if (renderer != null) ReadingPrefs.setPdfPage(this, uriText, currentPage, renderer.getPageCount());
        saveUiPrefs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        renderGeneration.incrementAndGet();
        executor.shutdownNow();
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private static final class ZoomImageView extends ImageView {
        interface SwipeListener { void onSwipe(int direction); }
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private float scale = 1f;
        private float translateX;
        private float translateY;
        private float lastX;
        private float lastY;
        private SwipeListener swipeListener;

        ZoomImageView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.FIT_CENTER);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    scale = Math.max(1f, Math.min(5f, scale * detector.getScaleFactor()));
                    if (scale <= 1.01f) { scale = 1f; translateX = 0f; translateY = 0f; }
                    applyMatrix();
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1f) resetZoom(); else { scale = 2.2f; applyMatrix(); }
                    return true;
                }
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (scale <= 1.02f && Math.abs(velocityX) > Math.abs(velocityY)
                            && Math.abs(velocityX) > 700f && swipeListener != null) {
                        swipeListener.onSwipe(velocityX < 0 ? 1 : -1);
                        return true;
                    }
                    return false;
                }
            });
        }

        void setSwipeListener(SwipeListener listener) { swipeListener = listener; }

        void resetZoom() {
            scale = 1f; translateX = 0f; translateY = 0f;
            setScaleType(ScaleType.FIT_CENTER);
            setImageMatrix(new Matrix());
        }

        void onNewPage() {
            translateX = 0f;
            translateY = 0f;
            if (scale <= 1.02f) resetZoom(); else post(this::applyMatrix);
        }

        private void applyMatrix() {
            setScaleType(ScaleType.MATRIX);
            matrix.reset();
            matrix.setScale(scale, scale, getWidth() / 2f, getHeight() / 2f);
            matrix.postTranslate(translateX, translateY);
            setImageMatrix(matrix);
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

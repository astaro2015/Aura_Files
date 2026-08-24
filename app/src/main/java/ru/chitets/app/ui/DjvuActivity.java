package ru.chitets.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.chitets.app.djvu.DjvuDocument;
import ru.chitets.app.djvu.DjvuException;
import ru.chitets.app.djvu.DjvuRenderer;
import ru.chitets.app.store.ReadingPrefs;

/** Native pure-Java DjVu reader. No WebView/Rust/WASM runtime is required. */
public final class DjvuActivity extends Activity {
    private static final int MAX_BOOK_BYTES = 512 * 1024 * 1024;

    private Uri uri;
    private String uriText;
    private String title;
    private ZoomImageView imageView;
    private TextView statusView, pageView, nightButton, fitButton, textButton;
    private SeekBar pageSeek;
    private ExecutorService worker;
    private DjvuDocument document;
    private Bitmap pageBitmap;
    private boolean seeking;
    private boolean night;
    private String fitMode;
    private int pageCount;
    private int currentPage;
    private int renderSerial;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String raw = getIntent().getStringExtra(BookReaderContract.EXTRA_URI);
        uri = raw == null ? null : Uri.parse(raw);
        uriText = uri == null ? "" : uri.toString();
        title = getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE);
        if (title == null || title.trim().isEmpty()) title = "DjVu";
        night = ReadingPrefs.getDjvuNight(this, uriText);
        fitMode = ReadingPrefs.getDjvuFitMode(this, uriText);
        currentPage = Math.max(0, ReadingPrefs.getDjvuPage(this, uriText));
        int requestedPage = getIntent().getIntExtra(BookReaderContract.EXTRA_DJVU_PAGE, -1);
        if (requestedPage >= 0) currentPage = requestedPage;
        worker = Executors.newSingleThreadExecutor();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(30, 30, 30));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        top.setBackgroundColor(Ui.PAPER);

        TextView back = Ui.action(this, "‹", false);
        back.setTextSize(28); back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 46)));

        TextView heading = Ui.text(this, title, 17, Ui.INK);
        heading.setMaxLines(1);
        top.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        textButton = Ui.action(this, "Текст", false);
        textButton.setTextSize(10.5f);
        textButton.setEnabled(false);
        textButton.setAlpha(.38f);
        textButton.setContentDescription("Текстовый слой DjVu ещё проверяется");
        textButton.setOnClickListener(v -> openTextMode());
        top.addView(textButton, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 44)));

        fitButton = Ui.action(this, "page".equals(fitMode) ? "▣" : "↔", false);
        fitButton.setContentDescription("Вписать страницу / по ширине");
        fitButton.setOnClickListener(v -> toggleFit());
        top.addView(fitButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));

        nightButton = Ui.action(this, night ? "☀" : "☾", false);
        nightButton.setContentDescription("Ночной режим DjVu");
        nightButton.setOnClickListener(v -> toggleNight());
        top.addView(nightButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));

        TextView external = Ui.action(this, "↗", false);
        external.setContentDescription("Открыть во внешнем DjVu-просмотрщике");
        external.setOnClickListener(v -> openExternal());
        top.addView(external, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        root.addView(top);

        FrameLayout pageFrame = new FrameLayout(this);
        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(Color.rgb(36, 36, 36));
        imageView.setFitMode(fitMode);
        imageView.setPageSwipeListener(delta -> goRelative(delta));
        pageFrame.addView(imageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = Ui.text(this, "Разбираю DjVu…", 15, Color.WHITE);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        pageFrame.addView(statusView, statusLp);
        root.addView(pageFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        bottom.setBackgroundColor(Ui.PAPER);

        TextView prev = Ui.action(this, "‹", false); prev.setTextSize(27); prev.setOnClickListener(v -> goRelative(-1));
        bottom.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));

        pageSeek = new SeekBar(this); pageSeek.setMax(0);
        pageSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && pageCount > 0) pageView.setText((progress + 1) + " / " + pageCount);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seeking = false; goToPage(bar.getProgress()); }
        });
        bottom.addView(pageSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pageView = Ui.text(this, "…", 14, Ui.INK); pageView.setGravity(Gravity.CENTER);
        bottom.addView(pageView, new LinearLayout.LayoutParams(Ui.dp(this, 82), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView next = Ui.action(this, "›", false); next.setTextSize(27); next.setOnClickListener(v -> goRelative(1));
        bottom.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        root.addView(bottom);

        setContentView(root);
        Ui.fitSystemBars(this, root);

        if (uri == null) showFatal("Не передан файл DjVu.");
        else loadDocument();
    }

    private void loadDocument() {
        statusView.setText("Разбираю DjVu…");
        worker.execute(() -> {
            try {
                byte[] bytes = readBook(uri);
                DjvuDocument parsed = new DjvuDocument(bytes);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    document = parsed;
                    pageCount = parsed.pageCount();
                    currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
                    pageSeek.setMax(Math.max(0, pageCount - 1));
                    pageSeek.setProgress(currentPage);
                    pageView.setText((currentPage + 1) + " / " + pageCount);
                    boolean hasText = parsed.hasTextLayer();
                    textButton.setEnabled(hasText);
                    textButton.setAlpha(hasText ? 1f : .38f);
                    textButton.setContentDescription(hasText
                            ? "Открыть встроенный текстовый слой DjVu (" + parsed.textPageCount() + " из " + pageCount + " страниц)"
                            : "В этом DjVu нет встроенного текстового слоя");
                    renderPage(currentPage);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> showFatal(readable(error)));
            }
        });
    }

    private byte[] readBook(Uri source) throws IOException, DjvuException {
        try (InputStream in = getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("Не удалось открыть поток файла");
            ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024);
            byte[] buffer = new byte[128 * 1024];
            int total = 0, n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > MAX_BOOK_BYTES) throw new DjvuException("DjVu: файл больше 512 МБ; текущий Java-декодер не загружает такие книги целиком в память");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void openTextMode() {
        DjvuDocument doc = document;
        if (doc == null || !doc.hasTextLayer()) {
            Toast.makeText(this, "В этом DjVu нет встроенного текстового слоя", Toast.LENGTH_SHORT).show();
            return;
        }
        ReadingPrefs.setDjvuPage(this, uriText, currentPage, Math.max(1, pageCount));
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(BookReaderContract.EXTRA_URI, uriText);
        intent.putExtra(BookReaderContract.EXTRA_TITLE, title);
        intent.putExtra(BookReaderContract.EXTRA_FORMAT, "DJVU");
        intent.putExtra(BookReaderContract.EXTRA_DJVU_PAGE, currentPage);
        startActivity(intent);
        finish();
    }

    private void renderPage(int page) {
        DjvuDocument doc = document;
        if (doc == null || page < 0 || page >= pageCount) return;
        int serial = ++renderSerial;
        statusView.setVisibility(TextView.VISIBLE);
        statusView.setText("Декодирую страницу " + (page + 1) + "…");
        // Free the previous full-resolution page before allocating decoder
        // buffers for the next one. Keeping both at once wastes tens of MB.
        imageView.setImageDrawable(null);
        recycleCurrentBitmap();
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        int maxWidth = Math.min(1800, Math.max(900, (displayWidth * 3) / 2));
        worker.execute(() -> {
            try {
                Bitmap rendered = DjvuRenderer.renderPage(doc, page, maxWidth, night);
                runOnUiThread(() -> {
                    if (isFinishing() || serial != renderSerial) { rendered.recycle(); return; }
                    recycleCurrentBitmap();
                    pageBitmap = rendered;
                    imageView.setImageBitmap(rendered);
                    imageView.setFitMode(fitMode);
                    statusView.setVisibility(TextView.GONE);
                    currentPage = page;
                    if (!seeking) pageSeek.setProgress(page);
                    pageView.setText((page + 1) + " / " + pageCount);
                    ReadingPrefs.setDjvuPage(this, uriText, page, pageCount);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> { if (serial == renderSerial) showFatal("Страница " + (page + 1) + ": " + readable(error)); });
            }
        });
    }

    private void goRelative(int delta) { if (pageCount > 0) goToPage(Math.max(0, Math.min(pageCount - 1, currentPage + delta))); }
    private void goToPage(int page) { if (pageCount > 0) renderPage(Math.max(0, Math.min(pageCount - 1, page))); }

    private void toggleNight() {
        night = !night;
        ReadingPrefs.setDjvuNight(this, uriText, night);
        nightButton.setText(night ? "☀" : "☾");
        if (document != null) renderPage(currentPage);
    }

    private void toggleFit() {
        fitMode = "page".equals(fitMode) ? "width" : "page";
        ReadingPrefs.setDjvuFitMode(this, uriText, fitMode);
        fitButton.setText("page".equals(fitMode) ? "▣" : "↔");
        imageView.setFitMode(fitMode);
    }

    private void showFatal(String message) {
        if (isFinishing()) return;
        statusView.setVisibility(TextView.GONE);
        new AlertDialog.Builder(this)
                .setTitle("Не удалось открыть DjVu")
                .setMessage(message == null || message.trim().isEmpty() ? "Неизвестная ошибка DjVu." : message)
                .setPositiveButton("Открыть внешне", (d, w) -> openExternal())
                .setNegativeButton("Назад", (d, w) -> finish())
                .setCancelable(false).show();
    }

    private static String readable(Throwable error) {
        Throwable e = error;
        while (e.getCause() != null && (e.getMessage() == null || e.getMessage().trim().isEmpty())) e = e.getCause();
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private void openExternal() {
        if (uri == null) return;
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "image/vnd.djvu");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(view, "Открыть DjVu в…");
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(this, DjvuActivity.class)});
        }
        try { startActivity(chooser); }
        catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setTitle("Нет внешнего DjVu-просмотрщика")
                    .setMessage("Внешнее приложение для DjVu не найдено.").setPositiveButton("OK", null).show();
        }
    }

    private void recycleCurrentBitmap() {
        if (pageBitmap != null && !pageBitmap.isRecycled()) pageBitmap.recycle();
        pageBitmap = null;
    }

    @Override protected void onDestroy() {
        renderSerial++;
        if (worker != null) worker.shutdownNow();
        imageView.setImageDrawable(null);
        recycleCurrentBitmap();
        super.onDestroy();
    }

    /** Matrix ImageView with pinch/double-tap zoom and one-page horizontal fling at base zoom. */
    private static final class ZoomImageView extends ImageView {
        interface PageSwipeListener { void onSwipe(int delta); }
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private PageSwipeListener pageSwipeListener;
        private String fitMode = "width";
        private float baseScale = 1f, userScale = 1f;

        ZoomImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    float next = Math.max(1f, Math.min(6f, userScale * factor));
                    factor = next / userScale; userScale = next;
                    matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    setImageMatrix(matrix); return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (userScale > 1.05f) resetMatrix();
                    else { userScale = 2f; matrix.postScale(2f, 2f, e.getX(), e.getY()); setImageMatrix(matrix); }
                    return true;
                }
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (userScale > 1.01f || "width".equals(fitMode)) { matrix.postTranslate(-dx, -dy); setImageMatrix(matrix); }
                    return true;
                }
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (userScale <= 1.05f && Math.abs(vx) > Math.abs(vy) * 1.3f && Math.abs(vx) > 800f && pageSwipeListener != null) {
                        pageSwipeListener.onSwipe(vx < 0 ? 1 : -1); return true;
                    }
                    return false;
                }
            });
        }

        void setPageSwipeListener(PageSwipeListener l) { pageSwipeListener = l; }
        void setFitMode(String mode) { fitMode = "page".equals(mode) ? "page" : "width"; post(this::resetMatrix); }

        @Override public void setImageBitmap(Bitmap bm) { super.setImageBitmap(bm); post(this::resetMatrix); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w,h,ow,oh); post(this::resetMatrix); }
        @Override public boolean onTouchEvent(MotionEvent event) { scaleDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event); return true; }

        private void resetMatrix() {
            Drawable d = getDrawable(); if (d == null || getWidth() <= 0 || getHeight() <= 0) return;
            float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight(); if (iw <= 0 || ih <= 0) return;
            float sx = getWidth() / iw, sy = getHeight() / ih;
            baseScale = "page".equals(fitMode) ? Math.min(sx, sy) : sx;
            userScale = 1f;
            matrix.reset(); matrix.postScale(baseScale, baseScale);
            float shownW = iw * baseScale, shownH = ih * baseScale;
            float tx = (getWidth() - shownW) / 2f;
            float ty = "page".equals(fitMode) ? (getHeight() - shownH) / 2f : Math.min(0f, (getHeight() - shownH) / 2f);
            matrix.postTranslate(tx, ty); setImageMatrix(matrix);
        }
    }
}

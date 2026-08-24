package ru.chitets.app.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;

final class ReaderImageDialog {
    private static final int MAX_SOURCE_BYTES = 40 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 16_000_000L;

    private ReaderImageDialog() {}

    static void show(Activity activity, String source, String alt, ExecutorService executor, Handler handler) {
        if (source == null || source.trim().isEmpty()) return;

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xff080808);

        ZoomImageView image = new ZoomImageView(activity);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar progress = new ProgressBar(activity);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Ui.dp(activity, 52), Ui.dp(activity, 52), Gravity.CENTER);
        root.addView(progress, pp);

        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextColor(0xffffffff);
        close.setTextSize(32);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x66000000);
        close.setContentDescription("Закрыть изображение");
        close.setOnClickListener(v -> dialog.dismiss());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.dp(activity, 52), Ui.dp(activity, 52), Gravity.TOP | Gravity.END);
        cp.setMargins(0, Ui.dp(activity, 8), Ui.dp(activity, 8), 0);
        root.addView(close, cp);

        String caption = alt == null ? "" : alt.trim();
        if (!caption.isEmpty() && !"Иллюстрация".equalsIgnoreCase(caption)) {
            TextView label = new TextView(activity);
            label.setText(caption);
            label.setTextColor(0xffeeeeee);
            label.setTextSize(13);
            label.setMaxLines(2);
            label.setGravity(Gravity.CENTER);
            label.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), Ui.dp(activity, 16), Ui.dp(activity, 8));
            label.setBackgroundColor(0x88000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            root.addView(label, lp);
        }

        dialog.setContentView(root);
        dialog.show();

        executor.execute(() -> {
            Bitmap bitmap = null;
            String error = null;
            try {
                byte[] data = readSource(activity, source);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Не удалось определить размер изображения");
                int sample = 1;
                while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE
                        || ((long) Math.max(1, bounds.outWidth / sample) * Math.max(1, bounds.outHeight / sample)) > MAX_PIXELS) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = Math.max(1, sample);
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null) throw new IllegalArgumentException("Android не смог декодировать изображение");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            Bitmap ready = bitmap;
            String message = error;
            handler.post(() -> {
                if (!dialog.isShowing()) {
                    if (ready != null && !ready.isRecycled()) ready.recycle();
                    return;
                }
                progress.setVisibility(android.view.View.GONE);
                if (ready == null) {
                    Toast.makeText(activity, "Не удалось открыть изображение: " + message, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    return;
                }
                image.setImageBitmap(ready);
                image.post(image::resetZoom);
                dialog.setOnDismissListener(d -> {
                    image.setImageDrawable(null);
                    if (!ready.isRecycled()) ready.recycle();
                });
            });
        });
    }

    private static byte[] readSource(Activity activity, String source) throws Exception {
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma <= 0) throw new IllegalArgumentException("Некорректное data:image");
            String header = source.substring(0, comma).toLowerCase();
            String payload = source.substring(comma + 1);
            byte[] data = header.contains(";base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : Uri.decode(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (data.length > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Изображение слишком большое");
            return data;
        }

        Uri uri = Uri.parse(source);
        InputStream input;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            input = new FileInputStream(new File(uri.getPath()));
        } else {
            input = activity.getContentResolver().openInputStream(uri);
        }
        if (input == null) throw new IllegalArgumentException("Не удалось открыть изображение");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Изображение слишком большое");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static final class ZoomImageView extends ImageView {
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private float baseScale = 1f;
        private float userScale = 1f;
        private float lastX;
        private float lastY;

        ZoomImageView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float before = userScale;
                    userScale = Math.max(1f, Math.min(6f, userScale * detector.getScaleFactor()));
                    float factor = userScale / Math.max(0.0001f, before);
                    matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    setImageMatrix(matrix);
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (userScale > 1.05f) resetZoom();
                    else {
                        userScale = 2.2f;
                        matrix.postScale(2.2f, 2.2f, e.getX(), e.getY());
                        setImageMatrix(matrix);
                    }
                    return true;
                }
            });
        }

        void resetZoom() {
            if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
            int iw = getDrawable().getIntrinsicWidth();
            int ih = getDrawable().getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) return;
            float sx = getWidth() / (float) iw;
            float sy = getHeight() / (float) ih;
            baseScale = Math.min(sx, sy);
            userScale = 1f;
            float shownW = iw * baseScale;
            float shownH = ih * baseScale;
            float tx = (getWidth() - shownW) / 2f;
            float ty = (getHeight() - shownH) / 2f;
            matrix.reset();
            matrix.postScale(baseScale, baseScale);
            matrix.postTranslate(tx, ty);
            setImageMatrix(matrix);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            post(this::resetZoom);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress() && userScale > 1.01f) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                matrix.postTranslate(dx, dy);
                setImageMatrix(matrix);
            }
            return true;
        }
    }
}

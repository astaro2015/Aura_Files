package ru.chitets.app.parser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the difficult fragments discovered by PdfReflowParser from the original PDF page.
 *
 * The reflow parser deliberately stays independent from Android graphics. It writes normalized
 * crop coordinates into the generated HTML. This helper runs on Android, uses the platform
 * PdfRenderer, and materializes only the missing crops into the reflow cache. If a crop cannot
 * be rendered, ReaderActivity still has the extracted preformatted-text fallback.
 */
public final class PdfHybridRenderer {
    private static final int MAX_CROPS = 400;
    private static final int TARGET_PAGE_WIDTH = 1600;
    private static final int TARGET_PAGE_HEIGHT = 2400;
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*\\bdata-pdf-crop=\\\"([^\\\"]+)\\\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_ATTR = Pattern.compile("\\bsrc=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);

    private PdfHybridRenderer() {}

    public static int renderMissing(Context context, Uri uri, File cacheDir, String html) {
        if (context == null || uri == null || cacheDir == null || html == null || html.isEmpty()) return 0;
        List<Crop> crops = parseCrops(cacheDir, html);
        if (crops.isEmpty()) return 0;

        Map<Integer, List<Crop>> byPage = new LinkedHashMap<>();
        for (Crop crop : crops) {
            if (crop.target.isFile() && crop.target.length() > 64) continue;
            byPage.computeIfAbsent(crop.pageIndex, ignored -> new ArrayList<>()).add(crop);
        }
        if (byPage.isEmpty()) return 0;

        int written = 0;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return 0;
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                for (Map.Entry<Integer, List<Crop>> entry : byPage.entrySet()) {
                    int pageIndex = entry.getKey();
                    if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) continue;
                    try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                        int pageW = Math.max(1, page.getWidth());
                        int pageH = Math.max(1, page.getHeight());
                        float scale = Math.min(TARGET_PAGE_WIDTH / (float) pageW, TARGET_PAGE_HEIGHT / (float) pageH);
                        scale = Math.min(3.4f, scale); // Allow downscaling oversized poster/engineering pages.
                        if (!(scale > 0f) || Float.isInfinite(scale) || Float.isNaN(scale)) scale = 1f;
                        int renderW = Math.max(1, Math.round(pageW * scale));
                        int renderH = Math.max(1, Math.round(pageH * scale));
                        Bitmap full = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
                        try {
                            full.eraseColor(Color.WHITE);
                            page.render(full, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            for (Crop crop : entry.getValue()) {
                                if (writeCrop(full, crop)) written++;
                            }
                        } finally {
                            full.recycle();
                        }
                    } catch (Exception ignored) {
                        // Keep going: one malformed/unsupported page must not disable hybrid mode for the book.
                    }
                }
            }
        } catch (Exception ignored) {
            // Permission problems or PdfRenderer limitations are non-fatal: HTML fallback remains readable.
        }
        return written;
    }

    private static boolean writeCrop(Bitmap full, Crop crop) {
        int w = full.getWidth(), h = full.getHeight();
        int left = clamp((int) Math.floor(crop.x0 * w), 0, Math.max(0, w - 1));
        int top = clamp((int) Math.floor(crop.y0 * h), 0, Math.max(0, h - 1));
        int right = clamp((int) Math.ceil(crop.x1 * w), left + 1, w);
        int bottom = clamp((int) Math.ceil(crop.y1 * h), top + 1, h);
        if (right - left < 6 || bottom - top < 6) return false;

        File parent = crop.target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        File temp = new File(crop.target.getParentFile(), crop.target.getName() + ".tmp");
        Bitmap part = null;
        try {
            part = Bitmap.createBitmap(full, left, top, right - left, bottom - top);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!part.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
                out.flush();
            }
            if (crop.target.exists() && !crop.target.delete()) return false;
            if (!temp.renameTo(crop.target)) {
                try (FileOutputStream out = new FileOutputStream(crop.target)) {
                    if (!part.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
                }
                temp.delete();
            }
            return crop.target.isFile() && crop.target.length() > 64;
        } catch (Exception ignored) {
            temp.delete();
            return false;
        } finally {
            if (part != null && part != full && !part.isRecycled()) part.recycle();
        }
    }

    private static List<Crop> parseCrops(File cacheDir, String html) {
        List<Crop> out = new ArrayList<>();
        Matcher tagMatcher = IMG_TAG.matcher(html);
        while (tagMatcher.find() && out.size() < MAX_CROPS) {
            String tag = tagMatcher.group();
            String data = tagMatcher.group(1);
            Matcher srcMatcher = SRC_ATTR.matcher(tag);
            if (!srcMatcher.find()) continue;
            String src = srcMatcher.group(1);
            if (!src.startsWith("hybrid/") || src.contains("..") || src.contains("\\")) continue;

            String[] p = data.split(",");
            if (p.length != 5) continue;
            try {
                int page = Integer.parseInt(p[0].trim());
                double x0 = Double.parseDouble(p[1]);
                double y0 = Double.parseDouble(p[2]);
                double x1 = Double.parseDouble(p[3]);
                double y1 = Double.parseDouble(p[4]);
                if (!finite01(x0) || !finite01(y0) || !finite01(x1) || !finite01(y1) || x1 <= x0 || y1 <= y0) continue;
                File target = new File(cacheDir, src.replace('/', File.separatorChar));
                out.add(new Crop(page, x0, y0, x1, y1, target));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean finite01(double v) { return !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0 && v <= 1.0; }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static final class Crop {
        final int pageIndex;
        final double x0, y0, x1, y1;
        final File target;
        Crop(int pageIndex, double x0, double y0, double x1, double y1, File target) {
            this.pageIndex = pageIndex;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.target = target;
        }
        @Override public String toString() {
            return String.format(Locale.US, "p%d %.3f,%.3f..%.3f,%.3f -> %s", pageIndex, x0, y0, x1, y1, target);
        }
    }
}

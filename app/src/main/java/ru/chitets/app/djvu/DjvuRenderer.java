package ru.chitets.app.djvu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure-Java DjVu page renderer used by Chitets.
 *
 * Decodes the normal DjVu v3 page layers directly in the APK:
 * IFF/DJVM, IW44 (BG44/FG44), JB2 (Sjbz/Djbz), BZZ and FGbz.
 * JPEG page layers are delegated to Android BitmapFactory.
 */
public final class DjvuRenderer {
    private DjvuRenderer() {}

    public static Bitmap renderPage(DjvuDocument doc, int pageIndex, int maxOutputWidth, boolean night) throws DjvuException {
        DjvuIff.Chunk page = doc.pageChunk(pageIndex);
        DjvuDocument.PageInfo info = doc.pageInfo(pageIndex);
        if (info.width <= 0 || info.height <= 0) throw new DjvuException("DjVu: неверный размер страницы " + info.width + "x" + info.height);

        int outW = maxOutputWidth <= 0 ? info.width : Math.min(info.width, Math.max(320, maxOutputWidth));
        int outH = Math.max(1, (int) Math.round((double) info.height * outW / info.width));
        // A phone does not need a 20-24 MP intermediate page.  Besides the
        // Bitmap itself, DjVu decoding may temporarily keep IW44/JB2 buffers.
        // Use at most 6 MP and reduce that further when the Java heap is tight.
        final long maxPixels = safeOutputPixelBudget();
        if ((long) outW * outH > maxPixels) {
            double scale = Math.sqrt(maxPixels / ((double) outW * outH));
            outW = Math.max(1, (int) Math.floor(outW * scale));
            outH = Math.max(1, (int) Math.floor(outH * scale));
        }

        int iwSubsample = chooseIw44Subsample(info.width, outW);
        RgbImage bg = decodeIw44(page.all("BG44"), iwSubsample);
        if (bg == null) bg = decodeJpeg(page.first("BGjp"));
        RgbImage fg = decodeIw44(page.all("FG44"), iwSubsample);
        if (fg == null) fg = decodeJpeg(page.first("FGjp"));

        FgbzPalette palette = null;
        DjvuIff.Chunk fgbzChunk = page.first("FGbz");
        if (fgbzChunk != null) palette = FgbzPalette.parse(fgbzChunk.data());

        Jb2Decoder.Dict dict = decodeDictionaryChain(doc, page, new HashSet<>());

        Jb2Decoder.Mask mask = null;
        DjvuIff.Chunk sjbz = page.first("Sjbz");
        if (sjbz != null) {
            mask = palette != null && palette.indices.length > 0
                    ? Jb2Decoder.decodeIndexed(sjbz.data(), dict, palette.indices, palette.colors.length)
                    : Jb2Decoder.decode(sjbz.data(), dict);
        } else {
            DjvuIff.Chunk smmr = page.first("Smmr");
            if (smmr != null) mask = SmmrDecoder.decode(smmr.data());
        }

        if (bg == null && fg == null && mask == null) {
            // Legal/real-world DjVu documents sometimes contain intentionally
            // blank pages whose FORM:DJVU has only INFO (and possibly text,
            // annotations or INCL metadata). Render them as an empty sheet
            // instead of reporting an unsupported page. Unknown visual chunks
            // are still reported so we do not silently hide real content.
            if (isMetadataOnlyPage(page)) {
                return createBlankPage(outW, outH, night, info.rotation);
            }
            throw unsupported(page, "страница не содержит BG44/FG44/Sjbz/Smmr/BGjp/FGjp");
        }

        // Do NOT build an int[outW*outH] first.  On a 22.8 MP page that
        // single Java array is ~91.2 MB, which is exactly the OOM seen on
        // large/tall DjVu pages.  Allocate only the final Bitmap and one row.
        Bitmap result;
        try {
            result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            throw new DjvuException("DjVu: не хватает памяти для страницы " + outW + "x" + outH + ". Попробуйте ещё раз после закрытия тяжёлых приложений.");
        }
        int[] rowPixels = new int[outW];
        for (int y = 0; y < outH; y++) {
            int py = Math.min(info.height - 1, (int) ((long) y * info.height / outH));
            for (int x = 0; x < outW; x++) {
                int px = Math.min(info.width - 1, (int) ((long) x * info.width / outW));
                int color = bg == null ? 0xffffffff : sample(bg, px, py, info.width, info.height);
                if (mask != null) {
                    int mx = Math.min(mask.width - 1, Math.max(0, (int) ((long) px * mask.width / info.width)));
                    int my = Math.min(mask.height - 1, Math.max(0, (int) ((long) py * mask.height / info.height)));
                    if (mask.get(mx, my)) {
                        if (palette != null && palette.colors.length > 0) {
                            color = palette.colorAtIndex(mask.paletteIndexAt(mx, my));
                        } else if (fg != null) {
                            color = sample(fg, px, py, info.width, info.height);
                        } else {
                            color = 0xff000000;
                        }
                    }
                } else if (fg != null) {
                    // Photo-only / unusual two-layer file: if there is no stencil, the foreground is the page.
                    color = sample(fg, px, py, info.width, info.height);
                }
                if (night) color = invert(color);
                rowPixels[x] = color;
            }
            result.setPixels(rowPixels, 0, outW, 0, y, outW, 1);
        }
        return rotate(result, info.rotation);
    }



    private static boolean isMetadataOnlyPage(DjvuIff.Chunk page) {
        if (page == null || page.children == null || page.children.isEmpty()) return true;
        for (DjvuIff.Chunk c : page.children) {
            String id = c.id;
            if (!("INFO".equals(id) || "INCL".equals(id) || "TXTa".equals(id) ||
                    "TXTz".equals(id) || "ANTa".equals(id) || "ANTz".equals(id))) {
                return false;
            }
        }
        return true;
    }

    private static Bitmap createBlankPage(int width, int height, boolean night, DjvuDocument.PageInfo.Rotation rotation) throws DjvuException {
        Bitmap bm;
        try {
            bm = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            throw new DjvuException("DjVu: не хватает памяти для пустой страницы " + width + "x" + height);
        }
        bm.eraseColor(night ? 0xff000000 : 0xffffffff);
        return rotate(bm, rotation);
    }

    private static Jb2Decoder.Dict decodeDictionaryChain(DjvuDocument doc, DjvuIff.Chunk form, Set<DjvuIff.Chunk> seen) throws DjvuException {
        if (form == null || !seen.add(form)) return null;
        DjvuIff.Chunk included = doc.resolveIncludedForm(form);
        Jb2Decoder.Dict inherited = included == null ? null : decodeDictionaryChain(doc, included, seen);
        DjvuIff.Chunk own = form.first("Djbz");
        return own == null ? inherited : Jb2Decoder.decodeDict(own.data(), inherited);
    }
    private static long safeOutputPixelBudget() {
        final long hardCap = 6L * 1024L * 1024L;
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long headroom = Math.max(0L, rt.maxMemory() - used);
        // Reserve roughly two thirds of the remaining heap for the compressed
        // document and decoder scratch buffers. ARGB_8888 is 4 bytes/pixel.
        long heapCap = headroom / 12L;
        // Even on a tight heap, keep enough pixels for a readable phone page.
        heapCap = Math.max(900_000L, heapCap);
        return Math.min(hardCap, heapCap);
    }

    private static int chooseIw44Subsample(int pageWidth, int outputWidth) {
        if (pageWidth <= 0 || outputWidth <= 0) return 1;
        int sub = 1;
        // IW44 naturally supports power-of-two subsampling. Decode no more
        // resolution than the phone can display, while keeping at least the
        // requested output width for the final compositor.
        while (sub < 16 && pageWidth / (sub * 2) >= outputWidth) sub *= 2;
        return sub;
    }

    private static RgbImage decodeIw44(List<DjvuIff.Chunk> chunks, int subsample) throws DjvuException {
        if (chunks == null || chunks.isEmpty()) return null;
        Iw44Decoder decoder = new Iw44Decoder();
        for (DjvuIff.Chunk c : chunks) decoder.decodeChunk(c.data());
        return decoder.toImage(Math.max(1, subsample));
    }

    private static RgbImage decodeJpeg(DjvuIff.Chunk chunk) throws DjvuException {
        if (chunk == null) return null;
        byte[] data = chunk.data();
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bm == null) throw new DjvuException("DjVu: Android не смог декодировать JPEG-слой " + chunk.id);
        RgbImage out = new RgbImage(bm.getWidth(), bm.getHeight());
        bm.getPixels(out.argb, 0, out.width, 0, 0, out.width, out.height);
        bm.recycle();
        return out;
    }

    private static int sample(RgbImage image, int pageX, int pageY, int pageW, int pageH) {
        int x = Math.min(image.width - 1, Math.max(0, (int) ((long) pageX * image.width / Math.max(1, pageW))));
        int y = Math.min(image.height - 1, Math.max(0, (int) ((long) pageY * image.height / Math.max(1, pageH))));
        return image.argb[y * image.width + x];
    }

    private static int invert(int c) {
        int r = 255 - ((c >>> 16) & 0xff);
        int g = 255 - ((c >>> 8) & 0xff);
        int b = 255 - (c & 0xff);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static Bitmap rotate(Bitmap src, DjvuDocument.PageInfo.Rotation rotation) {
        if (rotation == DjvuDocument.PageInfo.Rotation.NONE) return src;
        android.graphics.Matrix m = new android.graphics.Matrix();
        if (rotation == DjvuDocument.PageInfo.Rotation.CW90) m.postRotate(90f);
        else if (rotation == DjvuDocument.PageInfo.Rotation.CCW90) m.postRotate(-90f);
        else m.postRotate(180f);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    private static DjvuException unsupported(DjvuIff.Chunk page, String reason) {
        StringBuilder ids = new StringBuilder();
        for (DjvuIff.Chunk c : page.children) {
            if (ids.length() > 0) ids.append(", ");
            ids.append(c.id);
        }
        return new DjvuException("DjVu: " + reason + ". Чанки страницы: " + ids);
    }
}

package ru.chitets.app.djvu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Parsed DjVu/DJVM document with directly embedded pages/components. */
public final class DjvuDocument {
    public static final class PageInfo {
        public enum Rotation { NONE, CCW90, ROT180, CW90 }
        public final int width, height, dpi;
        public final float gamma;
        public final Rotation rotation;
        PageInfo(int width, int height, int dpi, float gamma, Rotation rotation) {
            this.width = width; this.height = height; this.dpi = dpi; this.gamma = gamma; this.rotation = rotation;
        }
    }

    private final byte[] bytes;
    private final DjvuIff.Chunk root;
    private final List<DjvuIff.Chunk> pages = new ArrayList<>();
    private final List<DjvuIff.Chunk> shared = new ArrayList<>();
    private final Map<String, DjvuIff.Chunk> componentById = new HashMap<>();

    public DjvuDocument(byte[] bytes) throws DjvuException {
        this.bytes = bytes;
        this.root = DjvuIff.parse(bytes);
        if (root.isForm("DJVU")) pages.add(root);
        else if (root.isForm("DJVM")) {
            List<DjvuIff.Chunk> forms = new ArrayList<>();
            for (DjvuIff.Chunk c : root.children) {
                if ("FORM".equals(c.id)) forms.add(c);
                if (c.isForm("DJVU")) pages.add(c);
                else if (c.isForm("DJVI")) shared.add(c);
            }
            indexDirmComponents(forms);
        } else throw new DjvuException("DjVu: неподдерживаемый FORM:" + root.formType);
        if (pages.isEmpty()) {
            throw new DjvuException("DjVu: внутри документа не найдено ни одной FORM:DJVU страницы. Возможно, это indirect DJVM с внешними файлами.");
        }
    }

    public int pageCount() { return pages.size(); }

    /** Returns true when at least one page contains an embedded DjVu hidden-text/OCR layer. */
    public boolean hasTextLayer() {
        for (DjvuIff.Chunk page : pages) {
            if (page.first("TXTa") != null || page.first("TXTz") != null) return true;
        }
        return false;
    }

    /** Number of pages carrying TXTa/TXTz hidden text. */
    public int textPageCount() {
        int count = 0;
        for (DjvuIff.Chunk page : pages) {
            if (page.first("TXTa") != null || page.first("TXTz") != null) count++;
        }
        return count;
    }

    /**
     * Decodes the UTF-8 payload of a page TXTa/TXTz chunk. Zone geometry follows the text
     * payload in the DjVu format and is intentionally ignored by the reflow reader for now.
     */
    public String pageText(int index) throws DjvuException {
        DjvuIff.Chunk page = pageChunk(index);
        DjvuIff.Chunk text = page.first("TXTa");
        boolean compressed = false;
        if (text == null) {
            text = page.first("TXTz");
            compressed = text != null;
        }
        if (text == null) return "";
        byte[] payload = text.data();
        if (compressed) payload = BzzDecoder.decode(payload);
        if (payload.length < 3) throw new DjvuException("DjVu: текстовый чанк страницы " + (index + 1) + " слишком короткий");
        int length = ((payload[0] & 255) << 16) | ((payload[1] & 255) << 8) | (payload[2] & 255);
        if (length < 0 || 3L + length > payload.length) {
            throw new DjvuException("DjVu: повреждён текстовый слой страницы " + (index + 1));
        }
        return new String(payload, 3, length, StandardCharsets.UTF_8);
    }

    DjvuIff.Chunk pageChunk(int index) throws DjvuException {
        if (index < 0 || index >= pages.size()) throw new DjvuException("DjVu: страница вне диапазона: " + (index + 1));
        return pages.get(index);
    }
    List<DjvuIff.Chunk> sharedForms() { return Collections.unmodifiableList(shared); }

    public PageInfo pageInfo(int index) throws DjvuException {
        DjvuIff.Chunk info = pageChunk(index).first("INFO");
        if (info == null) throw new DjvuException("DjVu: в странице нет INFO");
        byte[] d = info.data();
        if (d.length < 10) throw new DjvuException("DjVu: INFO короче 10 байт");
        int w = DjvuIff.u16be(d, 0), h = DjvuIff.u16be(d, 2);
        int dpi = DjvuIff.u16le(d, 6); if (dpi <= 0) dpi = 300;
        float gamma = (d[8] & 255) == 0 ? 2.2f : (d[8] & 255) / 10f;
        int f = d[9] & 7;
        PageInfo.Rotation r = f == 5 ? PageInfo.Rotation.CW90 : f == 2 ? PageInfo.Rotation.ROT180 : f == 6 ? PageInfo.Rotation.CCW90 : PageInfo.Rotation.NONE;
        return new PageInfo(w, h, dpi, gamma, r);
    }

    /** Resolve the first INCL component using the DJVM DIRM id table. */
    DjvuIff.Chunk resolveIncludedForm(DjvuIff.Chunk form) {
        DjvuIff.Chunk incl = form == null ? null : form.first("INCL");
        if (incl == null) return null;
        String wanted = incl.textData();
        DjvuIff.Chunk exact = componentById.get(wanted);
        if (exact != null) return exact;
        // Lenient fallback for old bundled files with one shared dictionary.
        return shared.size() == 1 ? shared.get(0) : null;
    }

    /** Compatibility helper retained for callers that only need a Djbz chunk. */
    DjvuIff.Chunk findSharedDictionary(DjvuIff.Chunk page) {
        DjvuIff.Chunk inline = page.first("Djbz");
        if (inline != null) return inline;
        DjvuIff.Chunk form = resolveIncludedForm(page);
        return form == null ? null : form.first("Djbz");
    }

    private void indexDirmComponents(List<DjvuIff.Chunk> forms) {
        DjvuIff.Chunk dirm = root.first("DIRM");
        if (dirm == null) return;
        try {
            byte[] d = dirm.data();
            if (d.length < 3) return;
            int n = DjvuIff.u16be(d, 1);
            int pos = 3;
            boolean bundled = (d[0] & 0x80) != 0;
            int[] offsets = new int[n];
            if (bundled) {
                long end = (long) pos + (long) n * 4L;
                if (end > d.length) return;
                for (int i = 0; i < n; i++) {
                    long off = DjvuIff.u32be(d, pos + i * 4);
                    offsets[i] = off > Integer.MAX_VALUE ? -1 : (int) off;
                }
                pos = (int) end;
            } else {
                java.util.Arrays.fill(offsets, -1);
            }

            byte[] packed = new byte[d.length - pos];
            System.arraycopy(d, pos, packed, 0, packed.length);
            byte[] meta = BzzDecoder.decode(packed);
            int flagsStart = n * 3;
            if (flagsStart + n > meta.length) return;

            Map<Integer, DjvuIff.Chunk> byOffset = new HashMap<>();
            for (DjvuIff.Chunk form : forms) byOffset.put(form.headerOffset(), form);

            List<DjvuIff.Chunk> orderedPages = new ArrayList<>();
            List<DjvuIff.Chunk> orderedShared = new ArrayList<>();
            int str = flagsStart + n;
            for (int i = 0; i < n; i++) {
                String id = readNt(meta, str);
                if (id == null) break;
                str += utf8Length(id) + 1;
                int flag = meta[flagsStart + i] & 0xff;
                if ((flag & 0x80) != 0) {
                    String name = readNt(meta, str);
                    if (name == null) break;
                    str += utf8Length(name) + 1;
                }
                if ((flag & 0x40) != 0) {
                    String title = readNt(meta, str);
                    if (title == null) break;
                    str += utf8Length(title) + 1;
                }

                DjvuIff.Chunk form = offsets[i] >= 0 ? byOffset.get(offsets[i]) : null;
                if (form == null && i < forms.size()) form = forms.get(i);
                if (form == null) continue;
                if (!id.isEmpty()) componentById.put(id, form);

                int kind = flag & 0x3f;
                if (kind == 1 && form.isForm("DJVU")) orderedPages.add(form);
                else if (kind != 2 && form.isForm("DJVI")) orderedShared.add(form);
            }

            // DIRM defines logical page order. Use it when it resolved cleanly;
            // physical FORM traversal remains a safe fallback for malformed DIRM.
            if (!orderedPages.isEmpty()) { pages.clear(); pages.addAll(orderedPages); }
            if (!orderedShared.isEmpty()) { shared.clear(); shared.addAll(orderedShared); }
        } catch (Exception ignored) {
            // DIRM is an accelerator/resolver. Physical FORM traversal still works.
        }
    }

    private static int utf8Length(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String readNt(byte[] b, int pos) {
        if (pos < 0 || pos >= b.length) return null;
        int end = pos;
        while (end < b.length && b[end] != 0) end++;
        if (end >= b.length) return null;
        return new String(b, pos, end - pos, java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] rawBytes() { return bytes; }
}

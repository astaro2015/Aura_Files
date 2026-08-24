package ru.chitets.app.parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;

/**
 * PDF -> reflowable HTML converter used by Chitets' "PDF / Text" mode.
 *
 * This is deliberately a reader-oriented parser, not a PDF editor. It extracts
 * positioned text, reconstructs lines/paragraphs/columns, and saves common
 * raster XObjects (JPEG and 8-bit Flate RGB/gray/indexed images) next to the
 * generated HTML so the ordinary ReaderActivity can display them inline.
 *
 * Supported PDF features are intentionally tolerant: classic objects, xref
 * streams indirectly via object-stream expansion, Flate/ASCII85/ASCIIHex/
 * RunLength/LZW stream filters, ToUnicode CMaps, WinAnsi/MacRoman encodings,
 * Type0 Identity text when a ToUnicode map is present, page resource inheritance,
 * nested Form XObjects, and the common text/graphics operators used by books.
 */
public final class PdfReflowParser {
    private static final int MAX_PDF_BYTES = 160 * 1024 * 1024;
    private static final int MAX_OBJECTS = 120_000;
    private static final int MAX_STREAM_BYTES = 96 * 1024 * 1024;
    private static final int CACHE_VERSION = 6;
    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
    private static final Pattern REF_PATTERN = Pattern.compile("(\\d+)\\s+(\\d+)\\s+R");
    private static final Pattern OBJ_HEADER = Pattern.compile("(?s)(?<!\\d)(\\d+)\\s+(\\d+)\\s+obj\\b");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private PdfReflowParser() {}

    /** Parse only the native PDF outline/bookmarks and map destinations to source pages. */
    public static List<TocEntry> parseOutline(InputStream input) throws Exception {
        byte[] pdf = readLimited(input, MAX_PDF_BYTES);
        if (pdf.length < 8 || !startsWithPdf(pdf)) throw new IOException("Это не похоже на PDF-файл");
        if (containsAscii(pdf, "/Encrypt")) return Collections.emptyList();
        PdfFile file = PdfFile.parse(pdf);
        file.expandObjectStreams();
        List<PdfObject> pages = file.pageObjectsInOrder();
        if (pages.isEmpty()) return Collections.emptyList();
        return file.outlineToc(pages);
    }

    public static ReaderDocument parse(InputStream input, File cacheDir, String fallbackTitle) throws Exception {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("Не удалось создать кэш разбора PDF");
        File cachedHtml = new File(cacheDir, "reflow-v" + CACHE_VERSION + ".html");
        File cachedMeta = new File(cacheDir, "reflow-v" + CACHE_VERSION + ".properties");
        if (cachedHtml.isFile() && cachedHtml.length() > 128 && cachedMeta.isFile()) {
            try {
                Properties p = new Properties();
                try (InputStream metaIn = new FileInputStream(cachedMeta)) { p.load(metaIn); }
                String html = readUtf8(cachedHtml, 32 * 1024 * 1024);
                if (!cacheAssetsPresent(html, cacheDir)) throw new IOException("PDF reflow cache has missing images");
                String title = p.getProperty("title", fallbackTitle);
                String author = p.getProperty("author", "");
                List<TocEntry> toc = decodeToc(p.getProperty("toc", ""));
                return new ReaderDocument(title, author, "", html, fileBaseUrl(cacheDir), "", toc);
            } catch (Exception ignored) {
                // Corrupt/old cache: rebuild below.
            }
        }

        byte[] pdf = readLimited(input, MAX_PDF_BYTES);
        if (pdf.length < 8 || !startsWithPdf(pdf)) throw new IOException("Это не похоже на PDF-файл");
        if (containsAscii(pdf, "/Encrypt")) {
            throw new IOException("Зашифрованный PDF пока нельзя разобрать в текстовый режим. Откройте его в режиме «Оригинал».");
        }

        PdfFile file = PdfFile.parse(pdf);
        file.expandObjectStreams();
        List<PdfObject> pages = file.pageObjectsInOrder();
        if (pages.isEmpty()) throw new IOException("В PDF не найдены страницы");

        Metadata metadata = file.metadata(fallbackTitle);
        List<TocEntry> nativeToc = file.outlineToc(pages);
        List<PageResult> pageResults = new ArrayList<>();
        Map<String, Integer> edgeRepeat = new HashMap<>();
        int totalTextChars = 0;

        File imagesDir = new File(cacheDir, "images");
        if (!imagesDir.exists()) imagesDir.mkdirs();
        else clearGeneratedImages(imagesDir);
        File hybridDir = new File(cacheDir, "hybrid");
        if (!hybridDir.exists()) hybridDir.mkdirs();
        else clearHybridCrops(hybridDir);
        ImageStore imageStore = new ImageStore(file, imagesDir);

        for (int i = 0; i < pages.size(); i++) {
            PdfObject page = pages.get(i);
            PageResult result = extractPage(file, page, i, imageStore);
            pageResults.add(result);
            totalTextChars += result.textChars;
            for (Line line : result.lines) {
                if (line.isEdge(result.height)) {
                    String key = normalizeRepeat(line.text);
                    if (key.length() >= 2 && key.length() <= 100) edgeRepeat.put(key, edgeRepeat.getOrDefault(key, 0) + 1);
                }
            }
        }

        int minText = Math.max(40, Math.min(500, pages.size() * 6));
        if (totalTextChars < minText) {
            throw new IOException("В PDF почти нет текстового слоя. Похоже, это скан. Для режима «Текст» здесь понадобится OCR; режим «Оригинал» продолжает работать.");
        }

        Set<String> repeatedEdges = new HashSet<>();
        int repeatThreshold = Math.max(3, (int) Math.ceil(pages.size() * 0.35));
        for (Map.Entry<String, Integer> e : edgeRepeat.entrySet()) if (e.getValue() >= repeatThreshold) repeatedEdges.add(e.getKey());

        float medianFont = medianFont(pageResults);
        StringBuilder body = new StringBuilder(Math.max(8192, totalTextChars * 2));
        List<TocEntry> detectedToc = new ArrayList<>();
        int blockId = 0;
        for (PageResult page : pageResults) {
            body.append("<section class=\"pdf-reflow-page\" id=\"pdf-page-").append(page.pageIndex + 1).append("\">");
            body.append("<div class=\"pdf-page-marker\">Страница ").append(page.pageIndex + 1).append("</div>");
            List<Block> blocks = buildBlocks(page, repeatedEdges, medianFont);
            for (Block block : blocks) {
                if (block.image != null) {
                    body.append("<figure class=\"pdf-image\"><img src=\"")
                            .append(HtmlUtil.escape(block.image.relativePath))
                            .append("\" alt=\"Иллюстрация из PDF\" onerror=\"this.style.display='none';this.nextElementSibling.style.display='block'\"><div class=\"pdf-image-fallback\">Иллюстрация PDF не декодирована</div></figure>");
                    continue;
                }
                if (block.text == null || block.text.trim().isEmpty()) continue;
                String id = "pdf-block-" + (++blockId);
                if (block.headingLevel > 0) {
                    String tag = block.headingLevel == 1 ? "h2" : "h3";
                    body.append('<').append(tag).append(" id=\"").append(id).append("\">")
                            .append(HtmlUtil.escape(block.text)).append("</").append(tag).append('>');
                    if (detectedToc.size() < 300 && block.text.length() <= 160) detectedToc.add(new TocEntry(block.text, id, Math.max(0, block.headingLevel - 1)));
                } else if (block.kind == BlockKind.COMPLEX) {
                    String cropPath = String.format(Locale.US, "hybrid/crop-p%04d-b%05d.png", page.pageIndex + 1, blockId);
                    double padX = Math.max(5.0, page.width * 0.012);
                    double padY = Math.max(4.0, page.height * 0.008);
                    double x0 = clamp01((block.minX - page.originX - padX) / Math.max(1.0, page.width));
                    double x1 = clamp01((block.maxX - page.originX + padX) / Math.max(1.0, page.width));
                    // Text extraction uses PDF bottom-left coordinates; PdfRenderer bitmap coordinates are top-left.
                    double y0 = clamp01(1.0 - (block.maxY - page.originY + padY) / Math.max(1.0, page.height));
                    double y1 = clamp01(1.0 - (block.minY - page.originY - padY) / Math.max(1.0, page.height));
                    if (x1 - x0 < 0.03) { double c=(x0+x1)*0.5; x0=clamp01(c-0.03); x1=clamp01(c+0.03); }
                    if (y1 - y0 < 0.012) { double c=(y0+y1)*0.5; y0=clamp01(c-0.012); y1=clamp01(c+0.012); }
                    double[] rotatedCrop = rotateNormalizedCrop(x0, y0, x1, y1, page.rotation);
                    x0=rotatedCrop[0]; y0=rotatedCrop[1]; x1=rotatedCrop[2]; y1=rotatedCrop[3];
                    body.append("<figure class=\"pdf-hybrid-block\" id=\"").append(id)
                            .append("\" data-pdf-page=\"").append(page.pageIndex + 1).append("\">")
                            .append("<img class=\"pdf-hybrid-crop\" src=\"").append(cropPath)
                            .append("\" data-pdf-crop=\"").append(page.pageIndex).append(',')
                            .append(fmt6(x0)).append(',').append(fmt6(y0)).append(',').append(fmt6(x1)).append(',').append(fmt6(y1))
                            .append("\" alt=\"Фрагмент оригинальной страницы PDF\" onerror=\"this.style.display='none';this.nextElementSibling.style.display='block'\">")
                            .append("<pre class=\"pdf-complex-fallback\">").append(HtmlUtil.escape(block.text)).append("</pre></figure>");
                } else if (block.kind == BlockKind.CAPTION) {
                    body.append("<div class=\"pdf-caption\" id=\"").append(id).append("\">")
                            .append(HtmlUtil.escape(block.text)).append("</div>");
                } else {
                    String cls = block.kind == BlockKind.LIST ? " class=\"pdf-list-item\""
                            : block.kind == BlockKind.QUOTE ? " class=\"pdf-quote\""
                            : block.firstLineIndent ? " class=\"pdf-indent\"" : "";
                    body.append("<p id=\"").append(id).append("\"").append(cls).append(">")
                            .append(HtmlUtil.escape(block.text)).append("</p>");
                }
            }
            body.append("</section>");
        }

        List<TocEntry> toc = nativeToc.isEmpty() ? detectedToc : nativeToc;
        String css = ".pdf-reflow-page{margin:0 0 2.2em}.pdf-page-marker{font-size:.72em;opacity:.45;text-align:center;" +
                "border-bottom:1px solid currentColor;padding:.4em 0;margin:1.6em 0 1.2em}.pdf-image{margin:1.1em 0;text-align:center}" +
                ".pdf-image img{max-height:75vh;object-fit:contain}.pdf-image-fallback{display:none;opacity:.55;font-size:.85em;padding:.6em;border:1px dashed currentColor}" +
                ".pdf-reflow-page p{orphans:2;widows:2}.pdf-indent{text-indent:1.35em!important}.pdf-list-item{text-indent:0!important;padding-left:1.35em}" +
                ".pdf-quote{text-indent:0!important;margin-left:1.2em!important;padding-left:.8em;border-left:2px solid currentColor;opacity:.92}" +
                ".pdf-caption{text-align:center;font-size:.84em;opacity:.72;margin:.25em 1em 1em}" +
                ".pdf-hybrid-block{margin:1em 0;break-inside:avoid;text-align:center}.pdf-hybrid-crop{display:block;max-width:100%;height:auto;margin:0 auto;" +
                "background:#fff;border-radius:.18em;box-shadow:0 1px 4px rgba(0,0,0,.14)}" +
                ".pdf-complex-fallback{display:none;white-space:pre-wrap;overflow-wrap:anywhere;text-align:left;font-family:ui-monospace,monospace;font-size:.88em;line-height:1.35;" +
                "padding:.65em .75em;margin:0;border:1px solid currentColor;border-radius:.35em;opacity:.92}" +
                "html.pdf-hybrid-off .pdf-hybrid-crop{display:none!important}html.pdf-hybrid-off .pdf-complex-fallback{display:block!important}";
        String html = HtmlUtil.wrap(metadata.title, metadata.author, body.toString(), css);

        writeUtf8(cachedHtml, html);
        Properties props = new Properties();
        props.setProperty("title", metadata.title);
        props.setProperty("author", metadata.author);
        props.setProperty("toc", encodeToc(toc));
        props.setProperty("nativeToc", Boolean.toString(!nativeToc.isEmpty()));
        props.setProperty("pages", Integer.toString(pages.size()));
        props.setProperty("chars", Integer.toString(totalTextChars));
        try (FileOutputStream out = new FileOutputStream(cachedMeta)) { props.store(out, "Chitets PDF reflow cache"); }

        return new ReaderDocument(metadata.title, metadata.author, "", html, fileBaseUrl(cacheDir), "", toc);
    }

    private static PageResult extractPage(PdfFile file, PdfObject page, int pageIndex, ImageStore imageStore) {
        PageResult result = new PageResult(pageIndex);
        double[] media = file.inheritedBox(page, "/CropBox");
        if (media == null) media = file.inheritedBox(page, "/MediaBox");
        if (media != null && media.length >= 4) {
            result.originX = Math.min(media[0], media[2]);
            result.originY = Math.min(media[1], media[3]);
            result.width = Math.abs(media[2] - media[0]);
            result.height = Math.abs(media[3] - media[1]);
        }
        if (result.width <= 0) result.width = 612;
        if (result.height <= 0) result.height = 792;
        result.rotation = normalizeRotation(file.inheritedInt(page, "/Rotate", 0));

        String resources = file.inheritedDictionary(page, "/Resources");
        ResourceSet resourceSet = ResourceSet.parse(file, resources);
        List<byte[]> streams = file.contentStreams(page);
        GraphicsState gs = new GraphicsState();
        for (byte[] stream : streams) {
            try {
                ContentInterpreter interpreter = new ContentInterpreter(file, resourceSet, result, imageStore);
                interpreter.run(stream, gs.copy(), 0);
            } catch (Exception ignored) {
                // A malformed content stream should not kill text from the rest of the book.
            }
        }
        result.finishLines();
        return result;
    }

    private static List<Block> buildBlocks(PageResult page, Set<String> repeatedEdges, float medianFont) {
        List<Line> lines = new ArrayList<>();
        for (Line line : page.lines) {
            if (line.text.trim().isEmpty()) continue;
            if (line.isEdge(page.height) && repeatedEdges.contains(normalizeRepeat(line.text))) continue;
            lines.add(line);
        }
        lines = orderLinesByColumns(lines, page.width);
        List<Block> result = new ArrayList<>();
        List<PositionedImage> images = new ArrayList<>(page.images);
        images.sort((a, b) -> Double.compare(b.y, a.y));
        int imageIndex = 0;

        Paragraph current = null;
        for (Line line : lines) {
            while (imageIndex < images.size() && images.get(imageIndex).y > line.y + Math.max(6, line.fontSize)) {
                if (current != null) { result.add(current.toBlock(medianFont, page.width)); current = null; }
                result.add(Block.image(images.get(imageIndex++)));
            }
            if (isListText(line.text)) {
                if (current != null) { result.add(current.toBlock(medianFont, page.width)); current = null; }
                result.add(new Paragraph(line).toBlock(medianFont, page.width));
                continue;
            }
            if (current == null || !current.accepts(line)) {
                if (current != null) result.add(current.toBlock(medianFont, page.width));
                current = new Paragraph(line);
            } else {
                current.add(line);
            }
        }
        if (current != null) result.add(current.toBlock(medianFont, page.width));
        while (imageIndex < images.size()) result.add(Block.image(images.get(imageIndex++)));
        return result;
    }

    private static List<Line> orderLinesByColumns(List<Line> source, double pageWidth) {
        if (source.size() < 12 || pageWidth <= 0) {
            source.sort(Line.READING_ORDER);
            return source;
        }
        double mid = pageWidth * 0.5;
        double gap = Math.max(12, pageWidth * 0.035);
        List<Line> left = new ArrayList<>(), right = new ArrayList<>(), wide = new ArrayList<>();
        for (Line l : source) {
            if (l.maxX < mid - gap) left.add(l);
            else if (l.minX > mid + gap) right.add(l);
            else wide.add(l);
        }
        boolean twoColumns = left.size() >= 5 && right.size() >= 5 && (left.size() + right.size()) >= source.size() * 0.65;
        if (!twoColumns) {
            source.sort(Line.READING_ORDER);
            return source;
        }
        left.sort(Line.READING_ORDER);
        right.sort(Line.READING_ORDER);
        wide.sort(Line.READING_ORDER);
        List<Line> out = new ArrayList<>(source.size());
        // Wide title/header lines above the main column content come first.
        double firstColumnY = Math.max(left.isEmpty() ? -1 : left.get(0).y, right.isEmpty() ? -1 : right.get(0).y);
        for (Line w : wide) if (w.y >= firstColumnY - 8) out.add(w);
        out.addAll(left);
        out.addAll(right);
        for (Line w : wide) if (w.y < firstColumnY - 8) out.add(w);
        return out;
    }

    private static float medianFont(List<PageResult> pages) {
        List<Double> sizes = new ArrayList<>();
        for (PageResult p : pages) for (Line l : p.lines) if (l.fontSize > 3 && l.fontSize < 100) sizes.add(l.fontSize);
        if (sizes.isEmpty()) return 11f;
        Collections.sort(sizes);
        return sizes.get(sizes.size() / 2).floatValue();
    }

    private static String normalizeRepeat(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static String fmt6(double v) { return String.format(Locale.US, "%.6f", v); }
    private static int normalizeRotation(int rotation) { int r=rotation%360; if(r<0)r+=360; return r==90||r==180||r==270?r:0; }
    private static double[] rotateNormalizedCrop(double x0,double y0,double x1,double y1,int rotation){
        if(rotation==0)return new double[]{x0,y0,x1,y1};
        double[][] pts={{x0,y0},{x1,y0},{x0,y1},{x1,y1}};double minX=1,minY=1,maxX=0,maxY=0;
        for(double[]p:pts){double x=p[0],y=p[1],rx,ry;if(rotation==90){rx=1-y;ry=x;}else if(rotation==180){rx=1-x;ry=1-y;}else{rx=y;ry=1-x;}minX=Math.min(minX,rx);minY=Math.min(minY,ry);maxX=Math.max(maxX,rx);maxY=Math.max(maxY,ry);}
        return new double[]{clamp01(minX),clamp01(minY),clamp01(maxX),clamp01(maxY)};
    }

    private static String fileBaseUrl(File dir){String p=dir.getAbsolutePath().replace('\\','/');if(!p.endsWith("/"))p+="/";return "file://"+(p.startsWith("/")?"":"/")+p;}

    private static boolean cacheAssetsPresent(String html, File cacheDir) {
        Matcher m = Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html == null ? "" : html);
        while (m.find()) {
            String src = m.group(1);
            if (!src.startsWith("images/")) continue;
            File f = new File(cacheDir, src.replace('/', File.separatorChar));
            if (!f.isFile() || f.length() < 16) return false;
        }
        return true;
    }

    private static void clearGeneratedImages(File imagesDir) {
        File[] files = imagesDir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.getName().startsWith("img-")) {
            try { f.delete(); } catch (Exception ignored) {}
        }
    }

    private static void clearHybridCrops(File hybridDir) {
        File[] files = hybridDir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.getName().startsWith("crop-p")) {
            try { f.delete(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int total = 0, read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("PDF слишком большой для текстового разбора (лимит " + (limit / 1024 / 1024) + " МБ)");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean startsWithPdf(byte[] data) {
        int max = Math.min(1024, data.length - 4);
        for (int i = 0; i < max; i++) if (data[i] == '%' && data[i + 1] == 'P' && data[i + 2] == 'D' && data[i + 3] == 'F') return true;
        return false;
    }

    private static void writeUtf8(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String readUtf8(File file, int max) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return new String(readLimitedNoClose(in, max), StandardCharsets.UTF_8); }
    }

    private static byte[] readLimitedNoClose(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int total = 0, n;
        while ((n = in.read(b)) != -1) { total += n; if (total > max) throw new IOException("Кэш слишком большой"); out.write(b,0,n); }
        return out.toByteArray();
    }

    private static String encodeToc(List<TocEntry> toc) {
        StringBuilder s = new StringBuilder();
        for (TocEntry e : toc) {
            if (s.length() > 0) s.append('\n');
            s.append(e.title.replace("\\", "\\\\").replace("\t", " ").replace("\n", " "))
                    .append('\t').append(e.anchor).append('\t').append(e.level);
        }
        return s.toString();
    }

    private static List<TocEntry> decodeToc(String raw) {
        List<TocEntry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length >= 2) {
                int level = 0;
                if (parts.length >= 3) try { level = Integer.parseInt(parts[2]); } catch (Exception ignored) {}
                out.add(new TocEntry(parts[0].replace("\\\\", "\\"), parts[1], level));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Parsed PDF object model
    // ---------------------------------------------------------------------------------------------

    private static final class PdfFile {
        final byte[] bytes;
        final Map<Integer, PdfObject> objects = new LinkedHashMap<>();

        PdfFile(byte[] bytes) { this.bytes = bytes; }

        static PdfFile parse(byte[] bytes) throws IOException {
            PdfFile file = new PdfFile(bytes);
            int pos = 0;
            while (pos < bytes.length && file.objects.size() < MAX_OBJECTS) {
                Match h = findObjectHeader(bytes, pos);
                if (h == null) break;
                int number = h.a;
                int generation = h.b;
                int bodyStart = h.end;
                ParseBoundary boundary = findObjectBoundary(bytes, bodyStart);
                if (boundary == null) break;
                PdfObject object = PdfObject.fromRange(number, generation, bytes, bodyStart, boundary.endObjStart);
                file.objects.put(number, object);
                pos = boundary.afterEndObj;
            }
            if (file.objects.isEmpty()) throw new IOException("Не удалось разобрать структуру PDF");
            return file;
        }

        void expandObjectStreams() {
            List<PdfObject> snapshot = new ArrayList<>(objects.values());
            for (PdfObject object : snapshot) {
                if (!"/ObjStm".equals(nameValue(object.dict, "/Type"))) continue;
                try {
                    int n = intValue(object.dict, "/N", 0);
                    int first = intValue(object.dict, "/First", -1);
                    if (n <= 0 || n > 100_000 || first < 0) continue;
                    byte[] decoded = decodeStream(object, this);
                    if (decoded == null || first > decoded.length) continue;
                    String header = new String(decoded, 0, first, LATIN1);
                    Matcher m = Pattern.compile("(\\d+)\\s+(\\d+)").matcher(header);
                    int[] nums = new int[n]; int[] offs = new int[n]; int got = 0;
                    while (got < n && m.find()) { nums[got] = Integer.parseInt(m.group(1)); offs[got] = Integer.parseInt(m.group(2)); got++; }
                    for (int i = 0; i < got; i++) {
                        int start = first + offs[i];
                        int end = i + 1 < got ? first + offs[i + 1] : decoded.length;
                        if (start < first || end < start || end > decoded.length) continue;
                        objects.putIfAbsent(nums[i], PdfObject.from(nums[i], 0, Arrays.copyOfRange(decoded, start, end)));
                    }
                } catch (Exception ignored) {}
            }
        }

        Metadata metadata(String fallbackTitle) {
            String title = fallbackTitle == null || fallbackTitle.trim().isEmpty() ? "PDF" : fallbackTitle.trim();
            String author = "";
            try {
                int trailerStart = Math.max(0, bytes.length - 1024 * 1024);
                String trailer = new String(bytes, trailerStart, bytes.length - trailerStart, LATIN1);
                Matcher infoRef = Pattern.compile("/Info\\s+(\\d+)\\s+\\d+\\s+R").matcher(trailer);
                if (infoRef.find()) {
                    PdfObject info = objects.get(Integer.parseInt(infoRef.group(1)));
                    if (info != null) {
                        String t = stringValue(info.dict, "/Title");
                        String a = stringValue(info.dict, "/Author");
                        if (!t.isEmpty()) title = t;
                        if (!a.isEmpty()) author = a;
                    }
                }
            } catch (Exception ignored) {}
            return new Metadata(title, author);
        }

        PdfObject get(int ref) { return objects.get(ref); }

        String resolveDictionaryValue(String dict, String key) {
            if (dict == null) return "";
            String val = rawValue(dict, key);
            Ref ref = parseRef(val);
            if (ref != null) {
                PdfObject o = get(ref.number);
                return o == null ? "" : o.dict;
            }
            return val;
        }

        String inheritedDictionary(PdfObject page, String key) {
            PdfObject cur = page;
            Set<Integer> seen = new HashSet<>();
            while (cur != null && seen.add(cur.number)) {
                String v = rawValue(cur.dict, key);
                if (!v.isEmpty()) {
                    Ref ref = parseRef(v);
                    if (ref != null) { PdfObject r = get(ref.number); return r == null ? "" : r.dict; }
                    return v;
                }
                Ref parent = parseRef(rawValue(cur.dict, "/Parent"));
                cur = parent == null ? null : get(parent.number);
            }
            return "";
        }

        double[] inheritedBox(PdfObject page, String key) {
            PdfObject cur = page;
            Set<Integer> seen = new HashSet<>();
            while (cur != null && seen.add(cur.number)) {
                String v = rawValue(cur.dict, key);
                if (!v.isEmpty()) {
                    List<Double> nums = parseNumbers(v);
                    if (nums.size() >= 4) return new double[]{nums.get(0), nums.get(1), nums.get(2), nums.get(3)};
                }
                Ref p = parseRef(rawValue(cur.dict, "/Parent"));
                cur = p == null ? null : get(p.number);
            }
            return null;
        }

        int inheritedInt(PdfObject page, String key, int def) {
            PdfObject cur = page;
            Set<Integer> seen = new HashSet<>();
            while (cur != null && seen.add(cur.number)) {
                String v = rawValue(cur.dict, key);
                if (!v.isEmpty()) {
                    try { Matcher m=Pattern.compile("[-+]?\\d+").matcher(v); if(m.find()) return Integer.parseInt(m.group()); } catch(Exception ignored) {}
                }
                Ref p = parseRef(rawValue(cur.dict, "/Parent"));
                cur = p == null ? null : get(p.number);
            }
            return def;
        }

        List<byte[]> contentStreams(PdfObject page) {
            List<byte[]> out = new ArrayList<>();
            String v = rawValue(page.dict, "/Contents");
            for (Ref ref : parseRefs(v)) {
                PdfObject o = get(ref.number);
                if (o != null && o.stream != null) try { byte[] d = decodeStream(o, this); if (d != null) out.add(d); } catch (Exception ignored) {}
            }
            if (out.isEmpty()) {
                Ref ref = parseRef(v);
                if (ref != null) {
                    PdfObject o = get(ref.number);
                    if (o != null && o.stream != null) try { byte[] d = decodeStream(o, this); if (d != null) out.add(d); } catch (Exception ignored) {}
                }
            }
            return out;
        }

        List<PdfObject> pageObjectsInOrder() {
            PdfObject catalog = null;
            for (PdfObject o : objects.values()) if ("/Catalog".equals(nameValue(o.dict, "/Type"))) { catalog = o; break; }
            List<PdfObject> out = new ArrayList<>();
            if (catalog != null) {
                Ref root = parseRef(rawValue(catalog.dict, "/Pages"));
                if (root != null) walkPages(root.number, out, new HashSet<>());
            }
            if (out.isEmpty()) {
                for (PdfObject o : objects.values()) if (isPage(o.dict)) out.add(o);
                out.sort(Comparator.comparingInt(a -> a.number));
            }
            return out;
        }

        void walkPages(int objNum, List<PdfObject> out, Set<Integer> seen) {
            if (!seen.add(objNum)) return;
            PdfObject o = get(objNum); if (o == null) return;
            if (isPage(o.dict)) { out.add(o); return; }
            String kids = rawValue(o.dict, "/Kids");
            for (Ref r : parseRefs(kids)) walkPages(r.number, out, seen);
        }

        List<TocEntry> outlineToc(List<PdfObject> pages) {
            List<TocEntry> out = new ArrayList<>();
            if (pages == null || pages.isEmpty()) return out;
            PdfObject catalog = catalog();
            if (catalog == null) return out;
            Ref outlinesRef = parseRef(rawValue(catalog.dict, "/Outlines"));
            if (outlinesRef == null) return out;
            PdfObject outlines = get(outlinesRef.number);
            if (outlines == null) return out;
            Ref first = parseRef(rawValue(outlines.dict, "/First"));
            if (first == null) return out;
            Map<Integer,Integer> pageNumbers = new HashMap<>();
            for (int i = 0; i < pages.size(); i++) pageNumbers.put(pages.get(i).number, i + 1);
            walkOutlineSiblings(first, 0, pageNumbers, out, new HashSet<>(), 0);
            return out;
        }

        private PdfObject catalog() {
            for (PdfObject o : objects.values()) if ("/Catalog".equals(nameValue(o.dict, "/Type"))) return o;
            return null;
        }

        private void walkOutlineSiblings(Ref start, int level, Map<Integer,Integer> pageNumbers,
                                         List<TocEntry> out, Set<Integer> seen, int guard) {
            Ref cursor = start;
            int localGuard = guard;
            while (cursor != null && out.size() < 1000 && localGuard++ < 4000 && seen.add(cursor.number)) {
                PdfObject item = get(cursor.number);
                if (item == null) break;
                String title = stringValue(item.dict, "/Title");
                Ref child = parseRef(rawValue(item.dict, "/First"));
                int page = outlineDestinationPage(item, pageNumbers);
                if (page <= 0 && child != null) page = firstOutlinePage(child, pageNumbers, new HashSet<>(), 0);
                if (!title.isEmpty() && page > 0) out.add(new TocEntry(title, "pdf-page-" + page, Math.max(0, level)));
                if (child != null) walkOutlineSiblings(child, level + 1, pageNumbers, out, seen, localGuard);
                cursor = parseRef(rawValue(item.dict, "/Next"));
            }
        }

        private int firstOutlinePage(Ref start, Map<Integer,Integer> pageNumbers, Set<Integer> seen, int depth) {
            Ref cursor = start;
            while (cursor != null && depth++ < 200 && seen.add(cursor.number)) {
                PdfObject item = get(cursor.number);
                if (item == null) break;
                int page = outlineDestinationPage(item, pageNumbers);
                if (page > 0) return page;
                Ref child = parseRef(rawValue(item.dict, "/First"));
                if (child != null) { int nested = firstOutlinePage(child, pageNumbers, seen, depth); if (nested > 0) return nested; }
                cursor = parseRef(rawValue(item.dict, "/Next"));
            }
            return -1;
        }

        private int outlineDestinationPage(PdfObject item, Map<Integer,Integer> pageNumbers) {
            String dest = rawValue(item.dict, "/Dest");
            int page = pageFromDestination(dest, pageNumbers);
            if (page <= 0) page = pageFromDestination(resolveNamedDestination(destinationName(dest)), pageNumbers);
            if (page > 0) return page;
            String actionRaw = rawValue(item.dict, "/A");
            Ref actionRef = parseRef(actionRaw);
            String action = actionRaw;
            if (actionRef != null) { PdfObject a = get(actionRef.number); action = a == null ? "" : a.dict; }
            if (!action.isEmpty() && ("/GoTo".equals(nameValue(action, "/S")) || rawValue(action, "/S").isEmpty())) {
                String actionDest = rawValue(action, "/D");
                page = pageFromDestination(actionDest, pageNumbers);
                if (page <= 0) page = pageFromDestination(resolveNamedDestination(destinationName(actionDest)), pageNumbers);
            }
            return page;
        }

        private int pageFromDestination(String dest, Map<Integer,Integer> pageNumbers) {
            if (dest == null || dest.isEmpty()) return -1;
            List<Ref> refs = parseRefs(dest);
            if (!refs.isEmpty()) {
                Integer page = pageNumbers.get(refs.get(0).number);
                if (page != null) return page;
                PdfObject d = get(refs.get(0).number);
                if (d != null) {
                    if (d.raw.startsWith("[")) { int nested = pageFromDestination(d.raw, pageNumbers); if (nested > 0) return nested; }
                    int nested = pageFromDestination(rawValue(d.dict, "/D"), pageNumbers);
                    if (nested > 0) return nested;
                }
            }
            return -1;
        }

        private String resolveNamedDestination(String name) {
            if (name == null || name.isEmpty()) return "";
            PdfObject catalog = catalog();
            if (catalog == null) return "";

            String dests = resolveRawObject(this, rawValue(catalog.dict, "/Dests"));
            String direct = namedDictionaryValue(dests, name);
            if (!direct.isEmpty()) return direct;

            String names = resolveRawObject(this, rawValue(catalog.dict, "/Names"));
            String treeRaw = rawValue(names, "/Dests");
            Ref treeRef = parseRef(treeRaw);
            PdfObject tree = treeRef == null ? null : get(treeRef.number);
            if (tree != null) return findNameTreeDestination(tree, name, new HashSet<>());
            if (treeRaw.startsWith("<<")) {
                PdfObject synthetic = new PdfObject(-1, 0, treeRaw, treeRaw, null);
                return findNameTreeDestination(synthetic, name, new HashSet<>());
            }
            return "";
        }

        private String namedDictionaryValue(String dict, String wanted) {
            if (dict == null || dict.isEmpty()) return "";
            Matcher m = Pattern.compile("/([A-Za-z0-9_.+#-]+)").matcher(dict);
            while (m.find()) {
                String token = "/" + m.group(1);
                if (!wanted.equals(decodePdfName(token.substring(1)))) continue;
                String value = rawValue(dict, token);
                if (!value.isEmpty()) return resolveRawObject(this, value);
            }
            return "";
        }

        private String findNameTreeDestination(PdfObject node, String wanted, Set<Integer> seen) {
            if (node == null || (node.number >= 0 && !seen.add(node.number))) return "";
            String names = rawValue(node.dict, "/Names");
            if (!names.isEmpty()) {
                Matcher pair = Pattern.compile("(?s)(\\((?:\\\\.|[^\\)])*\\)|<(?!<)([0-9A-Fa-f\\s]+)>)[\\s]*(\\[[^\\]]*\\]|\\d+[\\s]+\\d+[\\s]+R)").matcher(names);
                while (pair.find()) {
                    String keyRaw = pair.group(1);
                    String key = keyRaw.startsWith("(") ? decodePdfLiteralString(keyRaw) : decodeUtf16Bytes(hexBytes(pair.group(2)));
                    if (wanted.equals(key)) return resolveRawObject(this, pair.group(3));
                }
            }
            for (Ref kid : parseRefs(rawValue(node.dict, "/Kids"))) {
                String found = findNameTreeDestination(get(kid.number), wanted, seen);
                if (!found.isEmpty()) return found;
            }
            return "";
        }

        private static boolean isPage(String dict) {
            return Pattern.compile("/Type\\s*/Page(?!s)\\b").matcher(dict == null ? "" : dict).find();
        }
    }

    private static final class PdfObject {
        final int number, generation;
        final String dict;
        final String raw;
        final byte[] stream;

        PdfObject(int number, int generation, String dict, String raw, byte[] stream) {
            this.number = number; this.generation = generation; this.dict = dict == null ? "" : dict; this.raw = raw == null ? "" : raw; this.stream = stream;
        }

        static PdfObject from(int number, int generation, byte[] body) {
            return fromRange(number, generation, body, 0, body.length);
        }

        static PdfObject fromRange(int number, int generation, byte[] source, int bodyStart, int bodyEnd) {
            int streamWord = indexOf(source, "stream".getBytes(LATIN1), bodyStart, bodyEnd);
            int textEnd = streamWord >= 0 ? streamWord : bodyEnd;
            // PDF dictionaries are normally tiny. Do not turn binary image streams into giant Java Strings.
            String head = new String(source, bodyStart, Math.max(0, textEnd - bodyStart), LATIN1);
            int dictStart = head.indexOf("<<");
            String dict = "";
            if (dictStart >= 0) {
                int dictEnd = balancedDictionaryEnd(head, dictStart);
                if (dictEnd > dictStart) dict = head.substring(dictStart, dictEnd);
            }
            byte[] stream = null;
            if (streamWord >= 0) {
                int dataStart = streamWord + 6;
                if (dataStart < bodyEnd && source[dataStart] == '\r') dataStart++;
                if (dataStart < bodyEnd && source[dataStart] == '\n') dataStart++;
                int endStream = indexOf(source, "endstream".getBytes(LATIN1), dataStart, bodyEnd);
                if (endStream >= dataStart) stream = Arrays.copyOfRange(source, dataStart, endStream);
            }
            return new PdfObject(number, generation, dict, head.trim(), stream);
        }
    }

    private static Match findObjectHeader(byte[] bytes, int from) {
        int p = Math.max(0, from);
        while (p < bytes.length) {
            int c = bytes[p] & 255;
            if (c < '0' || c > '9' || (p > 0 && !isPdfWhitespaceOrDelimiter(bytes[p - 1] & 255))) { p++; continue; }
            int nStart = p; long num = 0;
            while (p < bytes.length && bytes[p] >= '0' && bytes[p] <= '9') { num = num * 10 + (bytes[p++] - '0'); if (num > Integer.MAX_VALUE) break; }
            if (num > Integer.MAX_VALUE) continue;
            int ws = p; while (p < bytes.length && isPdfWhitespace(bytes[p] & 255)) p++; if (p == ws || p >= bytes.length || bytes[p] < '0' || bytes[p] > '9') { p = nStart + 1; continue; }
            long gen = 0; while (p < bytes.length && bytes[p] >= '0' && bytes[p] <= '9') { gen = gen * 10 + (bytes[p++] - '0'); if (gen > Integer.MAX_VALUE) break; }
            if (gen > Integer.MAX_VALUE) { p = nStart + 1; continue; }
            ws = p; while (p < bytes.length && isPdfWhitespace(bytes[p] & 255)) p++; if (p == ws || p + 3 > bytes.length) { p = nStart + 1; continue; }
            if (bytes[p] == 'o' && bytes[p + 1] == 'b' && bytes[p + 2] == 'j' && (p + 3 >= bytes.length || isPdfWhitespaceOrDelimiter(bytes[p + 3] & 255))) {
                return new Match((int) num, (int) gen, p + 3);
            }
            p = nStart + 1;
        }
        return null;
    }

    private static ParseBoundary findObjectBoundary(byte[] bytes, int bodyStart) {
        byte[] endObj = "endobj".getBytes(LATIN1);
        byte[] stream = "stream".getBytes(LATIN1);
        byte[] endStream = "endstream".getBytes(LATIN1);
        int eo = indexOf(bytes, endObj, bodyStart);
        if (eo < 0) return null;
        int st = indexOf(bytes, stream, bodyStart);
        if (st >= 0 && st < eo) {
            int es = indexOf(bytes, endStream, st + stream.length);
            if (es >= 0) {
                eo = indexOf(bytes, endObj, es + endStream.length);
                if (eo < 0) return null;
            }
        }
        return new ParseBoundary(eo, eo + endObj.length);
    }

    // ---------------------------------------------------------------------------------------------
    // Stream filters
    // ---------------------------------------------------------------------------------------------

    private static byte[] decodeStream(PdfObject object, PdfFile file) throws IOException {
        if (object.stream == null) return null;
        byte[] data = object.stream;
        List<String> filters = filterNames(object.dict);
        for (String filter : filters) {
            switch (filter) {
                case "/FlateDecode": case "/Fl": data = inflate(data); break;
                case "/ASCIIHexDecode": case "/AHx": data = asciiHex(data); break;
                case "/ASCII85Decode": case "/A85": data = ascii85(data); break;
                case "/RunLengthDecode": case "/RL": data = runLength(data); break;
                case "/LZWDecode": case "/LZW": data = lzw(data); break;
                case "/DCTDecode": case "/DCT": case "/JPXDecode": return data; // kept compressed for image writer
                default: throw new IOException("Неподдерживаемый PDF-фильтр " + filter);
            }
            if (data.length > MAX_STREAM_BYTES) throw new IOException("Слишком большой распакованный PDF-поток");
        }
        return data;
    }

    private static List<String> filterNames(String dict) {
        String raw = rawValue(dict, "/Filter");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("/[A-Za-z0-9]+" ).matcher(raw);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(data.length * 3, 1 << 20));
        byte[] buffer = new byte[32768];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n > 0) { out.write(buffer, 0, n); if (out.size() > MAX_STREAM_BYTES) throw new IOException("PDF Flate stream too large"); }
                else if (inflater.needsDictionary() || inflater.needsInput()) break;
                else throw new IOException("Повреждён Flate-поток PDF");
            }
        } catch (DataFormatException first) {
            inflater.end();
            inflater = new Inflater(true); inflater.setInput(data); out.reset();
            try {
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n > 0) out.write(buffer,0,n); else if (inflater.needsInput()) break; else throw new IOException("Повреждён Flate-поток PDF");
                }
            } catch (DataFormatException e) { throw new IOException("Повреждён Flate-поток PDF", e); }
        } finally { inflater.end(); }
        return out.toByteArray();
    }

    private static byte[] asciiHex(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int hi = -1;
        for (byte b : data) {
            int c = b & 255; if (c == '>') break; int v = Character.digit((char)c, 16); if (v < 0) continue;
            if (hi < 0) hi = v; else { out.write((hi << 4) | v); hi = -1; }
        }
        if (hi >= 0) out.write(hi << 4); return out.toByteArray();
    }

    private static byte[] ascii85(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); long value = 0; int count = 0;
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 255;
            if (Character.isWhitespace(c)) continue;
            if (c == '<' && i + 1 < data.length && data[i + 1] == '~') { i++; continue; }
            if (c == '~' && i + 1 < data.length && data[i + 1] == '>') break;
            if (c == 'z' && count == 0) { out.write(new byte[]{0,0,0,0},0,4); continue; }
            if (c < '!' || c > 'u') continue;
            value = value * 85 + (c - '!'); count++;
            if (count == 5) { out.write((byte)(value >> 24)); out.write((byte)(value >> 16)); out.write((byte)(value >> 8)); out.write((byte)value); value = 0; count = 0; }
        }
        if (count > 1) { for (int i=count;i<5;i++) value = value*85+84; for(int i=0;i<count-1;i++) out.write((byte)(value >> (24-8*i))); }
        return out.toByteArray();
    }

    private static byte[] runLength(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int p = 0;
        while (p < data.length) { int n = data[p++] & 255; if (n == 128) break; if (n <= 127) { int count=n+1; int end=Math.min(data.length,p+count); out.write(data,p,end-p); p=end; } else if (p<data.length) { int count=257-n; byte b=data[p++]; for(int i=0;i<count;i++) out.write(b); } }
        return out.toByteArray();
    }

    private static byte[] lzw(byte[] data) throws IOException {
        BitInput bits = new BitInput(data); List<byte[]> table = new ArrayList<>(4096); resetLzw(table); int width=9; int prev=-1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int code=bits.read(width); if(code<0) break; if(code==256){resetLzw(table);width=9;prev=-1;continue;} if(code==257)break;
            byte[] entry;
            if(code<table.size() && table.get(code)!=null) entry=table.get(code); else if(code==table.size() && prev>=0){byte[] p=table.get(prev);entry=appendByte(p,p[0]);} else throw new IOException("Повреждён LZW-поток PDF");
            out.write(entry,0,entry.length);
            if(prev>=0 && table.size()<4096){byte[] p=table.get(prev);table.add(appendByte(p,entry[0])); if(table.size()==511||table.size()==1023||table.size()==2047) width++;}
            prev=code;
        }
        return out.toByteArray();
    }

    private static void resetLzw(List<byte[]> table){table.clear();for(int i=0;i<256;i++)table.add(new byte[]{(byte)i});table.add(null);table.add(null);}
    private static byte[] appendByte(byte[] a, byte b){byte[] x=Arrays.copyOf(a,a.length+1);x[a.length]=b;return x;}

    // ---------------------------------------------------------------------------------------------
    // Resources, fonts and content stream interpreter
    // ---------------------------------------------------------------------------------------------

    private static final class ResourceSet {
        final PdfFile file;
        final Map<String, FontInfo> fonts = new HashMap<>();
        final Map<String, Ref> xobjects = new HashMap<>();
        final Map<String, String> colorSpaces = new HashMap<>();

        ResourceSet(PdfFile file) { this.file = file; }

        static ResourceSet parse(PdfFile file, String resources) {
            ResourceSet set = new ResourceSet(file);
            String fontDict = dictionaryValue(file, resources, "/Font");
            for (Map.Entry<String, Ref> e : namedRefs(fontDict).entrySet()) {
                PdfObject fo = file.get(e.getValue().number);
                if (fo != null) set.fonts.put(e.getKey(), FontInfo.parse(file, fo));
            }
            String xo = dictionaryValue(file, resources, "/XObject");
            set.xobjects.putAll(namedRefs(xo));
            String cs = dictionaryValue(file, resources, "/ColorSpace");
            if (!cs.isEmpty()) {
                Matcher names=Pattern.compile("/([A-Za-z0-9_.+-]+)").matcher(cs);
                Set<String> seen=new HashSet<>();
                while(names.find()){String key=names.group(1);if(!seen.add(key)||isBuiltinColorSpace(key))continue;String v=rawValue(cs,"/"+key);if(!v.isEmpty())set.colorSpaces.put(key,resolveRawObject(file,v));}
            }
            return set;
        }

        String resolveColorSpace(String raw){
            String r=resolveRawObject(file,raw);
            String t=r==null?"":r.trim();
            if(t.startsWith("/")&&!t.startsWith("/Device")&&!t.startsWith("/Cal")){String key=firstName(t);if(key.startsWith("/"))key=key.substring(1);String mapped=colorSpaces.get(key);if(mapped!=null&&!mapped.isEmpty())return mapped;}
            return r;
        }
        private static boolean isBuiltinColorSpace(String n){return "DeviceRGB".equals(n)||"DeviceGray".equals(n)||"DeviceCMYK".equals(n)||"CalRGB".equals(n)||"CalGray".equals(n)||"Lab".equals(n)||"ICCBased".equals(n)||"Indexed".equals(n)||"I".equals(n)||"Pattern".equals(n)||"Separation".equals(n)||"DeviceN".equals(n);}
    }

    private static final class FontInfo {
        final Map<ByteKey, String> cmap = new HashMap<>();
        String encoding = "";
        final Map<Integer,String> differences = new HashMap<>();
        final Map<Integer,Double> widths = new HashMap<>();
        double defaultWidth = 500.0;
        boolean reliableWidths;
        boolean twoByteCodes;

        static FontInfo parse(PdfFile file, PdfObject font) {
            FontInfo info = new FontInfo();
            String enc = rawValue(font.dict, "/Encoding");
            Ref encRef = parseRef(enc);
            if (encRef != null) { PdfObject eo=file.get(encRef.number); if(eo!=null) enc=eo.raw.isEmpty()?eo.dict:eo.raw; }
            info.encoding = nameValue(enc, "/BaseEncoding");
            if (info.encoding.isEmpty() && enc.trim().startsWith("/")) info.encoding = firstName(enc);
            parseDifferences(enc, info.differences);
            Ref tu = parseRef(rawValue(font.dict, "/ToUnicode"));
            if (tu != null) {
                PdfObject cmapObj = file.get(tu.number);
                if (cmapObj != null && cmapObj.stream != null) try { parseCMap(decodeStream(cmapObj,file), info.cmap); } catch (Exception ignored) {}
            }

            String subtype = nameValue(font.dict, "/Subtype");
            info.twoByteCodes = "/Type0".equals(subtype) || "/Identity-H".equals(info.encoding) || "/Identity-V".equals(info.encoding);
            if ("/Type0".equals(subtype)) {
                List<Ref> descRefs = parseRefs(rawValue(font.dict, "/DescendantFonts"));
                if (!descRefs.isEmpty()) {
                    PdfObject descendant = file.get(descRefs.get(0).number);
                    if (descendant != null) {
                        info.defaultWidth = intValue(descendant.dict, "/DW", 1000);
                        String w = resolveRawObject(file, rawValue(descendant.dict, "/W"));
                        if (!w.isEmpty()) {
                            parseCidWidths(w, info.widths);
                            info.reliableWidths = true;
                        } else if (rawValue(descendant.dict, "/DW").length() > 0) {
                            info.reliableWidths = true;
                        }
                    }
                }
            } else {
                int firstChar = intValue(font.dict, "/FirstChar", 0);
                String wr = resolveRawObject(file, rawValue(font.dict, "/Widths"));
                List<Double> ws = parseNumbers(wr);
                if (!ws.isEmpty()) {
                    for (int i=0;i<ws.size();i++) info.widths.put(firstChar+i, ws.get(i));
                    info.reliableWidths = true;
                }
                Ref fd = parseRef(rawValue(font.dict, "/FontDescriptor"));
                if (fd != null) {
                    PdfObject fdo = file.get(fd.number);
                    if (fdo != null) info.defaultWidth = intValue(fdo.dict, "/MissingWidth", (int)info.defaultWidth);
                }
            }
            return info;
        }

        DecodedRun decodeRun(byte[] bytes) {
            StringBuilder out = new StringBuilder();
            double widthUnits = 0;
            int glyphs = 0, spaces = 0, p = 0;
            if (!cmap.isEmpty()) {
                while (p < bytes.length) {
                    String best = null; int bestLen = 0;
                    for (int len=1; len<=4 && p+len<=bytes.length; len++) {
                        String mapped = cmap.get(new ByteKey(bytes,p,len));
                        if (mapped != null) { best=mapped; bestLen=len; }
                    }
                    int code;
                    String mapped;
                    if (best != null) {
                        code = bytesInt(Arrays.copyOfRange(bytes,p,p+bestLen));
                        mapped = best;
                        p += bestLen;
                    } else {
                        code = bytes[p] & 255;
                        mapped = fallbackByte(code);
                        p++;
                    }
                    out.append(mapped); glyphs++; widthUnits += widthFor(code);
                    if (containsSpace(mapped)) spaces++;
                }
            } else if (twoByteCodes && bytes.length >= 2) {
                while (p + 1 < bytes.length) {
                    int code=((bytes[p]&255)<<8)|(bytes[p+1]&255); p+=2;
                    String mapped = code >= 32 ? new String(Character.toChars(code)) : "";
                    out.append(mapped); glyphs++; widthUnits += widthFor(code);
                    if (containsSpace(mapped)) spaces++;
                }
                if (p < bytes.length) { int code=bytes[p]&255; String mapped=fallbackByte(code); out.append(mapped); glyphs++; widthUnits+=widthFor(code); if(containsSpace(mapped))spaces++; }
            } else {
                for (byte b : bytes) {
                    int code=b&255; String mapped=differences.get(code); if(mapped==null||mapped.isEmpty())mapped=fallbackByte(code);
                    out.append(mapped); glyphs++; widthUnits += widthFor(code); if(containsSpace(mapped))spaces++;
                }
            }
            return new DecodedRun(out.toString(), glyphs, spaces, widthUnits, reliableWidths);
        }

        private double widthFor(int code) { Double w=widths.get(code); return w==null?defaultWidth:w; }

        private String fallbackByte(int c) {
            if (c < 32 && c != 9) return "";
            if ("/MacRomanEncoding".equals(encoding)) { try{return new String(new byte[]{(byte)c},Charset.forName("x-MacRoman"));}catch(Exception ignored){} }
            return new String(new byte[]{(byte)c}, Charset.forName("windows-1252"));
        }
    }

    private static final class DecodedRun {
        final String text; final int glyphs, spaces; final double widthUnits; final boolean reliableWidth;
        DecodedRun(String t,int g,int s,double w,boolean r){text=t;glyphs=g;spaces=s;widthUnits=w;reliableWidth=r;}
    }

    private static boolean containsSpace(String s){for(int i=0;i<s.length();i++)if(Character.isWhitespace(s.charAt(i))||s.charAt(i)=='\u00a0')return true;return false;}

    private static void parseCidWidths(String raw, Map<Integer,Double> out) {
        try {
            Object root = new ContentTokenizer(raw.getBytes(LATIN1)).next();
            if (!(root instanceof PdfArray)) return;
            List<Object> a=((PdfArray)root).items;
            int i=0;
            while(i<a.size()) {
                if (!(a.get(i) instanceof Double)) { i++; continue; }
                int first=(int)Math.round((Double)a.get(i++));
                if(i>=a.size())break;
                Object next=a.get(i++);
                if(next instanceof PdfArray) {
                    int code=first;
                    for(Object w:((PdfArray)next).items) if(w instanceof Double) out.put(code++,(Double)w);
                } else if(next instanceof Double && i<a.size() && a.get(i) instanceof Double) {
                    int last=(int)Math.round((Double)next); double w=(Double)a.get(i++);
                    if(last-first<100000) for(int code=first;code<=last;code++) out.put(code,w);
                }
            }
        } catch(Exception ignored) {}
    }

    private static void parseCMap(byte[] data, Map<ByteKey,String> out) {
        String s=new String(data,LATIN1);
        Matcher charM=Pattern.compile("(?s)beginbfchar(.*?)endbfchar").matcher(s);
        while(charM.find()){Matcher m=Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").matcher(charM.group(1));while(m.find()){byte[] src=hexBytes(m.group(1));out.put(new ByteKey(src,0,src.length),decodeUnicodeHex(m.group(2)));}}
        Matcher rangeM=Pattern.compile("(?s)beginbfrange(.*?)endbfrange").matcher(s);
        while(rangeM.find()){
            String block=rangeM.group(1);
            Matcher array=Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[(.*?)\\]",Pattern.DOTALL).matcher(block);
            while(array.find()){byte[] lo=hexBytes(array.group(1)),hi=hexBytes(array.group(2));int a=bytesInt(lo),b=bytesInt(hi);Matcher dest=Pattern.compile("<([0-9A-Fa-f]+)>").matcher(array.group(3));int code=a;while(dest.find()&&code<=b){out.put(new ByteKey(intBytes(code,lo.length),0,lo.length),decodeUnicodeHex(dest.group(1)));code++;}}
            String stripped=array.replaceAll(" ");
            Matcher direct=Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").matcher(stripped);
            while(direct.find()){byte[] lo=hexBytes(direct.group(1)),hi=hexBytes(direct.group(2)),dst=hexBytes(direct.group(3));int a=bytesInt(lo),b=bytesInt(hi),d=bytesInt(dst);for(int code=a;code<=b&&code-a<65536;code++){byte[] db=intBytes(d+(code-a),dst.length);out.put(new ByteKey(intBytes(code,lo.length),0,lo.length),decodeUtf16Bytes(db));}}
        }
    }

    private static void parseDifferences(String enc, Map<Integer,String> out) {
        int at=enc.indexOf("/Differences"); if(at<0)return; int l=enc.indexOf('[',at); if(l<0)return; int r=matching(enc,l,'[',']');if(r<0)return;
        String in=enc.substring(l+1,r);Matcher m=Pattern.compile("\\d+|/[A-Za-z0-9_.]+" ).matcher(in);int code=0;
        while(m.find()){String t=m.group();if(Character.isDigit(t.charAt(0)))code=Integer.parseInt(t);else{out.put(code++,glyphName(t.substring(1)));}}
    }

    private static String glyphName(String n) {
        if(n.length()==1)return n; if(n.startsWith("uni")&&n.length()>=7){try{return new String(Character.toChars(Integer.parseInt(n.substring(3,7),16)));}catch(Exception ignored){}}
        if(n.startsWith("u")&&n.length()>=5){try{return new String(Character.toChars(Integer.parseInt(n.substring(1),16)));}catch(Exception ignored){}}
        String v=GLYPHS.get(n);return v==null?"":v;
    }

    private static final Map<String,String> GLYPHS = buildGlyphs();
    private static Map<String,String> buildGlyphs(){Map<String,String>m=new HashMap<>();String[][]pairs={{"space"," "},{"hyphen","-"},{"minus","−"},{"period","."},{"comma",","},{"colon",":"},{"semicolon",";"},{"quotedbl","\""},{"quotesingle","'"},{"parenleft","("},{"parenright",")"},{"bracketleft","["},{"bracketright","]"},{"slash","/"},{"backslash","\\"},{"exclam","!"},{"question","?"},{"endash","–"},{"emdash","—"},{"ellipsis","…"},{"bullet","•"},{"copyright","©"},{"registered","®"},{"numero","№"}};for(String[]p:pairs)m.put(p[0],p[1]);String[]digits={"zero","one","two","three","four","five","six","seven","eight","nine"};for(int i=0;i<10;i++)m.put(digits[i],Integer.toString(i));for(char c='A';c<='Z';c++)m.put(String.valueOf(c),String.valueOf(c));for(char c='a';c<='z';c++)m.put(String.valueOf(c),String.valueOf(c));
        // Adobe AFII names commonly found in older Cyrillic PDFs.
        String upper="АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";String lower="абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        int[] upperAfii={10017,10018,10019,10020,10021,10022,10023,10024,10025,10026,10027,10028,10029,10030,10031,10032,10033,10034,10035,10036,10037,10038,10039,10040,10041,10042,10043,10044,10045,10046,10047,10048,10049};
        int[] lowerAfii={10065,10066,10067,10068,10069,10070,10071,10072,10073,10074,10075,10076,10077,10078,10079,10080,10081,10082,10083,10084,10085,10086,10087,10088,10089,10090,10091,10092,10093,10094,10095,10096,10097};
        for(int i=0;i<upperAfii.length;i++){m.put("afii"+upperAfii[i],String.valueOf(upper.charAt(i)));m.put("afii"+lowerAfii[i],String.valueOf(lower.charAt(i)));}return m;}

    private static final class ContentInterpreter {
        final PdfFile file; final ResourceSet resources; final PageResult page; final ImageStore images;
        final List<Object> stack = new ArrayList<>();
        ContentInterpreter(PdfFile f, ResourceSet r, PageResult p, ImageStore i){file=f;resources=r;page=p;images=i;}

        void run(byte[] content, GraphicsState initial, int depth) {
            if(depth>5||content==null)return; ContentTokenizer t=new ContentTokenizer(content); GraphicsState gs=initial; Deque<GraphicsState> saved=new ArrayDeque<>(); Object tok;
            while((tok=t.next())!=null){if(tok instanceof Operator){String op=((Operator)tok).value;try{gs=operate(op,gs,saved,depth);}catch(Exception ignored){}stack.clear();}else stack.add(tok);}
        }

        GraphicsState operate(String op, GraphicsState gs, Deque<GraphicsState> saved, int depth) throws IOException {
            switch(op){
                case "q":saved.push(gs.copy());break; case "Q":if(!saved.isEmpty())gs=saved.pop();break;
                case "cm":if(stack.size()>=6)gs.ctm=gs.ctm.multiply(matrix(lastNum(6),lastNum(5),lastNum(4),lastNum(3),lastNum(2),lastNum(1)));break;
                case "BT":gs.inText=true;gs.tx=0;gs.ty=0;gs.lineX=0;gs.lineY=0;break;case "ET":gs.inText=false;break;
                case "Tf":if(stack.size()>=2){Object n=stack.get(stack.size()-2);if(n instanceof Name)gs.font=((Name)n).value;gs.fontSize=lastNum(1);}break;
                case "TL":gs.leading=lastNum(1);break;case "Tc":gs.charSpace=lastNum(1);break;case "Tw":gs.wordSpace=lastNum(1);break;case "Tz":gs.hScale=lastNum(1)/100.0;break;
                case "Tm":if(stack.size()>=6){gs.tx=lastNum(2);gs.ty=lastNum(1);gs.lineX=gs.tx;gs.lineY=gs.ty;}break;
                case "Td":if(stack.size()>=2){gs.lineX+=lastNum(2);gs.lineY+=lastNum(1);gs.tx=gs.lineX;gs.ty=gs.lineY;}break;
                case "TD":if(stack.size()>=2){double x=lastNum(2),y=lastNum(1);gs.leading=-y;gs.lineX+=x;gs.lineY+=y;gs.tx=gs.lineX;gs.ty=gs.lineY;}break;
                case "T*":gs.lineY-=gs.leading;gs.tx=gs.lineX;gs.ty=gs.lineY;break;
                case "Tj":if(!stack.isEmpty())show(stack.get(stack.size()-1),gs);break;
                case "TJ":if(!stack.isEmpty()&&stack.get(stack.size()-1) instanceof PdfArray){for(Object x:((PdfArray)stack.get(stack.size()-1)).items){if(x instanceof PdfString)show(x,gs);else if(x instanceof Double){double gap=-((Double)x)/1000.0*gs.fontSize*gs.hScale;gs.tx+=gap;if(gap>Math.abs(gs.fontSize)*0.12)gs.pendingSpace=true;}}}break;
                case "'":gs.lineY-=gs.leading;gs.tx=gs.lineX;gs.ty=gs.lineY;if(!stack.isEmpty())show(stack.get(stack.size()-1),gs);break;
                case "\"":if(stack.size()>=3){gs.wordSpace=lastNum(3);gs.charSpace=lastNum(2);}gs.lineY-=gs.leading;gs.tx=gs.lineX;gs.ty=gs.lineY;if(!stack.isEmpty())show(stack.get(stack.size()-1),gs);break;
                case "Do":if(!stack.isEmpty()&&stack.get(stack.size()-1) instanceof Name)doXObject(((Name)stack.get(stack.size()-1)).value,gs,depth);break;
            }
            return gs;
        }

        void show(Object token, GraphicsState gs){
            if(!(token instanceof PdfString)||!gs.inText)return;
            PdfString ps=(PdfString)token; FontInfo fi=resources.fonts.get(gs.font);
            DecodedRun run=fi==null?new DecodedRun(new String(ps.bytes,Charset.forName("windows-1252")),ps.bytes.length,0,0,false):fi.decodeRun(ps.bytes);
            String cleaned=cleanExtractedPreserveEdges(run.text);
            boolean before=gs.pendingSpace||(!cleaned.isEmpty()&&Character.isWhitespace(cleaned.charAt(0)));
            boolean after=!cleaned.isEmpty()&&Character.isWhitespace(cleaned.charAt(cleaned.length()-1));
            String text=cleaned.trim();
            double width=run.reliableWidth?((run.widthUnits/1000.0)*gs.fontSize+gs.charSpace*Math.max(0,run.glyphs-1)+gs.wordSpace*run.spaces)*gs.hScale:estimateTextWidth(cleaned,gs.fontSize,gs.charSpace,gs.wordSpace,gs.hScale);
            if(!Double.isFinite(width))width=0;
            if(!text.isEmpty()){
                Point p=gs.ctm.apply(gs.tx,gs.ty);
                double fs=Math.max(1,Math.abs(gs.fontSize)*Math.max(Math.abs(gs.ctm.a),Math.abs(gs.ctm.d)));
                Point p2=gs.ctm.apply(gs.tx+width,gs.ty);
                double avgAdvance=Math.abs(width)/Math.max(1,run.glyphs);
                page.spans.add(new TextSpan(text,p.x,p.y,p2.x,p2.y,fs,before,after,avgAdvance));
                page.textChars+=text.replaceAll("\\s+","").length();
            }
            gs.tx+=width; gs.pendingSpace=after;
        }

        void doXObject(String name, GraphicsState gs, int depth) throws IOException {Ref r=resources.xobjects.get(name);if(r==null)return;PdfObject o=file.get(r.number);if(o==null||o.stream==null)return;String subtype=nameValue(o.dict,"/Subtype");if("/Image".equals(subtype)){PositionedImage im=images.store(o,gs.ctm,page.width,page.height,resources);if(im!=null)page.images.add(im);}else if("/Form".equals(subtype)){byte[] d=decodeStream(o,file);Matrix form=gs.ctm;List<Double> nums=parseNumbers(rawValue(o.dict,"/Matrix"));if(nums.size()>=6)form=form.multiply(matrix(nums.get(0),nums.get(1),nums.get(2),nums.get(3),nums.get(4),nums.get(5)));String res=dictionaryValue(file,o.dict,"/Resources");ResourceSet nested=res.isEmpty()?resources:ResourceSet.parse(file,res);new ContentInterpreter(file,nested,page,images).run(d,new GraphicsState(form),depth+1);}}

        double lastNum(int fromEnd){Object o=stack.get(stack.size()-fromEnd);return o instanceof Double?(Double)o:0;}
    }

    private static double estimateTextWidth(String text,double fs,double charSpace,double wordSpace,double hScale){double units=0;for(int i=0;i<text.length();i++){char c=text.charAt(i);units+=glyphEmWidth(c)*fs;if(Character.isWhitespace(c))units+=wordSpace;if(i+1<text.length())units+=charSpace;}return units*hScale;}
    private static double glyphEmWidth(char c){if(Character.isWhitespace(c))return .278;if("ilI|!.,:;'`".indexOf(c)>=0)return .25;if("fjrt()[]{}".indexOf(c)>=0)return .34;if("mwMW@%&ШЩЖЮФ".indexOf(c)>=0)return .82;if(Character.isDigit(c))return .556;if(Character.isUpperCase(c))return .66;if(Character.isLetter(c))return .53;return .5;}
    private static String cleanExtractedPreserveEdges(String s){if(s==null||s.isEmpty())return"";return s.replace('\u0000',' ').replace('\u00a0',' ').replaceAll("[\\p{Cntrl}&&[^\\t\\n\\r]]"," ").replaceAll("[ \t\r\n]+"," ");}

    private static final class ContentTokenizer {
        final byte[] d; int p;
        ContentTokenizer(byte[] d){this.d=d;}
        Object next(){skip();if(p>=d.length)return null;int c=d[p]&255;if(c=='(')return new PdfString(readLiteral());if(c=='<'){if(p+1<d.length&&d[p+1]=='<'){String raw=readBalancedText('<','>');return new Operator(raw);}return new PdfString(readHex());}if(c=='[')return readArray();if(c=='/'){p++;int s=p;while(p<d.length&&!delim(d[p]&255))p++;return new Name(new String(d,s,p-s,LATIN1));}int s=p;while(p<d.length&&!delim(d[p]&255))p++;if(s==p){p++;return next();}String t=new String(d,s,p-s,LATIN1);try{if(NUMBER.matcher(t).matches())return Double.parseDouble(t);}catch(Exception ignored){}return new Operator(t);}
        PdfArray readArray(){p++;List<Object>x=new ArrayList<>();while(true){skip();if(p>=d.length||d[p]==']'){if(p<d.length)p++;break;}Object o=next();if(o!=null)x.add(o);}return new PdfArray(x);}
        byte[] readLiteral(){p++;ByteArrayOutputStream o=new ByteArrayOutputStream();int depth=1;while(p<d.length&&depth>0){int c=d[p++]&255;if(c=='\\'){if(p>=d.length)break;int e=d[p++]&255;switch(e){case'n':o.write('\n');break;case'r':o.write('\r');break;case't':o.write('\t');break;case'b':o.write('\b');break;case'f':o.write('\f');break;case'\r':if(p<d.length&&d[p]=='\n')p++;break;case'\n':break;default:if(e>='0'&&e<='7'){int v=e-'0',n=1;while(n<3&&p<d.length&&d[p]>='0'&&d[p]<='7'){v=v*8+(d[p++]-'0');n++;}o.write(v);}else o.write(e);}}else if(c=='('){depth++;o.write(c);}else if(c==')'){depth--;if(depth>0)o.write(c);}else o.write(c);}return o.toByteArray();}
        byte[] readHex(){p++;StringBuilder s=new StringBuilder();while(p<d.length){int c=d[p++]&255;if(c=='>')break;if(Character.digit((char)c,16)>=0)s.append((char)c);}if((s.length()&1)==1)s.append('0');return hexBytes(s.toString());}
        String readBalancedText(char open,char close){int start=p,depth=0;while(p<d.length){char c=(char)(d[p++]&255);if(c==open)depth++;if(c==close){depth--;if(depth==0&&p<d.length&&d[p-1]==close&&p>=2&&d[p-2]==close)break;}}return new String(d,start,p-start,LATIN1);}
        void skip(){while(p<d.length){int c=d[p]&255;if(Character.isWhitespace(c)){p++;continue;}if(c=='%'){while(p<d.length&&d[p]!='\n'&&d[p]!='\r')p++;continue;}break;}}
        boolean delim(int c){return Character.isWhitespace(c)||c=='('||c==')'||c=='<'||c=='>'||c=='['||c==']'||c=='{'||c=='}'||c=='/'||c=='%';}
    }

    // ---------------------------------------------------------------------------------------------
    // Page text reconstruction
    // ---------------------------------------------------------------------------------------------

    private static final class PageResult {
        final int pageIndex; double width=612,height=792,originX,originY; int rotation; int textChars;
        final List<TextSpan> spans=new ArrayList<>(); final List<Line> lines=new ArrayList<>(); final List<PositionedImage> images=new ArrayList<>();
        PageResult(int i){pageIndex=i;}
        void finishLines(){spans.sort((a,b)->{int y=Double.compare(b.y,a.y);return y!=0?y:Double.compare(a.x,b.x);});for(TextSpan s:spans){Line best=null;double bd=Double.MAX_VALUE;for(int i=Math.max(0,lines.size()-12);i<lines.size();i++){Line l=lines.get(i);double d=Math.abs(l.y-s.y);double tol=Math.max(2.5,Math.min(l.fontSize,s.fontSize)*0.55);double hgap=s.x>l.maxX?s.x-l.maxX:(l.minX>Math.max(s.x,s.x2)?l.minX-Math.max(s.x,s.x2):0);if(hgap>Math.max(50,width*.12))continue;if(d<=tol&&d<bd){best=l;bd=d;}}if(best==null){best=new Line(s);lines.add(best);}else best.add(s);}for(Line l:lines)l.finish();lines.sort(Line.READING_ORDER);mergeAlignedTableRows();}
        void mergeAlignedTableRows(){if(lines.size()<4||width<=0)return;List<LineRow>rows=new ArrayList<>();for(Line l:lines){LineRow row=rows.isEmpty()?null:rows.get(rows.size()-1);double tol=Math.max(2.5,l.fontSize*.55);if(row==null||Math.abs(row.y-l.y)>tol){row=new LineRow(l);rows.add(row);}else row.add(l);}boolean[]merge=new boolean[rows.size()];for(int i=0;i<rows.size();i++){LineRow r=rows.get(i);if(r.parts.size()>=3&&r.span()>width*.20)merge[i]=true;}for(int i=0;i+1<rows.size();i++){LineRow a=rows.get(i),b=rows.get(i+1);if(!a.pairCandidate(width)||!b.pairCandidate(width))continue;double vgap=a.y-b.y;double fs=Math.max(a.fontSize,b.fontSize);if(vgap<0||vgap>fs*2.5+7)continue;if(Math.abs(a.parts.get(0).minX-b.parts.get(0).minX)>width*.06)continue;if(Math.abs(a.parts.get(1).minX-b.parts.get(1).minX)>width*.08)continue;merge[i]=merge[i+1]=true;}List<Line>out=new ArrayList<>();for(int i=0;i<rows.size();i++){LineRow r=rows.get(i);if(merge[i]&&r.parts.size()>=2)out.add(r.merged());else out.addAll(r.parts);}lines.clear();lines.addAll(out);lines.sort(Line.READING_ORDER);}
    }
    private static final class TextSpan {final String text;final double x,y,x2,y2,fontSize,avgAdvance;final boolean spaceBefore,spaceAfter;TextSpan(String t,double x,double y,double x2,double y2,double f,boolean sb,boolean sa,double aa){text=t;this.x=x;this.y=y;this.x2=x2;this.y2=y2;fontSize=f;spaceBefore=sb;spaceAfter=sa;avgAdvance=aa;}}
    private static final class Line {
        String text="";double y,minX,maxX,fontSize,largestGap;int separatedRuns;final List<TextSpan> spans=new ArrayList<>();
        static final Comparator<Line> READING_ORDER=(a,b)->{double dy=b.y-a.y;if(Math.abs(dy)>2)return dy>0?1:-1;return Double.compare(a.minX,b.minX);};
        Line(TextSpan s){y=s.y;minX=Math.min(s.x,s.x2);maxX=Math.max(s.x,s.x2);fontSize=s.fontSize;spans.add(s);}
        void add(TextSpan s){spans.add(s);y=(y*(spans.size()-1)+s.y)/spans.size();minX=Math.min(minX,Math.min(s.x,s.x2));maxX=Math.max(maxX,Math.max(s.x,s.x2));fontSize=Math.max(fontSize,s.fontSize);}
        void finish(){spans.sort(Comparator.comparingDouble(a->Math.min(a.x,a.x2)));StringBuilder b=new StringBuilder();TextSpan prevSpan=null;double prev=Double.NaN;for(TextSpan s:spans){if(s.text.isEmpty())continue;boolean addSpace=false;if(b.length()>0){double gap=Double.isNaN(prev)?0:Math.min(s.x,s.x2)-prev;double fs=Math.min(prevSpan==null?s.fontSize:prevSpan.fontSize,s.fontSize);double measured=Math.max(prevSpan==null?0:prevSpan.avgAdvance,s.avgAdvance);double charW=Math.max(1.0,Math.min(measured,Math.max(2.0,fs*0.75)));double threshold=Math.max(0.9,charW*0.30);largestGap=Math.max(largestGap,gap);if(gap>Math.max(charW*2.6,fs*1.8))separatedRuns++;addSpace=s.spaceBefore||(prevSpan!=null&&prevSpan.spaceAfter)||gap>threshold;if(noSpaceBefore(s.text)||noSpaceAfter(b))addSpace=false;}if(addSpace)b.append(' ');b.append(s.text);prev=Math.max(s.x,s.x2);prevSpan=s;}text=b.toString().replaceAll("\\s+"," ").trim();}
        boolean isEdge(double h){return y>h*.92||y<h*.08;}
    }
    private static final class LineRow {final List<Line>parts=new ArrayList<>();double y,fontSize;LineRow(Line l){add(l);}void add(Line l){parts.add(l);parts.sort(Comparator.comparingDouble(a->a.minX));double sy=0,sf=0;for(Line p:parts){sy+=p.y;sf=Math.max(sf,p.fontSize);}y=sy/parts.size();fontSize=sf;}double span(){return parts.isEmpty()?0:parts.get(parts.size()-1).maxX-parts.get(0).minX;}boolean pairCandidate(double pageWidth){if(parts.size()!=2)return false;Line a=parts.get(0),b=parts.get(1);double gap=b.minX-a.maxX;return gap>pageWidth*.07&&(a.maxX-a.minX)<pageWidth*.34&&(b.maxX-b.minX)<pageWidth*.34&&a.text.length()<=70&&b.text.length()<=70;}Line merged(){Line first=parts.get(0);TextSpan seed=first.spans.get(0);Line out=new Line(seed);for(int i=1;i<first.spans.size();i++)out.add(first.spans.get(i));for(int p=1;p<parts.size();p++)for(TextSpan sp:parts.get(p).spans)out.add(sp);out.finish();return out;}}
    private static boolean noSpaceBefore(String s){if(s==null||s.isEmpty())return false;char c=s.charAt(0);return ",.;:!?%)]}»”’".indexOf(c)>=0;}
    private static boolean noSpaceAfter(StringBuilder s){if(s.length()==0)return false;char c=s.charAt(s.length()-1);return "([{«“‘".indexOf(c)>=0;}
    private enum BlockKind { TEXT, LIST, QUOTE, CAPTION, COMPLEX }
    private static final class Paragraph {
        final List<Line> lines=new ArrayList<>();Paragraph(Line l){lines.add(l);}void add(Line l){lines.add(l);}
        boolean accepts(Line n){Line p=lines.get(lines.size()-1);double gap=p.y-n.y;double lh=Math.max(p.fontSize,n.fontSize);if(gap< -2||gap>lh*1.9+5)return false;if(Math.abs(n.fontSize-p.fontSize)>Math.max(1.8,lh*.18))return false;if(Math.abs(n.minX-p.minX)>Math.max(28,lh*3.0)&&!endsSentence(p.text))return false;return true;}
        Block toBlock(float median,double pageWidth){StringBuilder s=new StringBuilder();StringBuilder preserve=new StringBuilder();int complexLines=0;double minX=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,minY=Double.MAX_VALUE,maxY=-Double.MAX_VALUE;for(int i=0;i<lines.size();i++){Line line=lines.get(i);String t=line.text.trim();if(t.isEmpty())continue;if(line.separatedRuns>=2||line.largestGap>Math.max(28,line.fontSize*4.2))complexLines++;if(preserve.length()>0)preserve.append('\n');preserve.append(t);if(s.length()>0){if(endsHyphen(s)&&startsLower(t))s.setLength(s.length()-1);else s.append(' ');}s.append(t);minX=Math.min(minX,line.minX);maxX=Math.max(maxX,line.maxX);minY=Math.min(minY,line.y-line.fontSize*.42);maxY=Math.max(maxY,line.y+line.fontSize*1.08);}Line first=lines.get(0);double avg=0;for(Line l:lines)avg+=l.fontSize;avg/=lines.size();boolean shortish=s.length()<150;boolean centered=first.minX>pageWidth*.12&&first.maxX<pageWidth*.88;int heading=avg>median*1.55&&shortish?1:(avg>median*1.25&&shortish&&(centered||lines.size()<=2)?2:0);boolean firstIndent=lines.size()>1&&first.minX-lines.get(1).minX>Math.max(8,avg*.65);BlockKind kind=BlockKind.TEXT;if(heading==0){if(isListText(s.toString()))kind=BlockKind.LIST;else if(avg<median*.88&&shortish&&centered&&lines.size()<=2)kind=BlockKind.CAPTION;else if(isQuoteText(s.toString(),first,pageWidth))kind=BlockKind.QUOTE;else if(complexLines>=Math.max(2,(lines.size()+1)/2)||looksFormula(s.toString()))kind=BlockKind.COMPLEX;}if(minX==Double.MAX_VALUE){minX=first.minX;maxX=first.maxX;minY=first.y-first.fontSize*.42;maxY=first.y+first.fontSize*1.08;}return Block.text(kind==BlockKind.COMPLEX?preserve.toString():s.toString(),heading,kind,firstIndent,minX,maxX,minY,maxY);}}
    private static boolean isListText(String s){return s!=null&&s.matches("^(?:[•·▪◦‒–—*\\-]|\\(?\\d{1,3}[.)]|[A-Za-zА-Яа-я][.)])\\s+.+");}
    private static boolean isQuoteText(String s,Line first,double pageWidth){if(s==null||s.length()<25||s.length()>700)return false;boolean quoted=(s.startsWith("«")&&s.endsWith("»"))||(s.startsWith("“")&&s.endsWith("”"));boolean inset=first.minX>pageWidth*.10&&first.maxX<pageWidth*.90;return quoted||(inset&&s.length()<320&&endsSentence(s));}
    private static boolean looksFormula(String s){if(s==null||s.length()<3||s.length()>500)return false;int math=0,letters=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isLetter(c))letters++;if("=±×÷√∑∫≈≠≤≥∞^_<>→←∂∆∇{}[]|".indexOf(c)>=0)math++;}return math>=3&&math*3>=Math.max(3,letters);}
    private static boolean endsSentence(String s){return s.endsWith(".")||s.endsWith("!")||s.endsWith("?")||s.endsWith(":")||s.endsWith(";")||s.endsWith("…");}private static boolean endsHyphen(StringBuilder s){if(s.length()==0)return false;char c=s.charAt(s.length()-1);return c=='-'||c=='\u00ad';}private static boolean startsLower(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isLetter(c))return Character.isLowerCase(c);if(!Character.isWhitespace(c)&&!Character.isDigit(c))return false;}return false;}
    private static final class Block {final String text;final int headingLevel;final PositionedImage image;final BlockKind kind;final boolean firstLineIndent;final double minX,maxX,minY,maxY;Block(String t,int h,PositionedImage i,BlockKind k,boolean indent,double minX,double maxX,double minY,double maxY){text=t;headingLevel=h;image=i;kind=k;firstLineIndent=indent;this.minX=minX;this.maxX=maxX;this.minY=minY;this.maxY=maxY;}static Block text(String t,int h,BlockKind k,boolean indent,double minX,double maxX,double minY,double maxY){return new Block(t,h,null,k,indent,minX,maxX,minY,maxY);}static Block image(PositionedImage i){return new Block(null,0,i,BlockKind.TEXT,false,i.x,i.x+i.w,i.y,i.y+i.h);}}

    // ---------------------------------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------------------------------

    private static final class ImageStore {
        final PdfFile file; final File dir; final Map<Integer,String> done=new HashMap<>();
        ImageStore(PdfFile f,File d){file=f;dir=d;}

        PositionedImage store(PdfObject o,Matrix ctm,double pageW,double pageH,ResourceSet resources){
            try{
                int w=intValue(o.dict,"/Width",0),h=intValue(o.dict,"/Height",0),bpc=intValue(o.dict,"/BitsPerComponent",8);
                boolean mask="true".equalsIgnoreCase(rawValue(o.dict,"/ImageMask")); if(mask&&rawValue(o.dict,"/BitsPerComponent").isEmpty())bpc=1;
                if(w<12||h<12||((long)w*h)<400)return null;
                String path=done.get(o.number);
                if(path==null){String cs=mask?"/DeviceGray":resources.resolveColorSpace(rawValue(o.dict,"/ColorSpace"));path=extract(o,w,h,bpc,cs,mask);if(path==null)return null;done.put(o.number,path);}
                File target=new File(dir,path);if(!target.isFile()||target.length()<16)return null;
                Point p=ctm.apply(0,0),px=ctm.apply(1,0),py=ctm.apply(0,1);
                double rw=Math.hypot(px.x-p.x,px.y-p.y),rh=Math.hypot(py.x-p.x,py.y-p.y);double y=Math.max(p.y,Math.max(px.y,py.y));
                return new PositionedImage("images/"+path,p.x,y,rw,rh);
            }catch(Exception e){return null;}
        }

        String extract(PdfObject o,int w,int h,int bpc,String colorSpace,boolean imageMask)throws IOException{
            List<String> f=filterNames(o.dict);String ext;byte[] data;
            if(f.contains("/DCTDecode")||f.contains("/DCT")){
                data=decodeStream(o,file);if(data==null||data.length<4||(data[0]&255)!=0xff||(data[1]&255)!=0xd8)return null;ext="jpg";
            }else if(f.contains("/JPXDecode")||f.contains("/JBIG2Decode")||f.contains("/CCITTFaxDecode")||f.contains("/CCF")){
                return null;
            }else{
                data=decodeStream(o,file);if(data==null)return null;if(!(bpc==1||bpc==2||bpc==4||bpc==8||bpc==16))return null;
                RawImage img=decodeRawImage(data,w,h,bpc,colorSpace,o.dict,file,imageMask);if(img==null)return null;
                applySoftMask(img,o,file);data=png(img);ext="png";
            }
            String name="img-"+o.number+"."+ext;File target=new File(dir,name);
            try(FileOutputStream out=new FileOutputStream(target)){out.write(data);}
            if(!target.isFile()||target.length()<16)return null;return name;
        }
    }

    private static RawImage decodeRawImage(byte[] data,int w,int h,int bpc,String colorSpace,String dict,PdfFile file,boolean imageMask){
        try{
            String cs=colorSpace==null?"":colorSpace;int colors=3;byte[]palette=null;boolean gray=false,indexed=false,cmyk=false;
            if(imageMask||cs.contains("/DeviceGray")||cs.contains("/CalGray")||"/G".equals(cs.trim())){colors=1;gray=true;}
            else if(cs.contains("/DeviceCMYK")){colors=4;cmyk=true;}
            else if(cs.contains("/Indexed")||cs.contains("/I ")){colors=1;indexed=true;palette=paletteBytes(cs,file);}
            else if(cs.contains("/ICCBased")){int n=iccComponents(cs,file);colors=n==1?1:n==4?4:3;gray=colors==1;cmyk=colors==4;}
            else if(cs.contains("/Separation")||cs.contains("/DeviceN")||cs.contains("/Pattern")){return null;}
            else {colors=3;}

            String dp=resolveRawObject(file,rawValue(dict,"/DecodeParms"));int columns=intValue(dp,"/Columns",w);int predictor=intValue(dp,"/Predictor",1);
            int rowBytes=(int)(((long)w*colors*bpc+7)/8);byte[]raw=applyPredictor(data,predictor,colors,bpc,columns,rowBytes,h);if(raw.length<rowBytes*h)return null;
            List<Double> decode=parseNumbers(rawValue(dict,"/Decode"));
            byte[]rgb=new byte[w*h*3];int di=0;
            for(int y=0;y<h;y++){
                long rowBit=(long)y*rowBytes*8L;
                for(int x=0;x<w;x++){
                    if(indexed){int idx=readSample(raw,rowBit+(long)x*bpc,bpc);int off=idx*3;if(palette!=null&&off+2<palette.length){rgb[di++]=palette[off];rgb[di++]=palette[off+1];rgb[di++]=palette[off+2];}else{int g=sampleTo255(idx,bpc);rgb[di++]=(byte)g;rgb[di++]=(byte)g;rgb[di++]=(byte)g;}}
                    else if(gray){int v=readSample(raw,rowBit+(long)x*bpc,bpc);int g=decodeTo255(v,bpc,0,decode);rgb[di++]=(byte)g;rgb[di++]=(byte)g;rgb[di++]=(byte)g;}
                    else if(cmyk){long base=rowBit+(long)x*4*bpc;int c=decodeTo255(readSample(raw,base,bpc),bpc,0,decode),m=decodeTo255(readSample(raw,base+bpc,bpc),bpc,1,decode),yy=decodeTo255(readSample(raw,base+2L*bpc,bpc),bpc,2,decode),k=decodeTo255(readSample(raw,base+3L*bpc,bpc),bpc,3,decode);rgb[di++]=(byte)(255-Math.min(255,c+k));rgb[di++]=(byte)(255-Math.min(255,m+k));rgb[di++]=(byte)(255-Math.min(255,yy+k));}
                    else {long base=rowBit+(long)x*3*bpc;rgb[di++]=(byte)decodeTo255(readSample(raw,base,bpc),bpc,0,decode);rgb[di++]=(byte)decodeTo255(readSample(raw,base+bpc,bpc),bpc,1,decode);rgb[di++]=(byte)decodeTo255(readSample(raw,base+2L*bpc,bpc),bpc,2,decode);}
                }
            }
            if(imageMask&&decode.isEmpty()){for(int i=0;i<rgb.length;i++)rgb[i]=(byte)(255-(rgb[i]&255));}
            return new RawImage(w,h,rgb,null);
        }catch(Exception e){return null;}
    }

    private static void applySoftMask(RawImage img,PdfObject main,PdfFile file){
        try{Ref r=parseRef(rawValue(main.dict,"/SMask"));if(r==null)return;PdfObject sm=file.get(r.number);if(sm==null||sm.stream==null)return;int w=intValue(sm.dict,"/Width",0),h=intValue(sm.dict,"/Height",0),bpc=intValue(sm.dict,"/BitsPerComponent",8);if(w!=img.w||h!=img.h)return;List<String> filters=filterNames(sm.dict);if(filters.contains("/JPXDecode")||filters.contains("/JBIG2Decode")||filters.contains("/CCITTFaxDecode"))return;byte[]d=decodeStream(sm,file);String cs=resolveRawObject(file,rawValue(sm.dict,"/ColorSpace"));RawImage mask=decodeRawImage(d,w,h,bpc,cs,sm.dict,file,false);if(mask==null)return;img.alpha=new byte[w*h];for(int i=0;i<w*h;i++)img.alpha[i]=mask.rgb[i*3];}catch(Exception ignored){}
    }

    private static int readSample(byte[]data,long bitOffset,int bits){if(bits==8){int p=(int)(bitOffset>>3);return p<data.length?data[p]&255:0;}if(bits==16){int p=(int)(bitOffset>>3);return p+1<data.length?((data[p]&255)<<8)|(data[p+1]&255):0;}int p=(int)(bitOffset>>3),shift=8-bits-(int)(bitOffset&7);if(p<0||p>=data.length||shift<0)return 0;return ((data[p]&255)>>shift)&((1<<bits)-1);}
    private static int sampleTo255(int sample,int bits){int max=bits==16?65535:(1<<bits)-1;return max<=0?0:(int)Math.round(sample*255.0/max);}
    private static int decodeTo255(int sample,int bits,int component,List<Double>decode){double min=0,max=1;int p=component*2;if(decode!=null&&decode.size()>p+1){min=decode.get(p);max=decode.get(p+1);}int sm=bits==16?65535:(1<<bits)-1;double norm=sm<=0?0:sample/(double)sm;double v=min+norm*(max-min);return (int)Math.round(Math.max(0,Math.min(1,v))*255);}

    private static byte[] paletteBytes(String cs,PdfFile file){
        try{Matcher hm=Pattern.compile("<([0-9A-Fa-f\\s]+)>").matcher(cs);byte[]p=null;while(hm.find())p=hexBytes(hm.group(1));if(p!=null&&p.length>0)return p;Object root=new ContentTokenizer(cs.getBytes(LATIN1)).next();if(root instanceof PdfArray){List<Object>a=((PdfArray)root).items;for(int i=a.size()-1;i>=0;i--)if(a.get(i) instanceof PdfString){byte[]b=((PdfString)a.get(i)).bytes;if(b.length>0)return b;}}List<Ref>refs=parseRefs(cs);if(!refs.isEmpty()){PdfObject po=file.get(refs.get(refs.size()-1).number);if(po!=null&&po.stream!=null)return decodeStream(po,file);}return null;}catch(Exception e){return null;}
    }
    private static int iccComponents(String cs,PdfFile file){try{List<Ref>refs=parseRefs(cs);if(!refs.isEmpty()){PdfObject p=file.get(refs.get(0).number);if(p!=null)return intValue(p.dict,"/N",3);}}catch(Exception ignored){}return 3;}

    private static byte[] applyPredictor(byte[] data,int predictor,int colors,int bits,int columns,int rowBytes,int rows){if(predictor<=1)return data;if(predictor==2&&bits==8){byte[]o=Arrays.copyOf(data,data.length);int bpp=Math.max(1,(colors*bits+7)/8);for(int r=0;r<rows;r++){int off=r*rowBytes;for(int i=bpp;i<rowBytes&&off+i<o.length;i++)o[off+i]=(byte)((o[off+i]+o[off+i-bpp])&255);}return o;}if(predictor>=10&&predictor<=15){int stride=rowBytes+1;if(data.length<stride*rows)return data;byte[]o=new byte[rowBytes*rows];int bpp=Math.max(1,(colors*bits+7)/8);for(int r=0;r<rows;r++){int type=data[r*stride]&255;int src=r*stride+1,dst=r*rowBytes;for(int x=0;x<rowBytes;x++){int a=x>=bpp?o[dst+x-bpp]&255:0,b=r>0?o[dst-rowBytes+x]&255:0,c=(r>0&&x>=bpp)?o[dst-rowBytes+x-bpp]&255:0,v=data[src+x]&255;switch(type){case 1:v=(v+a)&255;break;case 2:v=(v+b)&255;break;case 3:v=(v+((a+b)>>1))&255;break;case 4:v=(v+paeth(a,b,c))&255;break;}o[dst+x]=(byte)v;}}return o;}return data;}
    private static int paeth(int a,int b,int c){int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;}
    private static byte[] png(RawImage img)throws IOException{boolean alpha=img.alpha!=null&&img.alpha.length>=img.w*img.h;int comps=alpha?4:3;ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});ByteArrayOutputStream ih=new ByteArrayOutputStream();writeInt(ih,img.w);writeInt(ih,img.h);ih.write(8);ih.write(alpha?6:2);ih.write(0);ih.write(0);ih.write(0);pngChunk(out,"IHDR",ih.toByteArray());byte[]scan=new byte[(img.w*comps+1)*img.h];for(int y=0;y<img.h;y++){int d=y*(img.w*comps+1);scan[d]=0;for(int x=0;x<img.w;x++){int si=(y*img.w+x)*3,di=d+1+x*comps;scan[di]=img.rgb[si];scan[di+1]=img.rgb[si+1];scan[di+2]=img.rgb[si+2];if(alpha)scan[di+3]=img.alpha[y*img.w+x];}}Deflater def=new Deflater(6);def.setInput(scan);def.finish();ByteArrayOutputStream z=new ByteArrayOutputStream();byte[]b=new byte[32768];while(!def.finished()){int n=def.deflate(b);z.write(b,0,n);}def.end();pngChunk(out,"IDAT",z.toByteArray());pngChunk(out,"IEND",new byte[0]);return out.toByteArray();}
    private static void pngChunk(ByteArrayOutputStream out,String type,byte[]data)throws IOException{byte[]t=type.getBytes(StandardCharsets.US_ASCII);writeInt(out,data.length);out.write(t);out.write(data);CRC32 crc=new CRC32();crc.update(t);crc.update(data);writeInt(out,(int)crc.getValue());}private static void writeInt(ByteArrayOutputStream o,int v){o.write((v>>>24)&255);o.write((v>>>16)&255);o.write((v>>>8)&255);o.write(v&255);}
    private static final class RawImage{final int w,h;final byte[]rgb;byte[]alpha;RawImage(int w,int h,byte[]r,byte[]a){this.w=w;this.h=h;rgb=r;alpha=a;}}
    private static final class PositionedImage{final String relativePath;final double x,y,w,h;PositionedImage(String p,double x,double y,double w,double h){relativePath=p;this.x=x;this.y=y;this.w=w;this.h=h;}}

    // ---------------------------------------------------------------------------------------------
    // PDF syntax helpers
    // ---------------------------------------------------------------------------------------------

    private static String dictionaryValue(PdfFile file,String dict,String key){String v=rawValue(dict,key);Ref r=parseRef(v);if(r!=null){PdfObject o=file.get(r.number);return o==null?"":o.dict;}return v;}
    private static String resolveRawObject(PdfFile file,String raw){Ref r=parseRef(raw);if(r==null)return raw==null?"":raw;PdfObject o=file.get(r.number);if(o==null)return"";return o.raw.isEmpty()?o.dict:o.raw;}
    private static Map<String,Ref> namedRefs(String dict){Map<String,Ref>m=new LinkedHashMap<>();if(dict==null)return m;Matcher x=Pattern.compile("/([A-Za-z0-9_.+-]+)\\s+(\\d+)\\s+(\\d+)\\s+R").matcher(dict);while(x.find())m.put(x.group(1),new Ref(Integer.parseInt(x.group(2)),Integer.parseInt(x.group(3))));return m;}
    private static String rawValue(String dict,String key){if(dict==null||key==null)return"";int k=tokenIndex(dict,key,0);if(k<0)return"";int p=k+key.length();while(p<dict.length()&&Character.isWhitespace(dict.charAt(p)))p++;if(p>=dict.length())return"";char c=dict.charAt(p);if(c=='<'){if(p+1<dict.length()&&dict.charAt(p+1)=='<'){int e=balancedDictionaryEnd(dict,p);return e>p?dict.substring(p,e):"";}int e=dict.indexOf('>',p+1);return e>=0?dict.substring(p,e+1):"";}if(c=='['){int e=matching(dict,p,'[',']');return e>=0?dict.substring(p,e+1):"";}if(c=='('){int e=matchingParen(dict,p);return e>=0?dict.substring(p,e+1):"";}if(c=='/'){int e=p+1;while(e<dict.length()&&!Character.isWhitespace(dict.charAt(e))&&"/<>[](){}%".indexOf(dict.charAt(e))<0)e++;return dict.substring(p,e);}int e=p;while(e<dict.length()&&!Character.isWhitespace(dict.charAt(e))&&"/<>[](){}%".indexOf(dict.charAt(e))<0)e++;String first=dict.substring(p,e);int q=e;while(q<dict.length()&&Character.isWhitespace(dict.charAt(q)))q++;Matcher ref=Pattern.compile("(\\d+)\\s+(\\d+)\\s+R").matcher(dict.substring(p,Math.min(dict.length(),p+80)));if(ref.lookingAt())return ref.group();return first;}
    private static String nameValue(String dict,String key){String r=rawValue(dict,key);return r.startsWith("/")?firstName(r):"";}private static String firstName(String s){Matcher m=Pattern.compile("/[A-Za-z0-9_.+-]+" ).matcher(s==null?"":s);return m.find()?m.group():"";}
    private static int intValue(String dict,String key,int def){String r=rawValue(dict,key);try{Matcher m=Pattern.compile("[-+]?\\d+").matcher(r);return m.find()?Integer.parseInt(m.group()):def;}catch(Exception e){return def;}}
    private static String stringValue(String dict,String key){String r=rawValue(dict,key);if(r.startsWith("("))return decodePdfLiteralString(r);if(r.startsWith("<")&&!r.startsWith("<<")){String h=r.substring(1,r.indexOf('>')).replaceAll("\\s+","");return decodeUtf16Bytes(hexBytes(h));}return"";}
    private static String destinationName(String raw){if(raw==null)return"";raw=raw.trim();if(raw.startsWith("/"))return decodePdfName(raw.substring(1));if(raw.startsWith("("))return decodePdfLiteralString(raw);if(raw.startsWith("<")&&!raw.startsWith("<<")){int e=raw.indexOf('>');if(e>1)return decodeUtf16Bytes(hexBytes(raw.substring(1,e).replaceAll("\\s+","")));}return"";}
    private static String decodePdfName(String name){if(name==null||name.indexOf('#')<0)return name==null?"":name;StringBuilder out=new StringBuilder();for(int i=0;i<name.length();i++){char c=name.charAt(i);if(c=='#'&&i+2<name.length()){int a=Character.digit(name.charAt(i+1),16),b=Character.digit(name.charAt(i+2),16);if(a>=0&&b>=0){out.append((char)((a<<4)|b));i+=2;continue;}}out.append(c);}return out.toString();}
    private static String decodePdfLiteralString(String r){if(r.length()<2)return"";ContentTokenizer t=new ContentTokenizer(r.getBytes(LATIN1));Object o=t.next();if(o instanceof PdfString){byte[]b=((PdfString)o).bytes;if(b.length>=2&&b[0]==(byte)0xfe&&b[1]==(byte)0xff)return new String(b,2,b.length-2,StandardCharsets.UTF_16BE);return new String(b,Charset.forName("windows-1252"));}return"";}
    private static Ref parseRef(String raw){if(raw==null)return null;Matcher m=REF_PATTERN.matcher(raw.trim());return m.matches()?new Ref(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))):null;}private static List<Ref> parseRefs(String raw){List<Ref>o=new ArrayList<>();if(raw==null)return o;Matcher m=REF_PATTERN.matcher(raw);while(m.find())o.add(new Ref(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))));return o;}
    private static List<Double> parseNumbers(String s){List<Double>o=new ArrayList<>();Matcher m=NUMBER.matcher(s==null?"":s);while(m.find())try{o.add(Double.parseDouble(m.group()));}catch(Exception ignored){}return o;}
    private static int balancedDictionaryEnd(String s,int start){int depth=0;for(int i=start;i+1<s.length();i++){char c=s.charAt(i),n=s.charAt(i+1);if(c=='('){i=matchingParen(s,i);if(i<0)return-1;continue;}if(c=='<'&&n=='<'){depth++;i++;continue;}if(c=='>'&&n=='>'){depth--;i++;if(depth==0)return i+1;}}return-1;}
    private static int matching(String s,int start,char open,char close){int d=0;for(int i=start;i<s.length();i++){char c=s.charAt(i);if(c=='('){i=matchingParen(s,i);if(i<0)return-1;continue;}if(c==open)d++;else if(c==close&&--d==0)return i;}return-1;}
    private static int matchingParen(String s,int start){int d=0;boolean esc=false;for(int i=start;i<s.length();i++){char c=s.charAt(i);if(esc){esc=false;continue;}if(c=='\\'){esc=true;continue;}if(c=='(')d++;else if(c==')'&&--d==0)return i;}return-1;}
    private static int tokenIndex(String s,String token,int from){int p=from;while((p=s.indexOf(token,p))>=0){boolean left=token.startsWith("/")||p==0||isDelimiter(s.charAt(p-1));int e=p+token.length();boolean right=e>=s.length()||isDelimiter(s.charAt(e));if(left&&right)return p;p=e;}return-1;}private static boolean isDelimiter(char c){return Character.isWhitespace(c)||"/<>[](){}%".indexOf(c)>=0;}
    private static int indexOf(byte[]a,byte[]n,int from){return indexOf(a,n,from,a.length);}
    private static int indexOf(byte[]a,byte[]n,int from,int limit){int end=Math.min(a.length,Math.max(from,limit));outer:for(int i=Math.max(0,from);i+n.length<=end;i++){for(int j=0;j<n.length;j++)if(a[i+j]!=n[j])continue outer;return i;}return-1;}
    private static int lastIndexOf(byte[]a,byte[]n){outer:for(int i=a.length-n.length;i>=0;i--){for(int j=0;j<n.length;j++)if(a[i+j]!=n[j])continue outer;return i;}return-1;}
    private static boolean containsAscii(byte[] data,String needle){return indexOf(data,needle.getBytes(LATIN1),0)>=0;}
    private static boolean isPdfWhitespace(int c){return c==0||c==9||c==10||c==12||c==13||c==32;}
    private static boolean isPdfWhitespaceOrDelimiter(int c){return isPdfWhitespace(c)||c=='('||c==')'||c=='<'||c=='>'||c=='['||c==']'||c=='{'||c=='}'||c=='/'||c=='%';}
    private static byte[]hexBytes(String h){h=h.replaceAll("\\s+","");if((h.length()&1)==1)h+="0";byte[]b=new byte[h.length()/2];for(int i=0;i<b.length;i++)try{b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16);}catch(Exception e){b[i]=0;}return b;}private static int bytesInt(byte[]b){int v=0;for(byte x:b)v=(v<<8)|(x&255);return v;}private static byte[]intBytes(int v,int len){byte[]b=new byte[len];for(int i=len-1;i>=0;i--){b[i]=(byte)v;v>>>=8;}return b;}
    private static String decodeUnicodeHex(String h){return decodeUtf16Bytes(hexBytes(h));}private static String decodeUtf16Bytes(byte[]b){if(b.length==0)return"";if((b.length&1)==0){try{return new String(b,StandardCharsets.UTF_16BE);}catch(Exception ignored){}}return new String(b,LATIN1);}
    private static Matrix matrix(double a,double b,double c,double d,double e,double f){return new Matrix(a,b,c,d,e,f);}

    private static final class Matrix{final double a,b,c,d,e,f;Matrix(){this(1,0,0,1,0,0);}Matrix(double a,double b,double c,double d,double e,double f){this.a=a;this.b=b;this.c=c;this.d=d;this.e=e;this.f=f;}Matrix multiply(Matrix o){return new Matrix(a*o.a+c*o.b,b*o.a+d*o.b,a*o.c+c*o.d,b*o.c+d*o.d,a*o.e+c*o.f+e,b*o.e+d*o.f+f);}Point apply(double x,double y){return new Point(a*x+c*y+e,b*x+d*y+f);}}
    private static final class Point{final double x,y;Point(double x,double y){this.x=x;this.y=y;}}
    private static final class GraphicsState{Matrix ctm=new Matrix();boolean inText,pendingSpace;String font="";double fontSize=11,tx,ty,lineX,lineY,leading=0,charSpace=0,wordSpace=0,hScale=1;GraphicsState(){}GraphicsState(Matrix m){ctm=m;}GraphicsState copy(){GraphicsState g=new GraphicsState();g.ctm=ctm;g.inText=inText;g.pendingSpace=pendingSpace;g.font=font;g.fontSize=fontSize;g.tx=tx;g.ty=ty;g.lineX=lineX;g.lineY=lineY;g.leading=leading;g.charSpace=charSpace;g.wordSpace=wordSpace;g.hScale=hScale;return g;}}
    private static final class Ref{final int number,generation;Ref(int n,int g){number=n;generation=g;}}
    private static final class Match{final int a,b,end;Match(int a,int b,int e){this.a=a;this.b=b;end=e;}}
    private static final class ParseBoundary{final int endObjStart,afterEndObj;ParseBoundary(int a,int b){endObjStart=a;afterEndObj=b;}}
    private static final class Metadata{final String title,author;Metadata(String t,String a){title=t;author=a;}}
    private static final class Name{final String value;Name(String v){value=v;}}
    private static final class Operator{final String value;Operator(String v){value=v;}}
    private static final class PdfString{final byte[]bytes;PdfString(byte[]b){bytes=b;}}
    private static final class PdfArray{final List<Object>items;PdfArray(List<Object>i){items=i;}}
    private static final class ByteKey{final byte[]b;ByteKey(byte[]x,int o,int n){b=Arrays.copyOfRange(x,o,o+n);}public boolean equals(Object o){return o instanceof ByteKey&&Arrays.equals(b,((ByteKey)o).b);}public int hashCode(){return Arrays.hashCode(b);}}
    private static final class BitInput{final byte[]d;int bit;BitInput(byte[]d){this.d=d;}int read(int n){if(bit+n>d.length*8)return-1;int v=0;for(int i=0;i<n;i++){v=(v<<1)|((d[bit>>3]>>(7-(bit&7)))&1);bit++;}return v;}}
}

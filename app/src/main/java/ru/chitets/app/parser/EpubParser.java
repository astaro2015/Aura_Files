package ru.chitets.app.parser;

import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;

public final class EpubParser {
    private static final long MAX_UNPACKED = 160L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 6000;

    private EpubParser() {}

    public static ReaderDocument parse(File epubFile, File outputDir, String fallbackTitle) throws Exception {
        File container = new File(outputDir, "META-INF/container.xml");
        if (!container.isFile()) {
            recreateDirectory(outputDir);
            unpack(epubFile, outputDir);
            container = new File(outputDir, "META-INF/container.xml");
        }
        if (!container.isFile()) throw new IOException("В EPUB отсутствует META-INF/container.xml");
        Document containerXml = parseXml(container);
        Element rootfile = firstByLocalName(containerXml, "rootfile");
        if (rootfile == null) throw new IOException("В EPUB не найдено описание книги");
        String opfPath = rootfile.getAttribute("full-path");
        File opfFile = safeChild(outputDir, opfPath);
        if (!opfFile.isFile()) throw new IOException("В EPUB не найден файл содержимого");

        Document opf = parseXml(opfFile);
        String title = text(firstByLocalName(opf, "title"));
        if (title.isEmpty()) title = fallbackTitle;
        String author = text(firstByLocalName(opf, "creator"));
        String series = findSeries(opf);

        Map<String, Item> manifest = new HashMap<>();
        NodeList itemNodes = opf.getElementsByTagNameNS("*", "item");
        if (itemNodes.getLength() == 0) itemNodes = opf.getElementsByTagName("item");
        File opfDir = opfFile.getParentFile();
        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element item = (Element) itemNodes.item(i);
            String id = item.getAttribute("id");
            String href = Uri.decode(item.getAttribute("href"));
            if (!id.isEmpty() && !href.isEmpty()) {
                File file = safeResolved(outputDir, opfDir, stripFragment(href));
                manifest.put(id, new Item(id, href, item.getAttribute("media-type"), item.getAttribute("properties"), file));
            }
        }

        List<Item> spine = new ArrayList<>();
        NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
        if (refs.getLength() == 0) refs = opf.getElementsByTagName("itemref");
        for (int i = 0; i < refs.getLength(); i++) {
            Item item = manifest.get(((Element) refs.item(i)).getAttribute("idref"));
            if (item != null && item.file.isFile()) spine.add(item);
        }
        if (spine.isEmpty()) {
            for (Item item : manifest.values()) {
                if (isHtml(item) && item.file.isFile()) spine.add(item);
            }
        }
        if (spine.isEmpty()) throw new IOException("В EPUB не найден текст книги");

        Map<String, String> chapterIds = new HashMap<>();
        for (int i = 0; i < spine.size(); i++) {
            chapterIds.put(spine.get(i).file.getCanonicalPath(), "epub_chapter_" + i);
        }

        String coverUrl = findCoverUrl(opf, manifest);
        String epubCss = collectCss(manifest, outputDir);
        List<TocEntry> toc = new ArrayList<>();
        StringBuilder body = new StringBuilder(16384);
        for (int i = 0; i < spine.size(); i++) {
            Item chapter = spine.get(i);
            String raw;
            try (FileInputStream input = new FileInputStream(chapter.file)) {
                raw = HtmlUtil.decodeText(HtmlUtil.readAll(input));
            }
            String content = HtmlUtil.stripDangerousHtml(HtmlUtil.bodyOf(raw));
            content = rewriteLinks(content, outputDir, chapter.file.getParentFile(), chapterIds);
            String anchor = chapterIds.get(chapter.file.getCanonicalPath());
            String chapterTitle = extractChapterTitle(raw);
            if (!chapterTitle.isEmpty()) toc.add(new TocEntry(chapterTitle, anchor, 0));
            body.append("<section class=\"chapter page-break\" id=\"")
                    .append(anchor).append("\">")
                    .append(content).append("</section>");
        }
        String baseUrl = Uri.fromFile(outputDir).toString() + "/";
        return new ReaderDocument(title, author, series, HtmlUtil.wrap(title, author, body.toString(), epubCss), baseUrl, coverUrl, toc);
    }

    private static String findSeries(Document opf) {
        NodeList metas = opf.getElementsByTagNameNS("*", "meta");
        if (metas.getLength() == 0) metas = opf.getElementsByTagName("meta");
        String series = "";
        String index = "";
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            String property = meta.getAttribute("property");
            String name = meta.getAttribute("name");
            String value = text(meta);
            if (value.isEmpty()) value = meta.getAttribute("content");
            if ("belongs-to-collection".equalsIgnoreCase(property) || "calibre:series".equalsIgnoreCase(name)) {
                if (!value.trim().isEmpty()) series = value.trim();
            } else if ("group-position".equalsIgnoreCase(property) || "calibre:series_index".equalsIgnoreCase(name)) {
                if (!value.trim().isEmpty()) index = value.trim();
            }
        }
        if (series.isEmpty()) return "";
        return index.isEmpty() ? series : series + " #" + index;
    }

    private static String collectCss(Map<String, Item> manifest, File allowedRoot) {
        StringBuilder css = new StringBuilder();
        int total = 0;
        for (Item item : manifest.values()) {
            String type = item.mediaType == null ? "" : item.mediaType.toLowerCase();
            if (!type.contains("css") && !item.href.toLowerCase().endsWith(".css")) continue;
            if (!item.file.isFile()) continue;
            try (FileInputStream input = new FileInputStream(item.file)) {
                String raw = HtmlUtil.decodeText(HtmlUtil.readAll(input));
                String safe = sanitizeCss(raw);
                safe = rewriteCssUrls(safe, allowedRoot, item.file.getParentFile());
                total += safe.length();
                if (total > 512 * 1024) break;
                css.append("\n").append(safe);
            } catch (Exception ignored) {
            }
        }
        return css.toString();
    }

    private static String sanitizeCss(String css) {
        if (css == null) return "";
        String safe = css.replaceAll("(?is)@import\\s+[^;]+;", "");
        safe = safe.replaceAll("(?is)expression\\s*\\([^)]*\\)", "");
        safe = safe.replaceAll("(?i)javascript\\s*:", "");
        return safe;
    }

    private static String rewriteCssUrls(String css, File allowedRoot, File cssDir) {
        Pattern pattern = Pattern.compile("(?i)url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)");
        Matcher matcher = pattern.matcher(css);
        StringBuffer out = new StringBuffer(css.length() + 256);
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            String lower = value.toLowerCase();
            String rewritten = value;
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("javascript:")) {
                rewritten = "";
            } else if (!value.isEmpty() && !lower.startsWith("data:") && !lower.startsWith("file:")) {
                try {
                    File target = safeResolved(allowedRoot, cssDir, Uri.decode(stripFragment(value)));
                    rewritten = Uri.fromFile(target).toString();
                } catch (Exception ignored) {
                    rewritten = "";
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement("url('" + rewritten.replace("'", "%27") + "')"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String findCoverUrl(Document opf, Map<String, Item> manifest) {
        for (Item item : manifest.values()) {
            if (item.properties != null && item.properties.toLowerCase().contains("cover-image") && item.file.isFile()) {
                return Uri.fromFile(item.file).toString();
            }
        }
        NodeList metas = opf.getElementsByTagNameNS("*", "meta");
        if (metas.getLength() == 0) metas = opf.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equalsIgnoreCase(meta.getAttribute("name"))) {
                Item item = manifest.get(meta.getAttribute("content"));
                if (item != null && item.file.isFile()) return Uri.fromFile(item.file).toString();
            }
        }
        for (Item item : manifest.values()) {
            String id = item.id.toLowerCase();
            String type = item.mediaType == null ? "" : item.mediaType.toLowerCase();
            if (id.contains("cover") && type.startsWith("image/") && item.file.isFile()) {
                return Uri.fromFile(item.file).toString();
            }
        }
        return "";
    }

    private static String extractChapterTitle(String raw) {
        Pattern heading = Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]\\s*>");
        Matcher matcher = heading.matcher(raw);
        if (matcher.find()) {
            String value = matcher.group(1).replaceAll("(?is)<[^>]+>", " ");
            value = value.replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
            return value.replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    private static boolean isHtml(Item item) {
        String type = item.mediaType == null ? "" : item.mediaType.toLowerCase();
        String path = item.href.toLowerCase();
        return type.contains("html") || path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm");
    }

    private static String rewriteLinks(String html, File allowedRoot, File chapterDir, Map<String, String> chapterIds) {
        Pattern pattern = Pattern.compile("(?i)(src|href)\\s*=\\s*([\"'])(.*?)\\2");
        Matcher matcher = pattern.matcher(html);
        StringBuffer out = new StringBuffer(html.length() + 256);
        while (matcher.find()) {
            String attribute = matcher.group(1).toLowerCase();
            String quote = matcher.group(2);
            String value = matcher.group(3).trim();
            String rewritten = rewriteOne(attribute, value, allowedRoot, chapterDir, chapterIds);
            String replacement = attribute + "=" + quote + HtmlUtil.escape(rewritten) + quote;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String rewriteOne(String attribute, String value, File allowedRoot, File chapterDir, Map<String, String> chapterIds) {
        String lower = value.toLowerCase();
        if (value.isEmpty() || value.startsWith("#") || lower.startsWith("data:") || lower.startsWith("http://")
                || lower.startsWith("https://") || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return value;
        }
        try {
            String decoded = Uri.decode(value);
            int hash = decoded.indexOf('#');
            String pathPart = hash >= 0 ? decoded.substring(0, hash) : decoded;
            String fragment = hash >= 0 ? decoded.substring(hash + 1) : "";
            File target = safeResolved(allowedRoot, chapterDir, pathPart);
            if ("href".equals(attribute) && chapterIds.containsKey(target.getCanonicalPath())) {
                return fragment.isEmpty() ? "#" + chapterIds.get(target.getCanonicalPath()) : "#" + fragment;
            }
            return Uri.fromFile(target).toString() + (fragment.isEmpty() ? "" : "#" + fragment);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static void unpack(File source, File root) throws IOException {
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Слишком много файлов внутри EPUB");
                File output = safeChild(root, entry.getName());
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) throw new IOException("Не удалось распаковать EPUB");
                } else {
                    File parent = output.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IOException("Не удалось распаковать EPUB");
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            total += read;
                            if (total > MAX_UNPACKED) throw new IOException("EPUB слишком большой после распаковки");
                            stream.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        String[] disabled = {
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        };
        for (String feature : disabled) {
            try { factory.setFeature(feature, false); } catch (Exception ignored) {}
        }
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { factory.setXIncludeAware(false); } catch (Exception ignored) {}
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Element firstByLocalName(Node root, String name) {
        if (root == null) return null;
        if (root instanceof Element) {
            String local = root.getLocalName();
            if (local == null) local = root.getNodeName();
            if (name.equals(local) || local.endsWith(":" + name)) return (Element) root;
        }
        Node child = root.getFirstChild();
        while (child != null) {
            Element result = firstByLocalName(child, name);
            if (result != null) return result;
            child = child.getNextSibling();
        }
        return null;
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static File safeChild(File root, String relative) throws IOException {
        File file = new File(root, relative);
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Недопустимый путь внутри книги");
        }
        return file;
    }

    private static File safeResolved(File allowedRoot, File base, String relative) throws IOException {
        File file = new File(base, relative);
        String rootPath = allowedRoot.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Недопустимая ссылка внутри книги");
        }
        return file;
    }

    private static String stripFragment(String path) {
        int hash = path.indexOf('#');
        return hash >= 0 ? path.substring(0, hash) : path;
    }

    private static void recreateDirectory(File dir) throws IOException {
        if (dir.exists()) deleteRecursive(dir);
        if (!dir.mkdirs()) throw new IOException("Не удалось создать каталог книги");
    }

    private static void deleteRecursive(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        if (!file.delete()) throw new IOException("Не удалось очистить старый кэш книги");
    }

    private static final class Item {
        final String id;
        final String href;
        final String mediaType;
        final String properties;
        final File file;

        Item(String id, String href, String mediaType, String properties, File file) {
            this.id = id;
            this.href = href;
            this.mediaType = mediaType;
            this.properties = properties;
            this.file = file;
        }
    }
}

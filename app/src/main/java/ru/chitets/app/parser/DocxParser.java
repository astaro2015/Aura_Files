package ru.chitets.app.parser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;

public final class DocxParser {
    private static final int MAX_ENTRY = 32 * 1024 * 1024;
    private DocxParser() {}

    public static ReaderDocument parse(InputStream input, String fallbackTitle) throws Exception {
        Map<String, byte[]> files = readZip(input);
        byte[] documentXml = files.get("word/document.xml");
        if (documentXml == null) throw new IOException("В DOCX нет word/document.xml");
        Meta meta = readMeta(files.get("docProps/core.xml"), fallbackTitle);
        Document doc = parseXml(documentXml);
        NodeList paragraphs = doc.getElementsByTagNameNS("*", "p");
        StringBuilder body = new StringBuilder(documentXml.length + 2048);
        List<TocEntry> toc = new ArrayList<>();
        int anchor = 0;
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element p = (Element) paragraphs.item(i);
            String style = paragraphStyle(p);
            int heading = headingLevel(style);
            String text = paragraphText(p);
            if (text.trim().isEmpty()) continue;
            if (heading > 0) {
                String id = "docx-" + (++anchor);
                body.append("<h").append(heading).append(" id=\"").append(id).append("\">")
                        .append(HtmlUtil.escape(text.trim())).append("</h").append(heading).append('>');
                toc.add(new TocEntry(text.trim(), id, heading - 1));
                if ((meta.title == null || meta.title.equals(fallbackTitle)) && heading == 1) meta.title = text.trim();
            } else {
                body.append("<p>").append(HtmlUtil.escape(text).replace("\t", "&emsp;").replace("\n", "<br>"))
                        .append("</p>");
            }
        }
        String title = meta.title == null || meta.title.trim().isEmpty() ? fallbackTitle : meta.title.trim();
        return new ReaderDocument(title, meta.author, "", HtmlUtil.wrap(title, meta.author, body.toString()), "about:blank", "", toc);
    }

    private static Map<String, byte[]> readZip(InputStream input) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry e; int count = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (++count > 5000) throw new IOException("Слишком много файлов внутри DOCX");
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || name.startsWith("/") || name.contains("../")) continue;
                if (!name.equals("word/document.xml") && !name.equals("docProps/core.xml")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int total = 0, r;
                while ((r = zip.read(buf)) != -1) {
                    total += r; if (total > MAX_ENTRY) throw new IOException("Слишком большой XML внутри DOCX");
                    out.write(buf, 0, r);
                }
                files.put(name, out.toByteArray());
            }
        }
        return files;
    }

    private static Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(data)));
    }

    private static Meta readMeta(byte[] xml, String fallback) {
        Meta m = new Meta(); m.title = fallback;
        if (xml == null) return m;
        try {
            Document doc = parseXml(xml);
            m.title = firstText(doc, "title", fallback);
            m.author = firstText(doc, "creator", "");
        } catch (Exception ignored) {}
        return m;
    }

    private static String firstText(Document doc, String local, String fallback) {
        NodeList list = doc.getElementsByTagNameNS("*", local);
        if (list.getLength() == 0) return fallback;
        String s = list.item(0).getTextContent();
        return s == null || s.trim().isEmpty() ? fallback : s.trim();
    }

    private static String paragraphStyle(Element p) {
        NodeList styles = p.getElementsByTagNameNS("*", "pStyle");
        if (styles.getLength() == 0) return "";
        Element e = (Element) styles.item(0);
        String value = e.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val");
        if (value.isEmpty()) value = e.getAttribute("w:val");
        return value;
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:heading|заголовок)[ _-]?(\\d)").matcher(style);
        if (m.find()) return Math.max(1, Math.min(6, Integer.parseInt(m.group(1))));
        return 0;
    }

    private static String paragraphText(Element p) {
        StringBuilder out = new StringBuilder();
        appendNodeText(p, out);
        return out.toString();
    }

    private static void appendNodeText(Node node, StringBuilder out) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            String local = node.getLocalName();
            if ("t".equals(local)) { out.append(node.getTextContent()); return; }
            if ("tab".equals(local)) { out.append('\t'); return; }
            if ("br".equals(local) || "cr".equals(local)) { out.append('\n'); return; }
            if ("noBreakHyphen".equals(local)) { out.append('\u2011'); return; }
        }
        Node child = node.getFirstChild();
        while (child != null) { appendNodeText(child, out); child = child.getNextSibling(); }
    }

    private static final class Meta { String title; String author = ""; }
}

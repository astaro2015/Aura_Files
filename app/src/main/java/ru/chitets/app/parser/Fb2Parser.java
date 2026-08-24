package ru.chitets.app.parser;

import android.net.Uri;
import android.util.Base64;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;

public final class Fb2Parser {
    private Fb2Parser() {}

    public static ReaderDocument parse(InputStream input, File cacheDir, String fallbackTitle) throws Exception {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw new IOException("Не удалось создать кэш книги");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        secure(factory);
        Document document = factory.newDocumentBuilder().parse(input);

        Element titleInfo = firstDescendant(document.getDocumentElement(), "title-info");
        String title = text(firstDescendant(titleInfo, "book-title"));
        if (title.isEmpty()) title = fallbackTitle;
        String author = readAuthor(titleInfo);
        String series = readSeries(titleInfo);

        Map<String, String> images = extractImages(document, cacheDir);
        String coverId = coverImageId(titleInfo);
        String coverUrl = images.containsKey(coverId) ? images.get(coverId) : "";
        StringBuilder body = new StringBuilder(8192);
        if (!coverUrl.isEmpty()) {
            body.append("<img class=\"cover\" src=\"")
                    .append(HtmlUtil.escape(coverUrl)).append("\" alt=\"Обложка\">");
        }

        Element mainBody = null;
        StringBuilder notes = new StringBuilder();
        NodeList bodies = document.getElementsByTagNameNS("*", "body");
        for (int i = 0; i < bodies.getLength(); i++) {
            Element candidate = (Element) bodies.item(i);
            String name = candidate.getAttribute("name");
            if (name == null || name.isEmpty()) {
                if (mainBody == null) mainBody = candidate;
            } else if ("notes".equalsIgnoreCase(name) || "comments".equalsIgnoreCase(name)) {
                renderChildren(candidate, notes, images);
            } else if (mainBody == null) {
                mainBody = candidate;
            }
        }
        if (mainBody == null && bodies.getLength() > 0) mainBody = (Element) bodies.item(0);

        List<TocEntry> toc = new ArrayList<>();
        if (mainBody != null) {
            ensureSectionIdsAndCollectToc(mainBody, toc, new int[]{0}, 0);
            renderChildren(mainBody, body, images);
        }
        if (notes.length() > 0) {
            body.append("<section class=\"notes\"><h2>Примечания</h2>").append(notes).append("</section>");
        }

        String html = HtmlUtil.wrap(title, author, body.toString());
        return new ReaderDocument(title, author, series, html, Uri.fromFile(cacheDir).toString() + "/", coverUrl, toc);
    }

    private static void ensureSectionIdsAndCollectToc(Element root, List<TocEntry> toc, int[] counter, int level) {
        Node child = root.getFirstChild();
        while (child != null) {
            if (child instanceof Element) {
                Element element = (Element) child;
                if ("section".equals(local(element))) {
                    String id = element.getAttribute("id");
                    if (id == null || id.trim().isEmpty()) {
                        id = "fb2_chapter_" + counter[0]++;
                        element.setAttribute("id", id);
                    }
                    Element titleElement = directChild(element, "title");
                    String heading = text(titleElement);
                    if (!heading.isEmpty()) toc.add(new TocEntry(heading, id, level));
                    ensureSectionIdsAndCollectToc(element, toc, counter, level + 1);
                } else {
                    ensureSectionIdsAndCollectToc(element, toc, counter, level);
                }
            }
            child = child.getNextSibling();
        }
    }

    private static Element directChild(Element root, String name) {
        if (root == null) return null;
        Node child = root.getFirstChild();
        while (child != null) {
            if (child instanceof Element && name.equals(local((Element) child))) return (Element) child;
            child = child.getNextSibling();
        }
        return null;
    }

    private static void secure(DocumentBuilderFactory factory) {
        String[] features = {
                "http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        };
        for (String feature : features) {
            try {
                boolean value = feature.endsWith("disallow-doctype-decl");
                factory.setFeature(feature, value);
            } catch (Exception ignored) {
            }
        }
        try { factory.setXIncludeAware(false); } catch (Exception ignored) {}
        factory.setExpandEntityReferences(false);
    }

    private static Map<String, String> extractImages(Document document, File cacheDir) {
        Map<String, String> images = new HashMap<>();
        NodeList binaries = document.getElementsByTagNameNS("*", "binary");
        for (int i = 0; i < binaries.getLength(); i++) {
            Element binary = (Element) binaries.item(i);
            String id = binary.getAttribute("id");
            if (id == null || id.isEmpty()) continue;
            String contentType = binary.getAttribute("content-type");
            String extension = imageExtension(contentType);
            String safeName = id.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!safeName.contains(".")) safeName += extension;
            File output = new File(cacheDir, safeName);
            try {
                byte[] data = Base64.decode(binary.getTextContent().replaceAll("\\s+", ""), Base64.DEFAULT);
                if (data.length > 12 * 1024 * 1024) continue;
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    stream.write(data);
                }
                images.put(id, Uri.fromFile(output).toString());
            } catch (Exception ignored) {
            }
        }
        return images;
    }

    private static String imageExtension(String contentType) {
        if (contentType == null) return ".img";
        if (contentType.contains("png")) return ".png";
        if (contentType.contains("gif")) return ".gif";
        if (contentType.contains("webp")) return ".webp";
        return ".jpg";
    }

    private static String readSeries(Element titleInfo) {
        Element sequence = firstDescendant(titleInfo, "sequence");
        if (sequence == null) return "";
        String name = sequence.getAttribute("name");
        String number = sequence.getAttribute("number");
        if (name == null) name = "";
        if (number == null) number = "";
        name = name.trim();
        number = number.trim();
        if (name.isEmpty()) return "";
        return number.isEmpty() ? name : name + " #" + number;
    }

    private static String readAuthor(Element titleInfo) {
        Element author = firstDescendant(titleInfo, "author");
        if (author == null) return "";
        String first = text(firstDescendant(author, "first-name"));
        String middle = text(firstDescendant(author, "middle-name"));
        String last = text(firstDescendant(author, "last-name"));
        String result = (first + " " + middle + " " + last).replaceAll("\\s+", " ").trim();
        if (result.isEmpty()) result = text(firstDescendant(author, "nickname"));
        return result;
    }

    private static String coverImageId(Element titleInfo) {
        Element cover = firstDescendant(titleInfo, "coverpage");
        Element image = firstDescendant(cover, "image");
        String href = attrAny(image, "href");
        return href.startsWith("#") ? href.substring(1) : href;
    }

    private static void renderChildren(Node node, StringBuilder out, Map<String, String> images) {
        if (node == null) return;
        Node child = node.getFirstChild();
        while (child != null) {
            render(child, out, images);
            child = child.getNextSibling();
        }
    }

    private static void render(Node node, StringBuilder out, Map<String, String> images) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            out.append(HtmlUtil.escape(node.getNodeValue()));
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) return;
        Element element = (Element) node;
        String tag = local(element);
        String id = element.getAttribute("id");
        String idAttr = id == null || id.isEmpty() ? "" : " id=\"" + HtmlUtil.escape(id) + "\"";
        switch (tag) {
            case "section":
                out.append("<section class=\"chapter\"").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</section>");
                break;
            case "title":
                out.append("<h2").append(idAttr).append(">").append(HtmlUtil.escape(text(element))).append("</h2>");
                break;
            case "subtitle":
                out.append("<h3").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</h3>");
                break;
            case "p":
                out.append("<p").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</p>");
                break;
            case "emphasis":
                out.append("<em>"); renderChildren(element, out, images); out.append("</em>");
                break;
            case "strong":
                out.append("<strong>"); renderChildren(element, out, images); out.append("</strong>");
                break;
            case "strikethrough":
                out.append("<s>"); renderChildren(element, out, images); out.append("</s>");
                break;
            case "a":
                String href = attrAny(element, "href");
                out.append("<a href=\"").append(HtmlUtil.escape(href)).append("\">");
                renderChildren(element, out, images);
                out.append("</a>");
                break;
            case "image":
                String imageId = attrAny(element, "href");
                if (imageId.startsWith("#")) imageId = imageId.substring(1);
                if (images.containsKey(imageId)) {
                    out.append("<img src=\"").append(HtmlUtil.escape(images.get(imageId))).append("\" alt=\"Иллюстрация\">");
                }
                break;
            case "epigraph":
            case "cite":
            case "annotation":
                out.append("<blockquote").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</blockquote>");
                break;
            case "poem":
            case "stanza":
                out.append("<div").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</div>");
                break;
            case "v":
                out.append("<div class=\"verse\"").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</div>");
                break;
            case "empty-line":
                out.append("<br>");
                break;
            case "date":
            case "text-author":
                out.append("<div").append(idAttr).append(">");
                renderChildren(element, out, images);
                out.append("</div>");
                break;
            default:
                renderChildren(element, out, images);
        }
    }

    private static Element firstDescendant(Node root, String name) {
        if (root == null) return null;
        if (root instanceof Element && name.equals(local((Element) root))) return (Element) root;
        Node child = root.getFirstChild();
        while (child != null) {
            Element found = firstDescendant(child, name);
            if (found != null) return found;
            child = child.getNextSibling();
        }
        return null;
    }

    private static String local(Element element) {
        String name = element.getLocalName();
        if (name == null) name = element.getTagName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String attrAny(Element element, String localName) {
        if (element == null) return "";
        if (element.hasAttribute(localName)) return element.getAttribute(localName);
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attr = element.getAttributes().item(i);
            String name = attr.getLocalName();
            if (name == null) name = attr.getNodeName();
            if (localName.equals(name) || name.endsWith(":" + localName)) return attr.getNodeValue();
        }
        return "";
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().replaceAll("\\s+", " ").trim();
    }
}

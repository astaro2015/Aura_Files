package ru.chitets.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReaderDocument {
    public final String title;
    public final String author;
    public final String series;
    public final String html;
    public final String baseUrl;
    public final String coverUrl;
    public final List<TocEntry> toc;

    public ReaderDocument(String title, String author, String html, String baseUrl) {
        this(title, author, "", html, baseUrl, "", Collections.emptyList());
    }

    public ReaderDocument(String title, String author, String html, String baseUrl,
                          String coverUrl, List<TocEntry> toc) {
        this(title, author, "", html, baseUrl, coverUrl, toc);
    }

    public ReaderDocument(String title, String author, String series, String html, String baseUrl,
                          String coverUrl, List<TocEntry> toc) {
        this.title = title == null || title.trim().isEmpty() ? "Книга" : title.trim();
        this.author = author == null ? "" : author.trim();
        this.series = series == null ? "" : series.trim();
        this.html = html == null ? "" : html;
        this.baseUrl = baseUrl == null ? "about:blank" : baseUrl;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.toc = Collections.unmodifiableList(new ArrayList<>(toc == null ? Collections.emptyList() : toc));
    }
}

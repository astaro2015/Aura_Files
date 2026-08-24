package ru.chitets.app.model;

public final class TocEntry {
    public final String title;
    public final String anchor;
    public final int level;

    public TocEntry(String title, String anchor, int level) {
        this.title = title == null || title.trim().isEmpty() ? "Раздел" : title.trim();
        this.anchor = anchor == null ? "" : anchor.trim();
        this.level = Math.max(0, level);
    }
}

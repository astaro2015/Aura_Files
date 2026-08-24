package ru.chitets.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public final class Book {
    public final String uri;
    public String title;
    public String author;
    public String series;
    public String collection;
    public final String fileName;
    public final String format;
    public final long addedAt;
    public String coverPath;
    public long lastOpenedAt;

    public Book(String uri, String title, String author, String fileName, String format, long addedAt) {
        this(uri, title, author, "", "", fileName, format, addedAt, "", 0L);
    }

    public Book(String uri, String title, String author, String series, String collection,
                String fileName, String format, long addedAt, String coverPath, long lastOpenedAt) {
        this.uri = uri;
        this.title = title == null || title.trim().isEmpty() ? fileName : title.trim();
        this.author = author == null ? "" : author.trim();
        this.series = series == null ? "" : series.trim();
        this.collection = collection == null ? "" : collection.trim();
        this.fileName = fileName == null ? "Книга" : fileName;
        this.format = format == null ? "Файл" : format.toUpperCase();
        this.addedAt = addedAt;
        this.coverPath = coverPath == null ? "" : coverPath;
        this.lastOpenedAt = Math.max(0L, lastOpenedAt);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("uri", uri);
        json.put("title", title);
        json.put("author", author);
        json.put("series", series);
        json.put("collection", collection);
        json.put("fileName", fileName);
        json.put("format", format);
        json.put("addedAt", addedAt);
        json.put("coverPath", coverPath);
        json.put("lastOpenedAt", lastOpenedAt);
        return json;
    }

    public static Book fromJson(JSONObject json) throws JSONException {
        return new Book(
                json.getString("uri"),
                json.optString("title"),
                json.optString("author"),
                json.optString("series", ""),
                json.optString("collection", ""),
                json.optString("fileName", "Книга"),
                json.optString("format", "Файл"),
                json.optLong("addedAt", System.currentTimeMillis()),
                json.optString("coverPath", ""),
                json.optLong("lastOpenedAt", 0L)
        );
    }
}

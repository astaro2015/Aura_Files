package ru.chitets.app.store;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.chitets.app.model.Book;

public final class LibraryStore {
    private static final String PREFS = "library_v1";
    private static final String KEY_BOOKS = "books";
    private final Context context;

    public LibraryStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized List<Book> getBooks() {
        List<Book> books = readBooks();
        Collections.sort(books, Comparator.comparingLong((Book b) -> b.addedAt).reversed());
        return books;
    }

    public synchronized Book findByUri(String uri) {
        if (uri == null) return null;
        for (Book book : readBooks()) if (book.uri.equals(uri)) return book;
        return null;
    }

    private List<Book> readBooks() {
        List<Book> books = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BOOKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                try {
                    books.add(Book.fromJson(array.getJSONObject(i)));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return books;
    }

    public synchronized Book add(Uri uri) {
        String uriText = uri.toString();
        List<Book> books = readBooks();
        for (Book existing : books) {
            if (existing.uri.equals(uriText)) return existing;
        }
        Book book = createBook(uri);
        books.add(book);
        save(books);
        return book;
    }

    /** Creates book metadata for a URI without adding it to the library. */
    public Book inspect(Uri uri) {
        return uri == null ? null : createBook(uri);
    }

    /**
     * Rebinds an existing library entry to a newly selected document URI while
     * preserving user metadata and timestamps. Returns null if another library
     * entry already uses the selected URI.
     */
    public synchronized Book relink(String oldUri, Uri newUri) {
        if (oldUri == null || newUri == null) return null;
        String newText = newUri.toString();
        List<Book> books = readBooks();
        int oldIndex = -1;
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            if (book.uri.equals(oldUri)) oldIndex = i;
            if (!book.uri.equals(oldUri) && book.uri.equals(newText)) return null;
        }
        if (oldIndex < 0) return null;
        Book old = books.get(oldIndex);
        Book fresh = createBook(newUri);
        Book replacement = new Book(
                newText, old.title, old.author, old.series, old.collection,
                fresh.fileName, fresh.format, old.addedAt, old.coverPath, old.lastOpenedAt);
        books.set(oldIndex, replacement);
        save(books);
        return replacement;
    }

    public synchronized int addMany(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return 0;
        List<Book> books = readBooks();
        Set<String> known = new HashSet<>();
        for (Book book : books) known.add(book.uri);
        int added = 0;
        for (Uri uri : uris) {
            if (uri == null || known.contains(uri.toString())) continue;
            books.add(createBook(uri));
            known.add(uri.toString());
            added++;
        }
        if (added > 0) save(books);
        return added;
    }

    private Book createBook(Uri uri) {
        String fileName = resolveName(uri);
        String format = inferFormat(fileName, context.getContentResolver().getType(uri));
        String title = stripExtension(fileName);
        return new Book(uri.toString(), title, "", fileName, format, System.currentTimeMillis());
    }

    public synchronized void updateMetadata(String uri, String title, String author, String series, String coverPath) {
        List<Book> books = readBooks();
        boolean changed = false;
        for (Book book : books) {
            if (book.uri.equals(uri)) {
                if (title != null && !title.trim().isEmpty()) book.title = title.trim();
                if (author != null && !author.trim().isEmpty()) book.author = author.trim();
                if (series != null && !series.trim().isEmpty()) book.series = series.trim();
                if (coverPath != null && !coverPath.trim().isEmpty()) book.coverPath = coverPath.trim();
                changed = true;
                break;
            }
        }
        if (changed) save(books);
    }

    public synchronized void updateMetadata(String uri, String title, String author, String coverPath) {
        updateMetadata(uri, title, author, "", coverPath);
    }

    public synchronized void updateMetadata(String uri, String title, String author) {
        updateMetadata(uri, title, author, "", "");
    }

    public synchronized void setCollection(String uri, String collection) {
        List<Book> books = readBooks();
        boolean changed = false;
        for (Book book : books) {
            if (book.uri.equals(uri)) {
                book.collection = collection == null ? "" : collection.trim();
                changed = true;
                break;
            }
        }
        if (changed) save(books);
    }

    public synchronized List<String> getCollections() {
        Set<String> names = new HashSet<>();
        for (Book book : readBooks()) {
            if (book.collection != null && !book.collection.trim().isEmpty()) names.add(book.collection.trim());
        }
        List<String> result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public synchronized void markOpened(String uri) {
        List<Book> books = readBooks();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (Book book : books) {
            if (book.uri.equals(uri)) {
                book.lastOpenedAt = now;
                changed = true;
                break;
            }
        }
        if (changed) save(books);
    }

    public synchronized void remove(String uri) {
        List<Book> books = readBooks();
        books.removeIf(book -> book.uri.equals(uri));
        save(books);
    }

    private void save(List<Book> books) {
        JSONArray array = new JSONArray();
        for (Book book : books) {
            try {
                array.put(book.toJson());
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BOOKS, array.toString()).apply();
    }

    private String resolveName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "Книга" : last;
    }

    public static boolean isSupported(String fileName, String mime) {
        String format = inferFormat(fileName, mime);
        return "EPUB".equals(format) || "FB2".equals(format) || "FB2.ZIP".equals(format)
                || "PDF".equals(format) || "HTML".equals(format) || "TXT".equals(format)
                || "CBZ".equals(format) || "CBR".equals(format) || "ZIP".equals(format)
                || "MD".equals(format) || "RTF".equals(format) || "DOCX".equals(format)
                || "MOBI".equals(format) || "AZW".equals(format) || "AZW3".equals(format)
                || "PRC".equals(format) || "DJVU".equals(format);
    }

    public static String inferFormat(String fileName, String mime) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        String type = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "FB2.ZIP";
        if (lower.endsWith(".epub") || "application/epub+zip".equals(type)) return "EPUB";
        if (lower.endsWith(".fb2") || "application/x-fictionbook+xml".equals(type)) return "FB2";
        if (lower.endsWith(".pdf") || "application/pdf".equals(type)) return "PDF";
        if (lower.endsWith(".djvu") || lower.endsWith(".djv") || "image/vnd.djvu".equals(type) || "image/x-djvu".equals(type)) return "DJVU";
        if (lower.endsWith(".cbz") || "application/vnd.comicbook+zip".equals(type) || "application/x-cbz".equals(type)) return "CBZ";
        if (lower.endsWith(".cbr") || "application/vnd.comicbook-rar".equals(type)
                || "application/x-cbr".equals(type) || "application/x-rar-compressed".equals(type)
                || "application/vnd.rar".equals(type)) return "CBR";
        if (lower.endsWith(".docx") || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(type)) return "DOCX";
        if (lower.endsWith(".rtf") || "application/rtf".equals(type) || "text/rtf".equals(type)) return "RTF";
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || "text/markdown".equals(type)) return "MD";
        if (lower.endsWith(".azw3")) return "AZW3";
        if (lower.endsWith(".azw") || "application/vnd.amazon.ebook".equals(type)) return "AZW";
        if (lower.endsWith(".mobi") || "application/x-mobipocket-ebook".equals(type)) return "MOBI";
        if (lower.endsWith(".prc")) return "PRC";
        if (lower.endsWith(".html") || lower.endsWith(".htm") || "text/html".equals(type)) return "HTML";
        if (lower.endsWith(".txt") || "text/plain".equals(type)) return "TXT";
        if (lower.endsWith(".zip") || "application/zip".equals(type)) return "ZIP";
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "ФАЙЛ";
    }

    private static String stripExtension(String name) {
        if (name == null) return "Книга";
        String result = name.replaceFirst("(?i)\\.fb2\\.zip$", "");
        result = result.replaceFirst("(?i)\\.(epub|fb2|pdf|txt|html?|zip|md|markdown|rtf|docx|mobi|azw3?|prc|djvu|djv)$", "");
        return result.replace('_', ' ').trim();
    }
}

package ru.chitets.app.store;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReadingPrefs {
    private static final String NAME = "reading_state_v1";

    private ReadingPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static String bookKey(String uri) {
        return Integer.toHexString(uri == null ? 0 : uri.hashCode());
    }

    /** Moves all URI-keyed reading/UI state when Android gives the same book a new document URI. */
    public static void migrateBookState(Context context, String oldUri, String newUri) {
        if (context == null || oldUri == null || newUri == null || oldUri.equals(newUri)) return;
        String oldKey = bookKey(oldUri);
        String newKey = bookKey(newUri);
        if (oldKey.equals(newKey)) return;

        migrateKeys(prefs(context), oldKey, newKey, new String[]{
                "progress_", "anchor_", "anchor_offset_", "pdf_", "comic_", "djvu_",
                "djvu_night_", "djvu_fit_", "bookmarks_", "notes_"
        });
        migrateKeys(context.getSharedPreferences("pdf_ui_v2", Context.MODE_PRIVATE), oldKey, newKey,
                new String[]{"night_", "spread_", "crop_"});
        migrateKeys(context.getSharedPreferences("comic_ui_v1", Context.MODE_PRIVATE), oldKey, newKey,
                new String[]{"rtl_", "night_"});
    }

    private static void migrateKeys(SharedPreferences target, String oldKey, String newKey, String[] prefixes) {
        Map<String, ?> all = target.getAll();
        SharedPreferences.Editor editor = target.edit();
        boolean changed = false;
        for (String prefix : prefixes) {
            String from = prefix + oldKey;
            if (!all.containsKey(from)) continue;
            String to = prefix + newKey;
            putValue(editor, to, all.get(from));
            editor.remove(from);
            changed = true;
        }
        if (changed) editor.apply();
    }

    @SuppressWarnings("unchecked")
    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Set) editor.putStringSet(key, new java.util.HashSet<>((Set<String>) value));
    }

    public static float getProgress(Context context, String uri) {
        return prefs(context).getFloat("progress_" + bookKey(uri), 0f);
    }

    public static void setProgress(Context context, String uri, float value) {
        setPosition(context, uri, value, "", 0f);
    }

    public static Position getPosition(Context context, String uri) {
        SharedPreferences p = prefs(context);
        Position result = new Position();
        result.progress = p.getFloat("progress_" + bookKey(uri), 0f);
        result.anchor = p.getString("anchor_" + bookKey(uri), "");
        result.anchorOffset = p.getFloat("anchor_offset_" + bookKey(uri), 0f);
        return result;
    }

    public static void setPosition(Context context, String uri, float progress, String anchor, float anchorOffset) {
        float safe = Math.max(0f, Math.min(1f, progress));
        float safeOffset = Math.max(0f, Math.min(1f, anchorOffset));
        prefs(context).edit()
                .putFloat("progress_" + bookKey(uri), safe)
                .putString("anchor_" + bookKey(uri), anchor == null ? "" : anchor)
                .putFloat("anchor_offset_" + bookKey(uri), safeOffset)
                .apply();
    }

    public static int getPdfPage(Context context, String uri) {
        return prefs(context).getInt("pdf_" + bookKey(uri), 0);
    }

    public static void setPdfPage(Context context, String uri, int page, int count) {
        int safePage = Math.max(0, page);
        float progress = count <= 1 ? 0f : (float) safePage / (float) (count - 1);
        prefs(context).edit()
                .putInt("pdf_" + bookKey(uri), safePage)
                .putFloat("progress_" + bookKey(uri), progress)
                .apply();
    }

    public static int getComicPage(Context context, String uri) {
        return prefs(context).getInt("comic_" + bookKey(uri), 0);
    }

    public static void setComicPage(Context context, String uri, int page, int count) {
        int safePage = Math.max(0, page);
        float progress = count <= 1 ? 0f : (float) safePage / (float) (count - 1);
        prefs(context).edit()
                .putInt("comic_" + bookKey(uri), safePage)
                .putFloat("progress_" + bookKey(uri), progress)
                .apply();
    }

    public static int getDjvuPage(Context context, String uri) {
        return prefs(context).getInt("djvu_" + bookKey(uri), 0);
    }

    public static void setDjvuPage(Context context, String uri, int page, int count) {
        int safePage = Math.max(0, page);
        float progress = count <= 1 ? 0f : (float) safePage / (float) (count - 1);
        prefs(context).edit()
                .putInt("djvu_" + bookKey(uri), safePage)
                .putFloat("progress_" + bookKey(uri), progress)
                .apply();
    }

    public static boolean getDjvuNight(Context context, String uri) {
        return prefs(context).getBoolean("djvu_night_" + bookKey(uri), false);
    }

    public static void setDjvuNight(Context context, String uri, boolean enabled) {
        prefs(context).edit().putBoolean("djvu_night_" + bookKey(uri), enabled).apply();
    }

    public static String getDjvuFitMode(Context context, String uri) {
        String mode = prefs(context).getString("djvu_fit_" + bookKey(uri), "width");
        return "page".equals(mode) ? "page" : "width";
    }

    public static void setDjvuFitMode(Context context, String uri, String mode) {
        prefs(context).edit().putString("djvu_fit_" + bookKey(uri), "page".equals(mode) ? "page" : "width").apply();
    }

    public static List<Float> getBookmarks(Context context, String uri) {
        List<Float> result = new ArrayList<>();
        String raw = prefs(context).getString("bookmarks_" + bookKey(uri), "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                double value = array.optDouble(i, -1d);
                if (value >= 0d && value <= 1d) result.add((float) value);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(result);
        return result;
    }

    public static void addBookmark(Context context, String uri, float progress) {
        float safe = Math.max(0f, Math.min(1f, progress));
        List<Float> values = getBookmarks(context, uri);
        for (Float value : values) {
            if (Math.abs(value - safe) < 0.004f) return;
        }
        values.add(safe);
        Collections.sort(values);
        saveBookmarks(context, uri, values);
    }

    public static void removeBookmark(Context context, String uri, float progress) {
        List<Float> values = getBookmarks(context, uri);
        Float nearest = null;
        float distance = Float.MAX_VALUE;
        for (Float value : values) {
            float d = Math.abs(value - progress);
            if (d < distance) {
                distance = d;
                nearest = value;
            }
        }
        if (nearest != null) {
            values.remove(nearest);
            saveBookmarks(context, uri, values);
        }
    }

    private static void saveBookmarks(Context context, String uri, List<Float> values) {
        JSONArray array = new JSONArray();
        for (Float value : values) array.put(value);
        prefs(context).edit().putString("bookmarks_" + bookKey(uri), array.toString()).apply();
    }

    public static List<Note> getNotes(Context context, String uri) {
        List<Note> notes = new ArrayList<>();
        String raw = prefs(context).getString("notes_" + bookKey(uri), "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                Note note = new Note();
                note.id = json.optLong("id", System.currentTimeMillis() + i);
                note.progress = (float) json.optDouble("progress", 0d);
                note.quote = json.optString("quote", "");
                note.text = json.optString("text", "");
                note.createdAt = json.optLong("createdAt", note.id);
                notes.add(note);
            }
        } catch (Exception ignored) {
        }
        notes.sort(Comparator.comparingDouble(n -> n.progress));
        return notes;
    }

    public static Note addNote(Context context, String uri, float progress, String quote, String text) {
        Note note = new Note();
        note.id = System.currentTimeMillis();
        note.progress = Math.max(0f, Math.min(1f, progress));
        note.quote = quote == null ? "" : quote.trim();
        note.text = text == null ? "" : text.trim();
        note.createdAt = note.id;
        List<Note> notes = getNotes(context, uri);
        notes.add(note);
        saveNotes(context, uri, notes);
        return note;
    }

    public static void removeNote(Context context, String uri, long id) {
        List<Note> notes = getNotes(context, uri);
        notes.removeIf(note -> note.id == id);
        saveNotes(context, uri, notes);
    }

    private static void saveNotes(Context context, String uri, List<Note> notes) {
        JSONArray array = new JSONArray();
        for (Note note : notes) {
            try {
                JSONObject json = new JSONObject();
                json.put("id", note.id);
                json.put("progress", note.progress);
                json.put("quote", note.quote);
                json.put("text", note.text);
                json.put("createdAt", note.createdAt);
                array.put(json);
            } catch (Exception ignored) {
            }
        }
        prefs(context).edit().putString("notes_" + bookKey(uri), array.toString()).apply();
    }

    public static ReaderSettings getSettings(Context context) {
        SharedPreferences p = prefs(context);
        ReaderSettings s = new ReaderSettings();
        s.fontSize = p.getInt("font_size", 20);
        s.lineHeight = p.getInt("line_height", 160);
        s.margin = p.getInt("margin", 18);
        s.theme = p.getString("theme", "sepia");
        s.font = p.getString("font", "book");
        s.justify = p.getBoolean("justify", false);
        s.paged = p.getBoolean("paged", false);
        s.paragraphIndent = p.getInt("paragraph_indent", 18);
        s.paragraphSpacing = p.getInt("paragraph_spacing", 8);
        s.brightness = p.getInt("brightness", 0);
        s.readingWpm = p.getInt("reading_wpm", 220);
        s.edgeGestures = p.getBoolean("edge_gestures", true);
        s.paperParallax = p.getInt("paper_parallax", 1);
        return s;
    }

    public static void saveSettings(Context context, ReaderSettings s) {
        prefs(context).edit()
                .putInt("font_size", s.fontSize)
                .putInt("line_height", s.lineHeight)
                .putInt("margin", s.margin)
                .putString("theme", s.theme)
                .putString("font", s.font)
                .putBoolean("justify", s.justify)
                .putBoolean("paged", s.paged)
                .putInt("paragraph_indent", s.paragraphIndent)
                .putInt("paragraph_spacing", s.paragraphSpacing)
                .putInt("brightness", s.brightness)
                .putInt("reading_wpm", Math.max(100, Math.min(500, s.readingWpm)))
                .putBoolean("edge_gestures", s.edgeGestures)
                .putInt("paper_parallax", Math.max(0, Math.min(2, s.paperParallax)))
                .apply();
    }

    public static final class Position {
        public float progress;
        public String anchor = "";
        public float anchorOffset;
    }

    public static final class Note {
        public long id;
        public float progress;
        public String quote = "";
        public String text = "";
        public long createdAt;
    }

    public static final class ReaderSettings {
        public int fontSize;
        public int lineHeight;
        public int margin;
        public String theme;
        public String font;
        public boolean justify;
        public boolean paged;
        public int paragraphIndent;
        public int paragraphSpacing;
        public int brightness;
        public int readingWpm;
        public boolean edgeGestures;
        public int paperParallax;
    }
}

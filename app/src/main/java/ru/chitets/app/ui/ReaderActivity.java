package ru.chitets.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.chitets.app.model.ReaderDocument;
import ru.chitets.app.model.TocEntry;
import ru.chitets.app.parser.BookLoader;
import ru.chitets.app.store.LibraryStore;
import ru.chitets.app.store.ReadingPrefs;

public final class ReaderActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private TextView titleView;
    private TextView progressView;
    private ProgressBar loading;
    private SeekBar progressSeek;
    private LinearLayout toolbar;
    private LinearLayout footer;
    private TextView notesButton;
    private TextView navigationBackButton;
    private String uriText;
    private boolean loaded;
    private boolean chromeVisible = true;
    private boolean seekFromUser;
    private boolean pageTurning;
    private boolean pageSwipeHandled;
    private boolean selectionMode;
    private ReadingPrefs.ReaderSettings settings;
    private ReaderDocument document;
    private float touchDownX;
    private float touchDownY;
    private long touchDownAt;
    private int estimatedWords;
    private float[] tocProgress = new float[0];
    private int pageNumber;
    private int pageCount;
    private String paperModernDataUri;
    private String paperOldDataUri;
    private boolean pdfReflow;
    private boolean djvuReflow;
    private boolean pdfHybridEnabled = true;
    private int requestedPdfPage = -1;
    private int requestedDjvuPage = -1;
    private final Deque<ReadingPrefs.Position> navigationHistory = new ArrayDeque<>();
    private static final int MAX_NAVIGATION_HISTORY = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uriText = getIntent().getStringExtra(BookReaderContract.EXTRA_URI);
        pdfReflow = "PDF".equalsIgnoreCase(getIntent().getStringExtra(BookReaderContract.EXTRA_FORMAT));
        djvuReflow = "DJVU".equalsIgnoreCase(getIntent().getStringExtra(BookReaderContract.EXTRA_FORMAT));
        pdfHybridEnabled = getSharedPreferences("pdf_reflow_ui", MODE_PRIVATE).getBoolean("hybrid_enabled", true);
        requestedPdfPage = getIntent().getIntExtra(BookReaderContract.EXTRA_PDF_PAGE, -1);
        requestedDjvuPage = getIntent().getIntExtra(BookReaderContract.EXTRA_DJVU_PAGE, -1);
        if (uriText == null) {
            finish();
            return;
        }
        settings = ReadingPrefs.getSettings(this);
        applyBrightness(settings.brightness);
        View content = buildContent();
        setContentView(content);
        Ui.fitSystemBars(this, content);
        loadBook();
    }

    private View buildContent() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.PAPER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        toolbar.setBackgroundColor(0xfff1e7d8);
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        TextView back = Ui.action(this, "‹", false);
        back.setTextSize(30);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 46)));

        titleView = Ui.text(this, getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE), 17, Ui.INK);
        titleView.setTypeface(Typeface.SERIF, Typeface.BOLD);
        titleView.setMaxLines(1);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        navigationBackButton = Ui.action(this, "↩", false);
        navigationBackButton.setTextSize(19);
        navigationBackButton.setContentDescription("Назад по внутренним переходам. Удерживать — к исходному месту чтения");
        navigationBackButton.setVisibility(View.GONE);
        navigationBackButton.setOnClickListener(v -> navigateBack());
        navigationBackButton.setOnLongClickListener(v -> { navigateBackToOrigin(); return true; });
        toolbar.addView(navigationBackButton, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 46)));

        if (pdfReflow) {
            TextView originalPdf = Ui.action(this, "PDF", false);
            originalPdf.setTextSize(10.5f);
            originalPdf.setContentDescription("Оригинальная страница PDF");
            originalPdf.setOnClickListener(v -> openOriginalPdf());
            toolbar.addView(originalPdf, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        } else if (djvuReflow) {
            TextView originalDjvu = Ui.action(this, "DjVu", false);
            originalDjvu.setTextSize(9.5f);
            originalDjvu.setContentDescription("Оригинальная страница DjVu");
            originalDjvu.setOnClickListener(v -> openOriginalDjvu());
            toolbar.addView(originalDjvu, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 46)));
        }

        TextView toc = Ui.action(this, "☰", false);
        toc.setTextSize(21);
        toc.setContentDescription("Оглавление");
        toc.setOnClickListener(v -> showToc());
        toolbar.addView(toc, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 46)));

        TextView bookmark = Ui.action(this, "☆", false);
        bookmark.setTextSize(23);
        bookmark.setContentDescription("Закладки");
        bookmark.setOnClickListener(v -> showBookmarks());
        toolbar.addView(bookmark, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 46)));

        notesButton = Ui.action(this, "✎", false);
        notesButton.setTextSize(21);
        notesButton.setContentDescription("Цитаты и заметки");
        notesButton.setOnClickListener(v -> {
            if (selectionMode) finishSelectionForNote();
            else showNotes();
        });
        toolbar.addView(notesButton, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 46)));

        TextView search = Ui.action(this, "⌕", false);
        search.setTextSize(24);
        search.setContentDescription("Поиск по книге");
        search.setOnClickListener(v -> showSearch());
        toolbar.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 46)));

        TextView tune = Ui.action(this, "Аа", false);
        tune.setContentDescription("Настройки чтения");
        tune.setOnClickListener(v -> showSettings());
        toolbar.addView(tune, new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 46)));

        webView = new WebView(this);
        configureWebView();
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        footer.setBackgroundColor(0xfff1e7d8);
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        TextView prev = Ui.action(this, "‹", false);
        prev.setOnClickListener(v -> turnPage(false));
        footer.addView(prev, new LinearLayout.LayoutParams(Ui.dp(this, 42), ViewGroup.LayoutParams.MATCH_PARENT));

        progressSeek = new SeekBar(this);
        progressSeek.setMax(1000);
        progressSeek.setProgress(0);
        progressSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateProgressText(progress / 1000f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { seekFromUser = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                seekFromUser = false;
                jumpToProgress(seekBar.getProgress() / 1000f);
            }
        });
        footer.addView(progressSeek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        progressView = Ui.text(this, "0%", 9.5f, Ui.MUTED);
        progressView.setGravity(Gravity.CENTER);
        progressView.setSingleLine(true);
        footer.addView(progressView, new LinearLayout.LayoutParams(Ui.dp(this, 176), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView next = Ui.action(this, "›", false);
        next.setOnClickListener(v -> turnPage(true));
        footer.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 42), ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(this);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER);
        frame.addView(loading, loadingParams);
        return frame;
    }


    private void openOriginalPdf() {
        if (!loaded) { launchOriginalPdf(-1); return; }
        String js = "(function(){var n=document.elementFromPoint(innerWidth/2,Math.min(innerHeight*.30,140));"
                + "while(n&&!(n.classList&&n.classList.contains('pdf-reflow-page')))n=n.parentElement;"
                + "if(!n){var all=document.querySelectorAll('.pdf-reflow-page'),best=null,dist=1e9;"
                + "for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect(),d=Math.abs(r.top);if(r.bottom>0&&d<dist){dist=d;best=all[i];}}n=best;}"
                + "if(!n||!n.id)return -1;var m=n.id.match(/^pdf-page-(\\d+)$/);return m?parseInt(m[1],10)-1:-1;})()";
        webView.evaluateJavascript(js, value -> {
            int page = -1;
            try { page = Integer.parseInt(value == null ? "-1" : value.replace("\"", "").trim()); } catch (Exception ignored) {}
            launchOriginalPdf(page);
        });
    }

    private void launchOriginalPdf(int page) {
        saveProgress();
        Intent intent = new Intent(this, PdfActivity.class);
        intent.putExtra(BookReaderContract.EXTRA_URI, uriText);
        intent.putExtra(BookReaderContract.EXTRA_TITLE, getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE));
        intent.putExtra(BookReaderContract.EXTRA_FORMAT, "PDF");
        if (page >= 0) intent.putExtra(BookReaderContract.EXTRA_PDF_PAGE, page);
        startActivity(intent);
        finish();
    }

    private void openOriginalDjvu() {
        if (!loaded) { launchOriginalDjvu(-1); return; }
        String js = "(function(){var n=document.elementFromPoint(innerWidth/2,Math.min(innerHeight*.30,140));"
                + "while(n&&!(n.classList&&n.classList.contains('djvu-reflow-page')))n=n.parentElement;"
                + "if(!n){var all=document.querySelectorAll('.djvu-reflow-page'),best=null,dist=1e9;"
                + "for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect(),d=Math.abs(r.top);if(r.bottom>0&&d<dist){dist=d;best=all[i];}}n=best;}"
                + "if(!n||!n.id)return -1;var m=n.id.match(/^djvu-page-(\\d+)$/);return m?parseInt(m[1],10)-1:-1;})()";
        webView.evaluateJavascript(js, value -> {
            int page = -1;
            try { page = Integer.parseInt(value == null ? "-1" : value.replace("\"", "").trim()); } catch (Exception ignored) {}
            launchOriginalDjvu(page);
        });
    }

    private void launchOriginalDjvu(int page) {
        saveProgress();
        Intent intent = new Intent(this, DjvuActivity.class);
        intent.putExtra(BookReaderContract.EXTRA_URI, uriText);
        intent.putExtra(BookReaderContract.EXTRA_TITLE, getIntent().getStringExtra(BookReaderContract.EXTRA_TITLE));
        intent.putExtra(BookReaderContract.EXTRA_FORMAT, "DJVU");
        if (page >= 0) intent.putExtra(BookReaderContract.EXTRA_DJVU_PAGE, page);
        startActivity(intent);
        finish();
    }

    private void configureWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(false);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setBlockNetworkImage(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setSupportZoom(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setTextZoom(100);
        webView.setBackgroundColor(Ui.PAPER);
        webView.setLongClickable(false);
        webView.addJavascriptInterface(new ReaderBridge(), "ChitetsBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                loading.setVisibility(View.GONE);
                applySettingsAndRestore();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                String fragment = uri.getFragment();
                if (fragment != null && !fragment.isEmpty()
                        && (scheme == null || "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme))) {
                    jumpToAnchor(Uri.decode(fragment), true);
                    return true;
                }
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                        || "mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException ignored) {
                    }
                    return true;
                }
                return false;
            }
        });
        webView.setOnTouchListener((v, event) -> handleReaderTouch(event));
    }

    private boolean handleReaderTouch(MotionEvent event) {
        if (!loaded) return false;
        if (selectionMode) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            touchDownAt = System.currentTimeMillis();
            pageSwipeHandled = false;
            return false;
        }

        float rawDx = event.getX() - touchDownX;
        float rawDy = event.getY() - touchDownY;
        float dx = Math.abs(rawDx);
        float dy = Math.abs(rawDy);
        long elapsed = System.currentTimeMillis() - touchDownAt;

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && settings.paged
                && !pageSwipeHandled && dx > Ui.dp(this, 36) && dx > dy * 1.20f) {
            pageSwipeHandled = true;
            turnPage(rawDx < 0f);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            boolean handled = pageSwipeHandled;
            pageSwipeHandled = false;
            return handled;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return pageSwipeHandled;
        if (pageSwipeHandled) {
            pageSwipeHandled = false;
            return true;
        }

        if (settings.edgeGestures && dy > Ui.dp(this, 52) && dy > dx * 1.25f && elapsed < 1200) {
            float startFraction = touchDownX / Math.max(1f, webView.getWidth());
            if (startFraction < 0.16f) {
                int base = settings.brightness <= 0 ? 50 : settings.brightness;
                int next = base + Math.round((-rawDy / Math.max(1f, webView.getHeight())) * 85f);
                settings.brightness = Math.max(5, Math.min(100, next));
                ReadingPrefs.saveSettings(this, settings);
                applyBrightness(settings.brightness);
                Toast.makeText(this, "Яркость: " + settings.brightness + "%", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (startFraction > 0.84f) {
                int next = settings.fontSize + Math.round((-rawDy / Math.max(1f, webView.getHeight())) * 16f);
                next = Math.max(14, Math.min(34, next));
                if (next != settings.fontSize) {
                    final int newSize = next;
                    readCurrentPosition(position -> {
                        ReadingPrefs.setPosition(this, uriText, position.progress, position.anchor, position.anchorOffset);
                        settings.fontSize = newSize;
                        ReadingPrefs.saveSettings(this, settings);
                        applySettingsAndRestore();
                        Toast.makeText(this, "Шрифт: " + settings.fontSize, Toast.LENGTH_SHORT).show();
                    });
                }
                return true;
            }
        }

        if (settings.paged && dx > Ui.dp(this, 36) && dx > dy * 1.20f && elapsed < 1200) {
            turnPage(rawDx < 0f);
            return true;
        }

        if (dx > Ui.dp(this, 18) || dy > Ui.dp(this, 18) || elapsed > 450) return false;
        WebView.HitTestResult hit = webView.getHitTestResult();
        int hitType = hit == null ? WebView.HitTestResult.UNKNOWN_TYPE : hit.getType();
        if (hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE || hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            return false;
        }
        float fraction = event.getX() / Math.max(1f, webView.getWidth());
        if (fraction < 0.22f) turnPage(false);
        else if (fraction > 0.78f) turnPage(true);
        else toggleChrome();
        return true;
    }

    private void loadBook() {
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                ReaderDocument loadedDocument = BookLoader.load(this, Uri.parse(uriText));
                LibraryStore library = new LibraryStore(this);
                library.updateMetadata(uriText, loadedDocument.title, loadedDocument.author, loadedDocument.series, loadedDocument.coverUrl);
                library.markOpened(uriText);
                handler.post(() -> {
                    document = loadedDocument;
                    estimatedWords = estimateWords(loadedDocument.html);
                    titleView.setText(loadedDocument.title);
                    webView.loadDataWithBaseURL(loadedDocument.baseUrl, loadedDocument.html, "text/html", "UTF-8", null);
                });
            } catch (Exception error) {
                handler.post(() -> showLoadError(error));
            }
        });
    }

    private void showLoadError(Exception error) {
        loading.setVisibility(View.GONE);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String errorTitle = pdfReflow ? "Не удалось разобрать PDF"
                : (djvuReflow ? "Не удалось извлечь текст DjVu" : "Не удалось открыть книгу");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(errorTitle)
                .setMessage(message)
                .setCancelable(false);
        if (pdfReflow) {
            dialog.setNegativeButton("Назад", (d, which) -> finish())
                    .setPositiveButton("Оригинал", (d, which) -> openOriginalPdf());
        } else if (djvuReflow) {
            dialog.setNegativeButton("Назад", (d, which) -> finish())
                    .setPositiveButton("Оригинал", (d, which) -> openOriginalDjvu());
        } else {
            dialog.setPositiveButton("Назад", (d, which) -> finish());
        }
        dialog.show();
    }

    private void applySettingsAndRestore() {
        ReadingPrefs.Position position = ReadingPrefs.getPosition(this, uriText);
        boolean restoreRequestedPdfPage = pdfReflow && requestedPdfPage >= 0;
        boolean restoreRequestedDjvuPage = djvuReflow && requestedDjvuPage >= 0;
        String preferredAnchor = restoreRequestedPdfPage ? "pdf-page-" + (requestedPdfPage + 1)
                : (restoreRequestedDjvuPage ? "djvu-page-" + (requestedDjvuPage + 1)
                : (position.anchor == null ? "" : position.anchor));

        String backgroundColor;
        String textColor;
        boolean paperTheme;
        int paperResource = 0;
        switch (settings.theme) {
            case "dark":
                backgroundColor = "#171512";
                textColor = "#e7dfd3";
                paperTheme = false;
                break;
            case "light":
                backgroundColor = "#ffffff";
                textColor = "#25221e";
                paperTheme = false;
                break;
            case "paper_modern":
                backgroundColor = "#f7f4ec";
                textColor = "#28251f";
                paperTheme = true;
                paperResource = com.aurafiles.app.R.drawable.paper_modern;
                break;
            case "paper_old":
                backgroundColor = "#e8d9b5";
                textColor = "#302619";
                paperTheme = true;
                paperResource = com.aurafiles.app.R.drawable.paper_old;
                break;
            default:
                backgroundColor = "#f7f1e5";
                textColor = "#302a22";
                paperTheme = false;
                break;
        }

        String align = settings.justify ? "justify" : "left";
        String fontCss;
        if ("book".equals(settings.font)) fontCss = "";
        else if ("sans".equals(settings.font)) fontCss = "font-family:system-ui,sans-serif;";
        else if ("mono".equals(settings.font)) fontCss = "font-family:ui-monospace,monospace;";
        else fontCss = "font-family:Georgia,'Times New Roman',serif;";

        String paperCss = "";
        if (paperTheme) {
            String paper = getPaperDataUri(paperResource);
            if (!paper.isEmpty()) {
                paperCss = "background-image:url('" + paper + "')!important;"
                        + "background-repeat:repeat!important;"
                        + "background-size:320px 320px!important;";
            }
        }

        int gap = Math.max(12, settings.margin * 2);
        int horizontalPadding = settings.margin * 2;
        String pagedCss = settings.paged
                ? "html{height:100%;overflow-x:auto;overflow-y:hidden;scroll-behavior:auto;overscroll-behavior:none;background-color:" + backgroundColor + "!important;}"
                    + "body{box-sizing:border-box;width:100vw;height:100%;max-width:none;margin:0;padding:" + settings.margin + "px;"
                    + "column-width:calc(100vw - " + horizontalPadding + "px);column-gap:" + gap + "px;column-fill:auto;overflow:visible;touch-action:pan-y;}"
                : "html{height:auto;overflow:auto;background-color:" + backgroundColor + "!important;}"
                    + "body{box-sizing:border-box;height:auto;overflow:visible;touch-action:auto;}";

        String css = "body{background-color:" + backgroundColor + "!important;color:" + textColor + "!important;"
                + paperCss
                + fontCss + "font-size:" + settings.fontSize + "px;line-height:" + settings.lineHeight + "%;"
                + "padding:" + settings.margin + "px;text-align:" + align + ";}"
                + "p{text-indent:" + settings.paragraphIndent + "px;margin-top:" + settings.paragraphSpacing
                + "px;margin-bottom:" + settings.paragraphSpacing + "px;}"
                + ".book-title,.book-author,blockquote p,.verse{text-indent:0;}"
                + "a[href]{cursor:pointer}.chitets-footnote-target{border-radius:6px;background-color:rgba(156,79,46,.18)!important;transition:background-color .25s;}"
                + "body,body *{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important;}"
                + "html.chitets-selection-mode body,html.chitets-selection-mode body *{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important;touch-action:auto!important;}"
                + pagedCss;

        String fallback = settings.paged
                ? "e.scrollLeft=p*Math.max(0,e.scrollWidth-e.clientWidth);"
                : "e.scrollTop=p*Math.max(0,e.scrollHeight-e.clientHeight);";
        String restore = "var a=" + quoteJs(preferredAnchor)
                + ";var off=" + String.format(Locale.US, "%.7f", position.anchorOffset) + ";"
                + "var n=a?document.getElementById(a):null;"
                + "if(n){var r=n.getBoundingClientRect();"
                + (settings.paged
                ? "var base=e.scrollLeft+r.left;e.scrollLeft=Math.max(0,base+off*Math.max(1,r.width));"
                : "var base=e.scrollTop+r.top;e.scrollTop=Math.max(0,base+off*Math.max(1,r.height));")
                + "}else{" + fallback + "}";

        String pageTouchJs = "if(window.__chitetsTouchStart){document.removeEventListener('touchstart',window.__chitetsTouchStart);"
                + "document.removeEventListener('touchmove',window.__chitetsTouchMove);}"
                + "window.__chitetsTouchStart=null;window.__chitetsTouchMove=null;";
        if (settings.paged) {
            pageTouchJs += "window.__chitetsTouchX=0;window.__chitetsTouchY=0;"
                    + "window.__chitetsTouchStart=function(ev){if(document.documentElement.classList.contains('chitets-selection-mode'))return;"
                    + "var t=ev.touches&&ev.touches[0];if(t){window.__chitetsTouchX=t.clientX;window.__chitetsTouchY=t.clientY;}};"
                    + "window.__chitetsTouchMove=function(ev){if(document.documentElement.classList.contains('chitets-selection-mode'))return;"
                    + "var t=ev.touches&&ev.touches[0];if(!t)return;var dx=t.clientX-window.__chitetsTouchX,dy=t.clientY-window.__chitetsTouchY;"
                    + "if(Math.abs(dx)>10&&Math.abs(dx)>Math.abs(dy)*1.15)ev.preventDefault();};"
                    + "document.addEventListener('touchstart',window.__chitetsTouchStart,{passive:true});"
                    + "document.addEventListener('touchmove',window.__chitetsTouchMove,{passive:false});";
        }

        String navigationJs = "if(window.__chitetsClickHandler)document.removeEventListener('click',window.__chitetsClickHandler,true);"
                + "window.__chitetsClickHandler=function(ev){if(document.documentElement.classList.contains('chitets-selection-mode'))return;"
                + "var el=ev.target;var img=el&&el.closest?el.closest('img'):null;"
                + "if(img&&img.src){ev.preventDefault();ev.stopPropagation();try{ChitetsBridge.image(img.src,img.getAttribute('alt')||'');}catch(x){}return;}"
                + "var a=el&&el.closest?el.closest('a[href]'):null;if(!a)return;var raw=a.getAttribute('href')||'';"
                + "var h=raw.lastIndexOf('#');if(h<0)return;var tail=raw.substring(h+1),id='';try{id=decodeURIComponent(tail);}catch(x){id=tail;}if(!id)return;var target=document.getElementById(id);if(!target)return;"
                + "ev.preventDefault();ev.stopPropagation();var type=((a.getAttribute('epub:type')||'')+' '+(a.getAttribute('role')||'')+' '+(a.className||'')).toLowerCase();"
                + "var tc=((target.getAttribute&&target.getAttribute('epub:type')||'')+' '+(target.getAttribute&&target.getAttribute('role')||'')+' '+(target.className||'')).toLowerCase();"
                + "var foot=type.indexOf('noteref')>=0||type.indexOf('footnote')>=0||tc.indexOf('footnote')>=0||tc.indexOf('doc-footnote')>=0||(target.closest&&target.closest('.notes'));"
                + "try{if(foot)ChitetsBridge.footnote(id,(a.textContent||'').trim(),(target.textContent||'').replace(/\\s+/g,' ').trim().substring(0,12000));else ChitetsBridge.internalLink(id);}catch(x){}"
                + "};document.addEventListener('click',window.__chitetsClickHandler,true);";

        float parallaxRatio = settings.paperParallax >= 2 ? 0.16f : (settings.paperParallax == 1 ? 0.08f : 0f);
        String paperJs = "if(window.__chitetsPaperHandler){window.removeEventListener('scroll',window.__chitetsPaperHandler);"
                + "window.__chitetsPaperHandler=null;}window.__chitetsPaperTick=false;"
                + "if(document.body){document.body.style.backgroundPosition='0px 0px';}";
        if (paperTheme && parallaxRatio > 0f) {
            String ratio = String.format(Locale.US, "%.3f", parallaxRatio);
            paperJs += "window.__chitetsPaperHandler=function(){"
                    + "if(window.__chitetsPaperTick)return;window.__chitetsPaperTick=true;"
                    + "requestAnimationFrame(function(){var e=document.scrollingElement||document.documentElement;"
                    + "var x=e.scrollLeft||0,y=e.scrollTop||0,k=" + ratio + ";"
                    + (settings.paged
                    ? "document.body.style.backgroundPosition=(x*(1-k))+'px 0px';"
                    : "document.body.style.backgroundPosition='0px '+(y*(1-k))+'px';")
                    + "window.__chitetsPaperTick=false;});};"
                    + "window.addEventListener('scroll',window.__chitetsPaperHandler,{passive:true});";
        }

        String js = "(function(){var s=document.getElementById('readerUserStyle');"
                + "if(!s){s=document.createElement('style');s.id='readerUserStyle';document.head.appendChild(s);}"
                + "s.textContent=" + quoteJs(css) + ";"
                + "document.documentElement.classList.toggle('chitets-selection-mode'," + selectionMode + ");"
                + "document.documentElement.classList.toggle('pdf-hybrid-off'," + (pdfReflow && !pdfHybridEnabled) + ");"
                + pageTouchJs
                + navigationJs
                + paperJs
                + "setTimeout(function(){var p=" + String.format(Locale.US, "%.7f", position.progress)
                + ";var e=document.scrollingElement||document.documentElement;"
                + restore
                + "if(window.__chitetsPaperHandler)window.__chitetsPaperHandler();"
                + "},90);})();";

        webView.evaluateJavascript(js, ignored -> handler.postDelayed(() -> {
            if (restoreRequestedPdfPage) {
                requestedPdfPage = -1;
                saveProgress();
            }
            refreshTocProgress();
            refreshPageMetrics();
        }, 180));
        updateProgressText(position.progress);

        int webBackground;
        if ("dark".equals(settings.theme)) webBackground = 0xff171512;
        else if ("light".equals(settings.theme)) webBackground = Color.WHITE;
        else if ("paper_modern".equals(settings.theme)) webBackground = 0xfff7f4ec;
        else if ("paper_old".equals(settings.theme)) webBackground = 0xffe8d9b5;
        else webBackground = Ui.PAPER;
        webView.setBackgroundColor(webBackground);
        applyReaderChromeTheme();
        applyBrightness(settings.brightness);
    }

    private String getPaperDataUri(int resId) {
        if (resId == com.aurafiles.app.R.drawable.paper_modern && paperModernDataUri != null) {
            return paperModernDataUri;
        }
        if (resId == com.aurafiles.app.R.drawable.paper_old && paperOldDataUri != null) {
            return paperOldDataUri;
        }
        try (InputStream input = getResources().openRawResource(resId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            String data = "data:image/png;base64,"
                    + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            if (resId == com.aurafiles.app.R.drawable.paper_modern) paperModernDataUri = data;
            if (resId == com.aurafiles.app.R.drawable.paper_old) paperOldDataUri = data;
            return data;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void applyReaderChromeTheme() {
        int chrome;
        if ("paper_modern".equals(settings.theme)) chrome = 0xffeee9dd;
        else if ("paper_old".equals(settings.theme)) chrome = 0xffd9c498;
        else chrome = 0xfff1e7d8;
        if (toolbar != null) toolbar.setBackgroundColor(chrome);
        if (footer != null) footer.setBackgroundColor(chrome);
    }

    private void turnPage(boolean forward) {
        if (!loaded || pageTurning || selectionMode) return;
        int direction = forward ? 1 : -1;
        if (!settings.paged) {
            String js = "(function(){var e=document.scrollingElement||document.documentElement;"
                    + "e.scrollBy({left:0,top:" + direction + "*innerHeight*0.86,behavior:'smooth'});})();";
            webView.evaluateJavascript(js, null);
            handler.postDelayed(() -> { saveProgress(); refreshPageMetrics(); }, 450);
            return;
        }

        pageTurning = true;
        String js = "(function(){var e=document.scrollingElement||document.documentElement;"
                + "var step=Math.max(1,e.clientWidth||innerWidth);"
                + "var total=Math.max(1,Math.ceil(e.scrollWidth/step));"
                + "var current=Math.max(0,Math.min(total-1,Math.round(e.scrollLeft/step)));"
                + "var target=Math.max(0,Math.min(total-1,current+" + direction + "));"
                + "e.scrollTo({left:target*step,top:0,behavior:'smooth'});return target;})()";
        webView.evaluateJavascript(js, ignored -> handler.postDelayed(() -> {
            pageTurning = false;
            saveProgress();
            refreshPageMetrics();
        }, 360));
    }

    private void jumpToProgress(float progress) {
        jumpToProgress(progress, true);
    }

    private void jumpToProgress(float progress, boolean remember) {
        if (!loaded) return;
        if (remember) {
            readCurrentPosition(position -> {
                pushNavigationPosition(position);
                jumpToProgress(progress, false);
            });
            return;
        }
        float safe = Math.max(0f, Math.min(1f, progress));
        String js = settings.paged
                ? "(function(){var e=document.scrollingElement||document.documentElement;e.scrollLeft=" + String.format(Locale.US, "%.7f", safe) + "*Math.max(0,e.scrollWidth-e.clientWidth);})()"
                : "(function(){var e=document.scrollingElement||document.documentElement;e.scrollTop=" + String.format(Locale.US, "%.7f", safe) + "*Math.max(0,e.scrollHeight-e.clientHeight);})()";
        webView.evaluateJavascript(js, null);
        ReadingPrefs.setProgress(this, uriText, safe);
        updateProgressText(safe);
        handler.postDelayed(() -> { saveProgress(); refreshPageMetrics(); }, 160);
    }

    private void jumpToAnchor(String anchor) {
        jumpToAnchor(anchor, true);
    }

    private void jumpToAnchor(String anchor, boolean remember) {
        if (!loaded || anchor == null || anchor.isEmpty()) return;
        if (remember) {
            readCurrentPosition(position -> {
                pushNavigationPosition(position);
                jumpToAnchor(anchor, false);
            });
            return;
        }
        String cleanAnchor = anchor.startsWith("#") ? anchor.substring(1) : anchor;
        String js = "(function(){var e=document.getElementById(" + quoteJs(cleanAnchor) + ");if(e){e.scrollIntoView({block:'start'});"
                + "e.classList.add('chitets-footnote-target');setTimeout(function(){e.classList.remove('chitets-footnote-target');},900);return true;}return false;})()";
        webView.evaluateJavascript(js, ignored -> handler.postDelayed(() -> { saveProgress(); refreshPageMetrics(); }, 220));
    }

    private void pushNavigationPosition(ReadingPrefs.Position source) {
        if (source == null) return;
        ReadingPrefs.Position copy = copyPosition(source);
        ReadingPrefs.Position top = navigationHistory.peekFirst();
        if (top != null && Math.abs(top.progress - copy.progress) < 0.0005f
                && safeEquals(top.anchor, copy.anchor) && Math.abs(top.anchorOffset - copy.anchorOffset) < 0.02f) return;
        navigationHistory.addFirst(copy);
        while (navigationHistory.size() > MAX_NAVIGATION_HISTORY) navigationHistory.removeLast();
        updateNavigationBackButton();
    }

    private static ReadingPrefs.Position copyPosition(ReadingPrefs.Position source) {
        ReadingPrefs.Position copy = new ReadingPrefs.Position();
        copy.progress = source.progress;
        copy.anchor = source.anchor == null ? "" : source.anchor;
        copy.anchorOffset = source.anchorOffset;
        return copy;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void updateNavigationBackButton() {
        if (navigationBackButton != null) navigationBackButton.setVisibility(navigationHistory.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void navigateBack() {
        ReadingPrefs.Position target = navigationHistory.pollFirst();
        updateNavigationBackButton();
        if (target != null) restoreNavigationPosition(target);
    }

    private void navigateBackToOrigin() {
        if (navigationHistory.isEmpty()) return;
        ReadingPrefs.Position target = navigationHistory.peekLast();
        navigationHistory.clear();
        updateNavigationBackButton();
        if (target != null) {
            restoreNavigationPosition(target);
            Toast.makeText(this, "Вернулись к месту чтения", Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreNavigationPosition(ReadingPrefs.Position target) {
        if (!loaded || target == null) return;
        String anchor = target.anchor == null ? "" : target.anchor;
        String fallback = settings.paged
                ? "e.scrollLeft=p*Math.max(0,e.scrollWidth-e.clientWidth);"
                : "e.scrollTop=p*Math.max(0,e.scrollHeight-e.clientHeight);";
        String js = "(function(){var e=document.scrollingElement||document.documentElement;var p="
                + String.format(Locale.US, "%.7f", target.progress) + ";var a=" + quoteJs(anchor)
                + ";var off=" + String.format(Locale.US, "%.7f", target.anchorOffset) + ";var n=a?document.getElementById(a):null;"
                + "if(n){var r=n.getBoundingClientRect();"
                + (settings.paged
                ? "var base=e.scrollLeft+r.left;e.scrollLeft=Math.max(0,base+off*Math.max(1,r.width));"
                : "var base=e.scrollTop+r.top;e.scrollTop=Math.max(0,base+off*Math.max(1,r.height));")
                + "}else{" + fallback + "}})()";
        webView.evaluateJavascript(js, ignored -> handler.postDelayed(() -> { saveProgress(); refreshPageMetrics(); }, 180));
    }

    private void saveProgress() {
        if (!loaded) return;
        readCurrentPosition(position -> {
            ReadingPrefs.setPosition(this, uriText, position.progress, position.anchor, position.anchorOffset);
            updateProgressText(position.progress);
        });
    }

    private void readCurrentProgress(ProgressCallback callback) {
        readCurrentPosition(position -> callback.onProgress(position.progress));
    }

    private void readCurrentPosition(PositionCallback callback) {
        if (!loaded) return;
        String axisProgress = settings.paged
                ? "(e.scrollWidth<=e.clientWidth?0:e.scrollLeft/(e.scrollWidth-e.clientWidth))"
                : "(e.scrollHeight<=e.clientHeight?0:e.scrollTop/(e.scrollHeight-e.clientHeight))";
        String offset = settings.paged
                ? "Math.max(0,Math.min(1,-r.left/Math.max(1,r.width)))"
                : "Math.max(0,Math.min(1,-r.top/Math.max(1,r.height)))";
        String js = "(function(){var e=document.scrollingElement||document.documentElement;var p=" + axisProgress + ";"
                + "var n=document.elementFromPoint(innerWidth/2,Math.min(24,innerHeight/2));"
                + "while(n&&(!n.id||!(n.classList&&n.classList.contains('chapter'))))n=n.parentElement;"
                + "if(!n){n=document.elementFromPoint(innerWidth/2,Math.min(24,innerHeight/2));while(n&&!n.id)n=n.parentElement;}"
                + "var a=n&&n.id?n.id:'';var o=0;if(n){var r=n.getBoundingClientRect();o=" + offset + ";}"
                + "return p+'|'+encodeURIComponent(a)+'|'+o;})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String decoded = new JSONArray("[" + value + "]").optString(0, "");
                String[] parts = decoded.split("\\|", -1);
                ReadingPrefs.Position position = new ReadingPrefs.Position();
                position.progress = parts.length > 0 ? Float.parseFloat(parts[0]) : 0f;
                position.anchor = parts.length > 1 ? Uri.decode(parts[1]) : "";
                position.anchorOffset = parts.length > 2 ? Float.parseFloat(parts[2]) : 0f;
                position.progress = Math.max(0f, Math.min(1f, position.progress));
                position.anchorOffset = Math.max(0f, Math.min(1f, position.anchorOffset));
                callback.onPosition(position);
            } catch (Exception ignored) {
            }
        });
    }

    private void refreshTocProgress() {
        if (!loaded || document == null || document.toc.isEmpty()) {
            tocProgress = new float[0];
            return;
        }
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < document.toc.size(); i++) {
            if (i > 0) ids.append(',');
            String anchor = document.toc.get(i).anchor;
            if (anchor.startsWith("#")) anchor = anchor.substring(1);
            ids.append(quoteJs(anchor));
        }
        ids.append(']');
        String js = "(function(){var e=document.scrollingElement||document.documentElement;var ids=" + ids + ";return ids.map(function(id){var n=document.getElementById(id);if(!n)return -1;var r=n.getBoundingClientRect();"
                + (settings.paged
                ? "var max=Math.max(1,e.scrollWidth-e.clientWidth);return Math.max(0,Math.min(1,(e.scrollLeft+r.left)/max));"
                : "var max=Math.max(1,e.scrollHeight-e.clientHeight);return Math.max(0,Math.min(1,(e.scrollTop+r.top)/max));")
                + "}).join(',');})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String decoded = new JSONArray("[" + value + "]").optString(0, "");
                if (decoded.isEmpty()) return;
                String[] parts = decoded.split(",");
                float[] positions = new float[parts.length];
                for (int i = 0; i < parts.length; i++) positions[i] = Float.parseFloat(parts[i]);
                tocProgress = positions;
            } catch (Exception ignored) {
                tocProgress = new float[0];
            }
        });
    }

    private void refreshPageMetrics() {
        if (!loaded || !settings.paged) {
            pageNumber = 0;
            pageCount = 0;
            return;
        }
        String js = "(function(){var e=document.scrollingElement||document.documentElement;var w=Math.max(1,e.clientWidth||innerWidth);var total=Math.max(1,Math.ceil(e.scrollWidth/w));var page=Math.max(1,Math.min(total,Math.round(e.scrollLeft/w)+1));return page+'|'+total;})()";
        webView.evaluateJavascript(js, value -> {
            try {
                String decoded = new JSONArray("[" + value + "]").optString(0, "");
                String[] parts = decoded.split("\\|");
                if (parts.length >= 2) {
                    pageNumber = Integer.parseInt(parts[0]);
                    pageCount = Integer.parseInt(parts[1]);
                    readCurrentProgress(this::updateProgressText);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void updateProgressText(float progress) {
        int percent = Math.max(0, Math.min(100, Math.round(progress * 100f)));
        StringBuilder label = new StringBuilder().append(percent).append('%');
        if (settings.paged && pageCount > 0) label.append(" · стр ").append(pageNumber).append('/').append(pageCount);
        if (estimatedWords > 0 && progress < 0.999f) {
            int wpm = Math.max(100, settings.readingWpm);
            int bookMinutes = Math.max(1, Math.round((estimatedWords * (1f - progress)) / wpm));
            float nextChapter = 1f;
            for (float position : tocProgress) {
                if (position > progress + 0.002f) { nextChapter = position; break; }
            }
            int chapterMinutes = Math.max(1, Math.round((estimatedWords * Math.max(0f, nextChapter - progress)) / wpm));
            if (tocProgress.length > 0 && nextChapter < 0.999f) label.append(" · гл ").append(shortTime(chapterMinutes));
            label.append(" · ").append(shortTime(bookMinutes));
        }
        progressView.setText(label.toString());
        if (!seekFromUser && progressSeek != null) progressSeek.setProgress(Math.round(progress * 1000f));
    }

    private static String shortTime(int minutes) {
        if (minutes < 60) return minutes + "м";
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + "ч" : hours + "ч" + rest + "м";
    }

    private static int estimateWords(String html) {
        if (html == null || html.isEmpty()) return 0;
        String text = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return 0;
        return text.split("\\s+").length;
    }

    private void showFootnoteDialog(String anchor, String label, String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            jumpToAnchor(anchor, true);
            return;
        }
        TextView note = Ui.text(this, clean, 16, Ui.INK);
        note.setTextIsSelectable(true);
        note.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 8));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(note, new android.widget.ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String title = label == null || label.trim().isEmpty() ? "Сноска" : "Сноска " + label.trim();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Перейти", (dialog, which) -> jumpToAnchor(anchor, true))
                .show();
    }

    private final class ReaderBridge {
        @JavascriptInterface public void internalLink(String anchor) {
            handler.post(() -> jumpToAnchor(anchor, true));
        }

        @JavascriptInterface public void footnote(String anchor, String label, String text) {
            handler.post(() -> showFootnoteDialog(anchor, label, text));
        }

        @JavascriptInterface public void image(String source, String alt) {
            handler.post(() -> ReaderImageDialog.show(ReaderActivity.this, source, alt, executor, handler));
        }
    }

    private void showToc() {
        if (!loaded || document == null) return;
        if (document.toc.isEmpty()) {
            Toast.makeText(this, "В этой книге оглавление не найдено", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[document.toc.size()];
        for (int i = 0; i < labels.length; i++) {
            TocEntry entry = document.toc.get(i);
            StringBuilder indent = new StringBuilder();
            for (int n = 0; n < Math.min(3, entry.level); n++) indent.append("    ");
            labels[i] = indent + entry.title;
        }
        new AlertDialog.Builder(this)
                .setTitle("Оглавление")
                .setItems(labels, (dialog, which) -> jumpToAnchor(document.toc.get(which).anchor))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showBookmarks() {
        if (!loaded) return;
        List<Float> bookmarks = ReadingPrefs.getBookmarks(this, uriText);
        String[] items = new String[bookmarks.size() + 1];
        items[0] = "★ Добавить закладку здесь";
        for (int i = 0; i < bookmarks.size(); i++) {
            items[i + 1] = "Закладка — " + Math.round(bookmarks.get(i) * 100f) + "%";
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Закладки")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        readCurrentProgress(progress -> {
                            ReadingPrefs.addBookmark(this, uriText, progress);
                            Toast.makeText(this, "Закладка добавлена", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        jumpToProgress(bookmarks.get(which - 1));
                    }
                })
                .setNeutralButton(bookmarks.isEmpty() ? null : "Удалить…", null)
                .setNegativeButton("Закрыть", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (!bookmarks.isEmpty()) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> showDeleteBookmark(bookmarks));
            }
        });
        dialog.show();
    }

    private void showDeleteBookmark(List<Float> bookmarks) {
        String[] labels = new String[bookmarks.size()];
        for (int i = 0; i < bookmarks.size(); i++) labels[i] = "Закладка — " + Math.round(bookmarks.get(i) * 100f) + "%";
        new AlertDialog.Builder(this)
                .setTitle("Удалить закладку")
                .setItems(labels, (dialog, which) -> {
                    ReadingPrefs.removeBookmark(this, uriText, bookmarks.get(which));
                    Toast.makeText(this, "Закладка удалена", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showNotes() {
        if (!loaded) return;
        List<ReadingPrefs.Note> notes = ReadingPrefs.getNotes(this, uriText);
        String[] items = new String[notes.size() + 2];
        items[0] = "＋ Новая заметка здесь";
        items[1] = "✎ Выделить текст для цитаты";
        for (int i = 0; i < notes.size(); i++) {
            ReadingPrefs.Note note = notes.get(i);
            String preview = !note.quote.isEmpty() ? note.quote : note.text;
            preview = preview.replaceAll("\\s+", " ").trim();
            if (preview.length() > 55) preview = preview.substring(0, 55) + "…";
            if (preview.isEmpty()) preview = "Заметка";
            items[i + 2] = Math.round(note.progress * 100f) + "% — " + preview;
        }
        new AlertDialog.Builder(this)
                .setTitle("Цитаты и заметки")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) readCurrentProgress(progress -> showAddNoteDialog("", progress));
                    else if (which == 1) enterSelectionMode();
                    else showNoteDetail(notes.get(which - 2));
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void enterSelectionMode() {
        if (!loaded) return;
        if (!chromeVisible) toggleChrome();
        selectionMode = true;
        pageTurning = false;
        webView.setLongClickable(true);
        if (notesButton != null) {
            notesButton.setText("✓");
            notesButton.setContentDescription("Сохранить выделение как цитату");
        }
        webView.evaluateJavascript("document.documentElement.classList.add('chitets-selection-mode');", null);
        Toast.makeText(this, "Удерживайте слово, выделите фразу и нажмите ✓ сверху", Toast.LENGTH_LONG).show();
    }

    private void finishSelectionForNote() {
        captureSelection(quote -> {
            if (quote == null || quote.trim().isEmpty()) {
                Toast.makeText(this, "Сначала выделите нужный фрагмент текста", Toast.LENGTH_SHORT).show();
                return;
            }
            readCurrentProgress(progress -> {
                exitSelectionMode(true);
                showAddNoteDialog(quote, progress);
            });
        });
    }

    private void exitSelectionMode(boolean clearSelection) {
        selectionMode = false;
        webView.setLongClickable(false);
        if (notesButton != null) {
            notesButton.setText("✎");
            notesButton.setContentDescription("Цитаты и заметки");
        }
        String clear = clearSelection
                ? "if(window.getSelection){var s=window.getSelection();if(s)s.removeAllRanges();}"
                : "";
        webView.evaluateJavascript("document.documentElement.classList.remove('chitets-selection-mode');" + clear, null);
    }

    private void showAddNoteDialog(String quote, float progress) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        panel.setPadding(pad, 0, pad, 0);
        if (quote != null && !quote.trim().isEmpty()) {
            TextView selected = Ui.text(this, "«" + quote.trim() + "»", 14, Ui.MUTED);
            selected.setPadding(0, 0, 0, Ui.dp(this, 8));
            panel.addView(selected);
        }
        EditText note = new EditText(this);
        note.setHint("Комментарий (можно оставить пустым)");
        note.setMinLines(3);
        note.setMaxLines(7);
        panel.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Новая заметка — " + Math.round(progress * 100f) + "%")
                .setView(panel)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    ReadingPrefs.addNote(this, uriText, progress, quote, note.getText().toString());
                    Toast.makeText(this, "Заметка сохранена", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showNoteDetail(ReadingPrefs.Note note) {
        StringBuilder text = new StringBuilder();
        if (!note.quote.isEmpty()) text.append('«').append(note.quote).append('»');
        if (!note.text.isEmpty()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(note.text);
        }
        if (text.length() == 0) text.append("Заметка без текста");
        new AlertDialog.Builder(this)
                .setTitle("Заметка — " + Math.round(note.progress * 100f) + "%")
                .setMessage(text.toString())
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Удалить", (dialog, which) -> {
                    ReadingPrefs.removeNote(this, uriText, note.id);
                    Toast.makeText(this, "Заметка удалена", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Перейти", (dialog, which) -> jumpToProgress(note.progress))
                .show();
    }

    private void captureSelection(SelectionCallback callback) {
        webView.evaluateJavascript("(window.getSelection?window.getSelection().toString():'')", value -> {
            try {
                callback.onSelection(new JSONArray("[" + value + "]").optString(0, ""));
            } catch (Exception ignored) {
                callback.onSelection("");
            }
        });
    }

    private void showSearch() {
        if (!loaded) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        panel.setPadding(pad, 0, pad, 0);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Слово или фраза");
        panel.addView(query);
        TextView status = Ui.text(this, "Введите запрос", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 5), 0, 0);
        panel.addView(status);
        webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
            if (isDoneCounting) {
                status.setText(numberOfMatches == 0 ? "Ничего не найдено" : (activeMatchOrdinal + 1) + " из " + numberOfMatches);
            }
        });
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s.toString().trim();
                if (value.isEmpty()) { webView.clearMatches(); status.setText("Введите запрос"); }
                else webView.findAllAsync(value);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Поиск по книге")
                .setView(panel)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("‹ Назад", null)
                .setPositiveButton("Вперёд ›", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> webView.findNext(false));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> webView.findNext(true));
            query.requestFocus();
        });
        dialog.setOnDismissListener(ignored -> { webView.clearMatches(); webView.setFindListener(null); });
        dialog.show();
    }

    private void showSettings() {
        if (selectionMode) exitSelectionMode(true);
        ReadingPrefs.ReaderSettings current = ReadingPrefs.getSettings(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        panel.setPadding(pad, 0, pad, 0);

        SeekBar fontSize = addSeek(panel, "Размер шрифта", current.fontSize - 14, 20);
        SeekBar lineHeight = addSeek(panel, "Межстрочный интервал", (current.lineHeight - 120) / 4, 20);
        SeekBar margin = addSeek(panel, "Поля страницы", current.margin - 4, 36);
        SeekBar paragraphIndent = addSeek(panel, "Абзацный отступ", current.paragraphIndent, 40);
        SeekBar paragraphSpacing = addSeek(panel, "Интервал между абзацами", current.paragraphSpacing, 24);
        SeekBar brightness = addSeek(panel, "Яркость: 0 = системная", current.brightness, 100);
        SeekBar readingWpm = addSeek(panel, "Скорость чтения (120–500 слов/мин)", (current.readingWpm - 120) / 10, 38);

        Spinner theme = addSpinner(panel, "Тема", new String[]{"Сепия", "Светлая", "Тёмная", "Бумага — современная", "Бумага — старая"});
        int themeSelection;
        if ("light".equals(current.theme)) themeSelection = 1;
        else if ("dark".equals(current.theme)) themeSelection = 2;
        else if ("paper_modern".equals(current.theme)) themeSelection = 3;
        else if ("paper_old".equals(current.theme)) themeSelection = 4;
        else themeSelection = 0;
        theme.setSelection(themeSelection);
        Spinner paperParallax = addSpinner(panel, "Параллакс бумажной текстуры", new String[]{"Выкл", "Слабый", "Средний"});
        paperParallax.setSelection(Math.max(0, Math.min(2, current.paperParallax)));
        Spinner font = addSpinner(panel, "Шрифт", new String[]{"Из книги (EPUB)", "Книжный", "Без засечек", "Моноширинный"});
        font.setSelection("book".equals(current.font) ? 0 : ("sans".equals(current.font) ? 2 : ("mono".equals(current.font) ? 3 : 1)));

        CheckBox justify = new CheckBox(this);
        justify.setText("Выравнивать текст по ширине");
        justify.setChecked(current.justify);
        panel.addView(justify);
        CheckBox paged = new CheckBox(this);
        paged.setText("Постраничный режим");
        paged.setChecked(current.paged);
        panel.addView(paged);
        CheckBox edgeGestures = new CheckBox(this);
        edgeGestures.setText("Жесты по краям: слева яркость, справа шрифт");
        edgeGestures.setChecked(current.edgeGestures);
        panel.addView(edgeGestures);
        final CheckBox pdfHybrid = pdfReflow ? new CheckBox(this) : null;
        if (pdfHybrid != null) {
            pdfHybrid.setText("Умный PDF: таблицы, формулы и сложную вёрстку показывать как оригинал");
            pdfHybrid.setChecked(pdfHybridEnabled);
            panel.addView(pdfHybrid);
        }

        new AlertDialog.Builder(this)
                .setTitle("Настройки чтения")
                .setView(panel)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (dialog, which) -> {
                    ReadingPrefs.ReaderSettings next = new ReadingPrefs.ReaderSettings();
                    next.fontSize = 14 + fontSize.getProgress();
                    next.lineHeight = 120 + lineHeight.getProgress() * 4;
                    next.margin = 4 + margin.getProgress();
                    next.paragraphIndent = paragraphIndent.getProgress();
                    next.paragraphSpacing = paragraphSpacing.getProgress();
                    next.brightness = brightness.getProgress();
                    next.readingWpm = 120 + readingWpm.getProgress() * 10;
                    next.edgeGestures = edgeGestures.isChecked();
                    int themeChoice = theme.getSelectedItemPosition();
                    next.theme = themeChoice == 1 ? "light"
                            : (themeChoice == 2 ? "dark"
                            : (themeChoice == 3 ? "paper_modern"
                            : (themeChoice == 4 ? "paper_old" : "sepia")));
                    next.paperParallax = paperParallax.getSelectedItemPosition();
                    int fontChoice = font.getSelectedItemPosition();
                    next.font = fontChoice == 0 ? "book" : (fontChoice == 2 ? "sans" : (fontChoice == 3 ? "mono" : "serif"));
                    next.justify = justify.isChecked();
                    next.paged = paged.isChecked();
                    if (pdfHybrid != null) {
                        pdfHybridEnabled = pdfHybrid.isChecked();
                        getSharedPreferences("pdf_reflow_ui", MODE_PRIVATE).edit().putBoolean("hybrid_enabled", pdfHybridEnabled).apply();
                    }
                    readCurrentPosition(position -> {
                        ReadingPrefs.setPosition(this, uriText, position.progress, position.anchor, position.anchorOffset);
                        ReadingPrefs.saveSettings(this, next);
                        settings = next;
                        applySettingsAndRestore();
                    });
                }).show();
    }

    private SeekBar addSeek(LinearLayout panel, String label, int progress, int max) {
        TextView title = Ui.text(this, label, 14, Ui.INK);
        title.setPadding(0, Ui.dp(this, 9), 0, 0);
        panel.addView(title);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(Math.max(0, Math.min(max, progress)));
        panel.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return seek;
    }

    private Spinner addSpinner(LinearLayout panel, String label, String[] values) {
        TextView title = Ui.text(this, label, 14, Ui.INK);
        title.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        panel.addView(title);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        panel.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        toolbar.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        footer.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        if (chromeVisible) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            Ui.setLightSystemBars(this, true);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void applyBrightness(int value) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = value <= 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.05f, Math.min(1f, value / 100f));
        getWindow().setAttributes(params);
    }

    private static String quoteJs(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "").replace("\n", "\\n") + "'";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            turnPage(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            turnPage(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && selectionMode) {
            exitSelectionMode(true);
            Toast.makeText(this, "Выделение отменено", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && !chromeVisible) {
            toggleChrome();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && !navigationHistory.isEmpty()) {
            navigateBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        saveProgress();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private interface ProgressCallback {
        void onProgress(float progress);
    }

    private interface PositionCallback {
        void onPosition(ReadingPrefs.Position position);
    }

    private interface SelectionCallback {
        void onSelection(String selection);
    }
}

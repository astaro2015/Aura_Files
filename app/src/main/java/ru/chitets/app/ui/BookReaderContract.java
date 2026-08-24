package ru.chitets.app.ui;

/** Intent keys shared by Aura Files and the embedded book viewers. */
public final class BookReaderContract {
    public static final String EXTRA_URI = "reader_uri";
    public static final String EXTRA_TITLE = "reader_title";
    public static final String EXTRA_FORMAT = "reader_format";
    public static final String EXTRA_PDF_PAGE = "reader_pdf_page";
    public static final String EXTRA_DJVU_PAGE = "reader_djvu_page";

    private BookReaderContract() {}
}

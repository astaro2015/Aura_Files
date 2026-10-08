package ru.chitets.app.parser;

import java.io.InterruptedIOException;

/** Shared guardrails for blocking reader I/O executed on cancellable worker threads. */
public final class ReaderIoPolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long DJVU_HARD_CAP_BYTES = 128L * MIB;

    private ReaderIoPolicy() {}

    /**
     * DjVu is decoded by the in-process Java decoder and therefore the complete source currently
     * has to live in the Java heap. ByteArrayOutputStream growth/finalization and page decoding can
     * temporarily require several copies/buffers, so never dedicate most of the heap to the source.
     */
    public static long safeDjvuSourceBytes() {
        Runtime runtime = Runtime.getRuntime();
        long max = Math.max(1L, runtime.maxMemory());
        long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        long headroom = Math.max(0L, max - used);
        long byHeap = max / 6L;
        long byHeadroom = headroom / 3L;
        return Math.max(1L * MIB, Math.min(DJVU_HARD_CAP_BYTES, Math.min(byHeap, byHeadroom)));
    }

    public static int safeDjvuSourceMiB() {
        return (int) Math.max(1L, safeDjvuSourceBytes() / MIB);
    }

    public static void throwIfInterrupted(String operation) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(operation + " отменено");
        }
    }
}

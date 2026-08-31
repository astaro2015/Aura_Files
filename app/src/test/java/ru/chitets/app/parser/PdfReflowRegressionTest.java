package ru.chitets.app.parser;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import ru.chitets.app.model.ReaderDocument;

public class PdfReflowRegressionTest {
    @Test public void simpleTextPdfStillReflowsWithoutOcr() throws Exception {
        byte[] pdf = buildPdf(
            "Aura Files PDF Reflow regression text. This page contains enough native text " +
            "to verify extraction, paragraph reconstruction and the no-OCR path remains alive."
        );
        File cache = Files.createTempDirectory("aura-pdf-reflow-test").toFile();
        try {
            ReaderDocument document = PdfReflowParser.parse(new ByteArrayInputStream(pdf), cache, "Regression");
            assertTrue(document.html.contains("Aura Files PDF Reflow regression text"));
            assertTrue(document.html.contains("pdf-reflow-page"));
        } finally {
            deleteTree(cache);
        }
    }

    private static byte[] buildPdf(String text) throws Exception {
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        String escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        byte[] content = ("BT /F1 12 Tf 72 720 Td (" + escaped + ") Tj ET").getBytes(StandardCharsets.ISO_8859_1);
        objects.add("<< /Length " + content.length + " >>\nstream\n" + new String(content, StandardCharsets.ISO_8859_1) + "\nendstream");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            out.write(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = out.size();
        out.write(("xref\n0 " + (objects.size() + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write("0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int i = 1; i < offsets.size(); i++) {
            out.write(String.format("%010d 00000 n \n", offsets.get(i)).getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}

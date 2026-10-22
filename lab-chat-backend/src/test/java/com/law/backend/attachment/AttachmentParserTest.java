package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import com.law.backend.service.ImageCaptionService;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentParserTest {
    @TempDir Path temp;
    final FileProperties properties = new FileProperties();
    final ImageCaptionService vision = mock(ImageCaptionService.class);
    AttachmentParser parser() { return new AttachmentParser(properties, vision); }
    @Test void retainsTextAfterTwentyThousandCharacters() throws Exception {
        String text = "普通条款。\n".repeat(4000) + "末尾保修期限为七年。";
        Path path = temp.resolve("long.txt"); Files.writeString(path, text);
        var result = parser().parse(path, "txt");
        assertEquals(text, result.text()); assertTrue(result.text().endsWith("末尾保修期限为七年。"));
        verifyNoInteractions(vision);
    }
    @Test void readsDocxTablesInDocumentOrder() throws Exception {
        Path path = temp.resolve("table.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(path)) {
            doc.createParagraph().createRun().setText("第一条 付款");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目"); table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("违约金"); table.getRow(1).getCell(1).setText("合成示例：二百元");
            doc.createParagraph().createRun().setText("第二条 但不可抗力除外"); doc.write(out);
        }
        var result = parser().parse(path, "docx");
        assertTrue(result.text().contains("合成示例：二百元"));
        assertTrue(result.text().indexOf("违约金") < result.text().indexOf("不可抗力"));
        assertTrue(result.sources().stream().anyMatch(s -> s.location().contains("表格第2行第2列")));
    }
    @Test void readsPdfPageThirtyOne() throws Exception {
        Path path = pdf(31, false);
        var result = parser().parse(path, "pdf");
        assertEquals(31, result.totalPages()); assertEquals(31, result.processedPages());
        assertTrue(result.text().contains("PAGE_31_WARRANTY_TAIL"));
        assertTrue(result.sources().stream().anyMatch(s -> s.location().equals("第31页")));
    }
    @Test void rejectsOversizedPdfInsteadOfSilentlyReadingPrefix() throws Exception {
        properties.setMaxPages(2);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parser().parse(pdf(3, false), "pdf")).getMessage().contains("未截取"));
    }
    @Test void reportsFailedOcrPageAndKeepsSuccessfulPageFullTranscription() throws Exception {
        when(vision.transcribe(any(), eq("image/png"))).thenThrow(new IllegalStateException()).thenReturn("完整转录：第二页责任限制与但书。".repeat(20));
        var result = parser().parse(pdf(2, true), "pdf");
        assertEquals(java.util.List.of(1), result.failedPages()); assertEquals(1, result.processedPages());
        assertTrue(result.text().contains("责任限制与但书")); assertFalse(result.warnings().isEmpty());
        verify(vision, times(2)).transcribe(any(), eq("image/png")); verify(vision, never()).caption(any(), any());
    }
    @Test void rejectsTextLimitWithoutSavingTruncatedSuccess() throws Exception {
        properties.setMaxTextChars(100);
        Path path = temp.resolve("limit.txt"); Files.writeString(path, "条款".repeat(100));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parser().parse(path, "txt")).getMessage().contains("未截取"));
    }
    @Test void allOcrFailuresRetainPageCoverageForTheStatusResponse() throws Exception {
        when(vision.transcribe(any(), anyString())).thenThrow(new IllegalStateException());
        var result = parser().parse(pdf(2, true), "pdf");
        assertEquals(2, result.totalPages()); assertEquals(0, result.processedPages());
        assertEquals(java.util.List.of(1, 2), result.failedPages()); assertFalse(result.warnings().isEmpty());
    }
    private Path pdf(int pages, boolean scan) throws Exception {
        Path path = temp.resolve("fixture-" + pages + ".pdf");
        try (var doc = new PDDocument()) {
            for (int n = 1; n <= pages; n++) {
                var page = new PDPage(); doc.addPage(page);
                try (var out = new PDPageContentStream(doc, page)) {
                    if (scan) {
                        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
                        out.drawImage(LosslessFactory.createFromImage(doc, image), 20, 20, 400, 400);
                    } else {
                        out.beginText(); out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10); out.newLineAtOffset(30, 600);
                        out.showText("Synthetic clause for testing only. More than fifty characters. PAGE_" + n + "_WARRANTY_TAIL"); out.endText();
                    }
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }
}

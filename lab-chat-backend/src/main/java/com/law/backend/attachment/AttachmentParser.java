package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import com.law.backend.service.ImageCaptionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 有界完整解析。拒绝超限文档；单页失败显式计入覆盖率，绝不冒充全文成功。 */
@Component
public class AttachmentParser {
    public static final String VERSION = "document-v1";
    public record Parsed(String text, List<AttachmentChunker.Source> sources, int totalPages,
                         int processedPages, List<Integer> failedPages, List<String> warnings, String kind) {}
    private final FileProperties properties;
    private final ImageCaptionService vision;
    public AttachmentParser(FileProperties properties, ImageCaptionService vision) {
        this.properties = properties; this.vision = vision;
    }

    public Parsed parse(Path path, String extension) throws Exception {
        Accumulator a = new Accumulator();
        return switch (extension) {
            case "txt", "md" -> {
                byte[] bytes = Files.readAllBytes(path);
                String text = decodeText(bytes);
                a.append(text, "正文");
                yield a.result(1, 1, List.of(), "text");
            }
            case "docx" -> {
                try (var input = Files.newInputStream(path); var document = new XWPFDocument(input)) {
                    for (var header : document.getHeaderList()) body(header.getBodyElements(), a, "页眉");
                    body(document.getBodyElements(), a, "文档");
                    for (var note : document.getFootnotes()) body(note.getBodyElements(), a, "脚注" + note.getId());
                    for (var note : document.getEndnotes()) body(note.getBodyElements(), a, "尾注" + note.getId());
                    for (var footer : document.getFooterList()) body(footer.getBodyElements(), a, "页脚");
                }
                yield a.result(1, 1, List.of(), "text");
            }
            case "pdf" -> pdf(path, a);
            case "png", "jpg", "jpeg", "webp" -> {
                validateImage(path);
                BufferedImage decoded = ImageIO.read(path.toFile());
                if (decoded != null && !meaningful(decoded)) throw new IllegalArgumentException("图片过小或无可辨识内容");
                if (decoded != null) decoded.flush();
                try {
                    if (!properties.isOcrEnabled()) throw new IllegalStateException();
                    String text = vision.transcribe(Files.readAllBytes(path), mime(extension));
                    a.append(text, "图片 OCR（需核对原图）");
                    if (text.contains("[无法辨认]")) a.warn("图片存在无法辨认的文字，需核对原图");
                    if (text.equals("[无文字]")) a.warn("图片未检测到可转录文字；图像内容须通过直接看图核对");
                    yield a.result(1, 1, List.of(), "image");
                } catch (Exception e) {
                    a.warn("图片 OCR 未完成；可在普通问答中直接看图，无法用于全文审查");
                    yield a.result(1, 0, List.of(1), "image");
                }
            }
            default -> throw new IllegalArgumentException("不支持的附件格式");
        };
    }

    private void body(List<IBodyElement> elements, Accumulator a, String path) throws Exception {
        int i = 0;
        for (IBodyElement element : elements) {
            a.check();
            String location = path + " 第" + (++i) + "段";
            if (element instanceof XWPFParagraph paragraph) {
                a.append(paragraph.getText() + "\n", location);
                for (XWPFRun run : paragraph.getRuns()) for (XWPFPicture picture : run.getEmbeddedPictures()) {
                    if (++a.images > properties.getMaxImagesPerDoc()) {
                        a.warn("文档图片超过 OCR 限额，部分图片内容未转录，请单独上传相关图片");
                        continue;
                    }
                    try {
                        if (!properties.isOcrEnabled()) throw new IllegalStateException();
                        byte[] data = picture.getPictureData().getData();
                        validateImage(data);
                        String text = vision.transcribe(data, mime(picture.getPictureData().suggestFileExtension()));
                        a.append("\n" + text + "\n", location + " 图片 OCR");
                        if (text.contains("[无法辨认]")) a.warn("文档图片含无法辨认的文字");
                    } catch (Exception e) { a.warn("文档中有图片未能转录，请单独上传核对"); }
                }
            } else if (element instanceof XWPFTable table) {
                int row = 0;
                for (XWPFTableRow tableRow : table.getRows()) {
                    int col = 0;
                    for (XWPFTableCell cell : tableRow.getTableCells()) {
                        body(cell.getBodyElements(), a, location + " 表格第" + (row + 1) + "行第" + (++col) + "列");
                        a.append("\t", location);
                    }
                    a.append("\n", location);
                    row++;
                }
            }
        }
    }

    private Parsed pdf(Path path, Accumulator a) throws Exception {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages > properties.getMaxPages()) throw new IllegalArgumentException("PDF 超过 " + properties.getMaxPages() + " 页，请拆分上传；未截取正文");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = new PDFRenderer(document);
            List<Integer> failed = new ArrayList<>();
            int processed = 0;
            for (int page = 1; page <= pages; page++) {
                if (a.timedOut()) { for (int n = page; n <= pages; n++) failed.add(n); a.warn("解析达到时间上限，剩余页未处理"); break; }
                String location = "第" + page + "页";
                String textLayer = "";
                try {
                    stripper.setStartPage(page); stripper.setEndPage(page);
                    String text = stripper.getText(document); textLayer = text;
                    var resources = document.getPage(page - 1).getResources();
                    boolean graphics = resources != null && resources.getXObjectNames().iterator().hasNext();
                    if (text.strip().length() < 50 && graphics) {
                        if (!properties.isOcrEnabled()) throw new IllegalStateException();
                        var box = document.getPage(page - 1).getCropBox();
                        double pixels = box.getWidth() * box.getHeight() * Math.pow(120.0 / 72, 2);
                        if (pixels > properties.getMaxImagePixels()) throw new IllegalStateException();
                        BufferedImage image = renderer.renderImageWithDPI(page - 1, 120);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        ImageIO.write(image, "png", bytes); image.flush();
                        text = vision.transcribe(bytes.toByteArray(), "image/png");
                        location += " OCR（需核对原图）";
                        if (text.contains("[无法辨认]")) a.warn("扫描页含无法辨认的文字");
                        if (text.equals("[无文字]")) a.warn("部分页面仅含图像，OCR 无文字结果，需核对原件");
                    } else if (graphics) {
                        a.warn("PDF 文字页中的图表未单独 OCR；需核对原图或单独上传图表");
                    }
                    a.append("\n" + text + "\n", location);
                    processed++;
                } catch (LimitExceeded e) { throw e; }
                catch (Exception e) {
                    failed.add(page);
                    if (!textLayer.isBlank()) a.append("\n" + textLayer + "\n", "第" + page + "页文字层（OCR未完成）");
                }
            }
            if (!failed.isEmpty()) a.warn("部分页面未完成解析或 OCR，问答仅依据已解析内容");
            return a.result(pages, processed, failed, "text");
        }
    }

    public static String mime(String extension) { return "image/" + ("jpg".equals(extension) ? "jpeg" : extension); }
    private String decodeText(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) charset = StandardCharsets.UTF_16;
        try { return charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) {
            if (charset != StandardCharsets.UTF_8) throw e;
            return Charset.forName("GB18030").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
    private void validateImage(Path path) throws Exception { validateImage(Files.readAllBytes(path)); }
    private void validateImage(byte[] bytes) throws Exception {
        if (bytes.length > properties.getMaxImageBytes()) throw new IllegalArgumentException("图片超过大小限制");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                int[] dimensions = webpDimensions(bytes);
                if (dimensions == null) throw new IllegalArgumentException("图片无法安全解码，请转为 PNG 或 JPEG");
                checkPixels(dimensions[0], dimensions[1]); return;
            }
            var reader = readers.next();
            try {
                reader.setInput(stream);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
            } finally { reader.dispose(); }
        }
    }
    private void checkPixels(int width, int height) {
        long pixels = (long) width * height;
        if (pixels > properties.getMaxImagePixels() || pixels < 1) throw new IllegalArgumentException("图片分辨率超过处理上限");
    }
    private int[] webpDimensions(byte[] b) {
        if (b.length < 30 || !new String(b, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") || !new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return null;
        String kind = new String(b, 12, 4, StandardCharsets.US_ASCII);
        if (kind.equals("VP8X")) return new int[]{1 + ((b[24] & 255) | (b[25] & 255) << 8 | (b[26] & 255) << 16), 1 + ((b[27] & 255) | (b[28] & 255) << 8 | (b[29] & 255) << 16)};
        if (kind.equals("VP8L") && (b[20] & 255) == 0x2F) return new int[]{1 + ((b[21] & 255) | (b[22] & 63) << 8), 1 + ((b[22] & 192) >> 6 | (b[23] & 255) << 2 | (b[24] & 15) << 10)};
        if (kind.equals("VP8 ") && (b[23] & 255) == 0x9D && b[24] == 1 && b[25] == 0x2A) return new int[]{((b[26] & 255) | (b[27] & 255) << 8) & 16383, ((b[28] & 255) | (b[29] & 255) << 8) & 16383};
        return null;
    }
    /** 过滤图标与纯色噪声；邻域采样避免棋盘格被单点采样混叠。 */
    public boolean meaningful(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        if (w < properties.getMinImagePx() || h < properties.getMinImagePx()) return false;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int gx = 0; gx < 5; gx++) for (int gy = 0; gy < 5; gy++) {
            int cx = Math.min(w - 1, gx * w / 5 + w / 10), cy = Math.min(h - 1, gy * h / 5 + h / 10);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                int rgb = image.getRGB(Math.max(0, Math.min(w - 1, cx + dx)), Math.max(0, Math.min(h - 1, cy + dy)));
                int sum = ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
                min = Math.min(min, sum); max = Math.max(max, sum);
            }
        }
        return max - min >= 36;
    }
    private static class LimitExceeded extends IllegalArgumentException { LimitExceeded(String message) { super(message); } }
    private final class Accumulator {
        final StringBuilder text = new StringBuilder();
        final List<AttachmentChunker.Source> sources = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final long deadline = System.nanoTime() + properties.getProcessingTimeoutSeconds() * 1_000_000_000L;
        int images;
        boolean timedOut() { return Thread.currentThread().isInterrupted() || System.nanoTime() > deadline; }
        void check() { if (timedOut()) throw new LimitExceeded("附件解析超时，请拆分后重试"); }
        void append(String value, String location) {
            if (text.length() + value.length() > properties.getMaxTextChars()) throw new LimitExceeded("提取文字超过 " + properties.getMaxTextChars() + " 字限制，请拆分上传；未截取正文");
            int start = text.length(); text.append(value);
            if (!value.isEmpty()) sources.add(new AttachmentChunker.Source(start, text.length(), location));
        }
        void warn(String value) { if (!warnings.contains(value)) warnings.add(value); }
        Parsed result(int pages, int processed, List<Integer> failed, String kind) {
            return new Parsed(text.toString(), List.copyOf(sources), pages, processed, List.copyOf(failed), List.copyOf(warnings), kind);
        }
    }
}

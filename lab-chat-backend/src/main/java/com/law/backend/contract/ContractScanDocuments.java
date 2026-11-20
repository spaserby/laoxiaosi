package com.law.backend.contract;

import com.law.backend.service.ImageCaptionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.IIOImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static com.law.backend.contract.ContractScanStore.ScanPage;

@Component
final class ContractScanDocuments {
    private static final int MAX_PAGE_TEXT = 100_000;
    private final ContractScanProperties properties;
    private final ImageCaptionService vision;

    ContractScanDocuments(ContractScanProperties properties, ImageCaptionService vision) {
        this.properties = properties;
        this.vision = vision;
    }

    void verifyPhoto(Path path, String format) throws IOException {
        try (var image = ImageIO.createImageInputStream(path.toFile())) {
            if (image == null) throw new IllegalArgumentException("无法读取照片，请重新选择");
            var readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) throw new IllegalArgumentException("照片应为 JPEG 或 PNG");
            var reader = readers.next();
            try {
                String actual = reader.getFormatName();
                if (!(format.equals("jpg") && actual.equalsIgnoreCase("jpeg")) && !actual.equalsIgnoreCase(format))
                    throw new IllegalArgumentException("照片格式与文件内容不一致");
                reader.setInput(image, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 12_000 || height > 12_000 || (long) width * height > properties.getMaxPhotoPixels())
                    throw new IllegalArgumentException("照片像素超限，请使用自动优化后的图片");
            } finally {
                reader.dispose();
            }
        }
    }

    Pdf pdf(Path path) throws IOException {
        PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache());
        if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > properties.getMaxPdfPages()) {
            document.close();
            throw new IllegalArgumentException("请上传未加密的 PDF，最多 " + properties.getMaxPdfPages() + " 页");
        }
        return new Pdf(document);
    }

    ScanPage photo(Path path, String format) throws IOException {
        verifyPhoto(path, format);
        return transcribe(Files.readAllBytes(path), format.equals("jpg") ? "image/jpeg" : "image/png", "");
    }

    private ScanPage transcribe(byte[] bytes, String mime, String textLayer) {
        try {
            String text = vision.transcribe(bytes, mime);
            if (text.equals("[无文字]")) return new ScanPage(textLayer, "图片文字待核对", false, true);
            if (text.length() > MAX_PAGE_TEXT) return new ScanPage(textLayer, "页内文字量超出处理限额，请拆分此页", false, true);
            if (text.replace("[无法辨认]", "").isBlank()) return new ScanPage(textLayer, "文字无法辨认，请核对原件", false, true);
            return new ScanPage(text, text.contains("[无法辨认]") ? "部分文字无法辨认，请核对原件" : null, true, true);
        } catch (RuntimeException failure) {
            return new ScanPage(textLayer, "此页识别未完成，可重试", false, true);
        }
    }

    static boolean hasImages(PDResources resources, Set<COSBase> visited) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) return false;
        if (visited.size() > 64) return true;
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject || (object instanceof PDFormXObject form && hasImages(form.getResources(), visited))) return true;
        }
        return false;
    }

    final class Pdf implements AutoCloseable {
        private final PDDocument document;
        private final PDFRenderer renderer;

        Pdf(PDDocument document) {
            this.document = document;
            renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
        }

        int pages() { return document.getNumberOfPages(); }

        ScanPage scan(int page, java.util.function.BooleanSupplier ocrSlot) throws IOException {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document).strip();
            if (text.length() > MAX_PAGE_TEXT) return new ScanPage("", "页内文字量超出处理限额，请拆分此页", false, false);
            var visited = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
            if (text.isBlank() || hasImages(document.getPage(page - 1).getResources(), visited)) {
                if (!ocrSlot.getAsBoolean()) return new ScanPage(text, "今日识别额度已用完，可稍后重试", false, true);
                try {
                    return transcribe(raster(page, 3300), "image/jpeg", text);
                } catch (IOException | IllegalArgumentException failure) {
                    return new ScanPage(text, "该页图像未能识别，可核对原件或重试", false, true);
                }
            }
            return new ScanPage(text, null, true, false);
        }

        byte[] raster(int page, int longEdge) throws IOException {
            var bounds = document.getPage(page - 1).getCropBox();
            float edge = Math.max(bounds.getWidth(), bounds.getHeight());
            if (!Float.isFinite(edge) || edge <= 0 || edge > 100_000) throw new IllegalArgumentException("PDF 页面尺寸异常");
            float dpi = Math.min(240f, longEdge * 72f / edge);
            var image = renderer.renderImageWithDPI(page - 1, dpi, ImageType.RGB);
            var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
            try (var output = new ByteArrayOutputStream(); var stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                var params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.91f);
                writer.write(null, new IIOImage(image, null, null), params);
                stream.flush();
                return output.toByteArray();
            } finally {
                writer.dispose();
                image.flush();
            }
        }

        @Override public void close() throws IOException { document.close(); }
    }
}

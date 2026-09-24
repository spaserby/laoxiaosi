package com.law.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话附件服务
 * <p>
 * <b>四个工程坑的防御</b>：
 * <ul>
 *   <li><b>图文对应</b>：抽图记录位置（docx 段落号 / pdf 页码），文本中插入
 *       {@code [此处有图片N：位置]} 标记，模型据此建立图文关联</li>
 *   <li><b>扫描版 PDF</b>：页文本 &lt; 50 字且含大图 → 判定扫描页，页图直接进 VL 模型
 *       （多模态即 OCR，不引 Tesseract）</li>
 *   <li><b>token 爆炸</b>：尺寸/纯色过滤 + 单文档 ≤6 图 + 单轮 ≤8 图 + PDF ≤30 页 + 单轮图片总≤32MB</li>
 *   <li><b>caption 成本</b>：每文档限 3 张 + 并行 + 单张 8s 超时降级空 caption</li>
 * </ul>
 * <b>存储</b>：文本与图片元数据在 {@code chat:file:{id}}；图片 base64 在子键
 * {@code chat:file:{id}:img:{i}}（记忆只存 caption 占位，原图仅当前轮/按需展开进模型）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(FileProperties.class)
public class ChatFileService {

    private static final String FILE_KEY = "chat:file:";
    /** 单文件 ≤100MB（解析侧仍有 ≤30 页/≤2 万字闸） */
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    /** 对标 DeepSeek 视觉 API 内联通道：单图 ≤32MiB */
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    /** 单轮图片总字节闸：图片 base64 暂存 Redis，防多张大图撑爆内存态存储 */
    private static final long MAX_TURN_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_FILES = 3;
    public static final int MAX_CHARS = 20_000;
    private static final List<String> TEXT_EXTS = List.of("txt", "md", "pdf", "docx");
    private static final List<String> IMAGE_EXTS = List.of("png", "jpg", "jpeg", "webp");

    private final RedissonClient redissonClient;
    private final FileProperties properties;
    private final ImageCaptionService captionService;

    /** 上传原始载荷（WebFlux FilePart 收流后转 bytes，与 Servlet MultipartFile 解耦） */
    public record FilePayload(String name, byte[] bytes) {
    }

    /** 抽出的原始图（过滤后、存 Redis 前） */
    public record RawImage(byte[] bytes, String mime, String location) {
    }

    /** 存储后的图片元数据（前端 chip / 消息卡 / 模型装配共用） */
    public record ImgMeta(int idx, String location, String caption) {
    }

    /** 上传结果 */
    public record UploadedFile(String fileId, String name, int chars, boolean truncated,
                               String kind, List<ImgMeta> images) {
    }

    /** 文档暂存结果：剩余单轮图片张数预算 + 本轮已收图片字节数 */
    private record DocStoreResult(int remaining, long imageBytes) {
    }

    /** Redis 中的图片实体（模型装配/按需展开用） */
    public record StoredImage(int idx, String mime, byte[] bytes, String location, String caption) {
    }

    /**
     * 校验 + 解析 + 抽图 + caption + 暂存
     */
    public List<UploadedFile> store(List<FilePayload> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("未选择文件");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("单次最多上传 " + MAX_FILES + " 个文件");
        }
        List<UploadedFile> result = new ArrayList<>();
        int turnImageBudget = properties.getMaxImagesPerTurn();
        long turnImageBytes = 0;
        for (FilePayload f : files) {
            String name = f.name() == null ? "未命名" : f.name();
            String ext = extOf(name);
            boolean isImage = IMAGE_EXTS.contains(ext);
            long sizeLimit = isImage ? MAX_IMAGE_BYTES : MAX_BYTES;
            if (f.bytes().length > sizeLimit) {
                throw new IllegalArgumentException("文件「" + name + "」超过 " + (sizeLimit / 1024 / 1024) + "MB 限制");
            }
            if (!isImage && !TEXT_EXTS.contains(ext)) {
                throw new IllegalArgumentException("暂不支持「" + ext + "」格式，支持 txt/md/pdf/docx/图片");
            }
            try {
                if (isImage) {
                    turnImageBytes += f.bytes().length;
                    checkTurnBytes(turnImageBytes);
                    storePureImage(f.bytes(), ext, name, result);
                    turnImageBudget -= 1;
                } else {
                    DocStoreResult r = storeDocument(f.bytes(), ext, name, result, turnImageBudget);
                    turnImageBudget = r.remaining();
                    turnImageBytes += r.imageBytes();
                    checkTurnBytes(turnImageBytes);
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                log.warn("附件解析失败: name={}, error={}", name, e.getMessage());
                throw new IllegalArgumentException("文件「" + name + "」解析失败：内容可能已损坏");
            }
            if (turnImageBudget < 0) {
                throw new IllegalArgumentException("单轮图片总数超过 " + properties.getMaxImagesPerTurn() + " 张限制");
            }
        }
        return result;
    }

    /** 单轮图片总字节闸（Redis base64 暂存成本保护） */
    private void checkTurnBytes(long turnImageBytes) {
        if (turnImageBytes > MAX_TURN_IMAGE_BYTES) {
            throw new IllegalArgumentException("单轮图片总大小超过 " + (MAX_TURN_IMAGE_BYTES / 1024 / 1024) + "MB 限制");
        }
    }

    /* ---------- 纯图片 ---------- */

    private void storePureImage(byte[] bytes, String ext, String name, List<UploadedFile> out) throws Exception {
        BufferedImage img = safeDecode(bytes);
        // 本地可解码 → 噪声过滤；解不开但魔法字节合法（CMYK JPEG/WebP 等 ImageIO 不支持但
        // VL 模型支持的格式）→ 直通 VL，跳过本地过滤
        if (img != null && !meaningful(img)) {
            throw new IllegalArgumentException("图片「" + name + "」过小或无内容，已拒绝");
        }
        if (img == null && !knownImageMagic(bytes)) {
            throw new IllegalArgumentException("文件「" + name + "」不是可识别的图片");
        }
        String mime = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
        String location = "用户上传";
        String caption = captionAsync(List.of(new RawImage(bytes, mime, location)))
                .stream().findFirst().orElse("");
        String fileId = newFileId();
        RMap<String, String> bucket = redissonClient.getMap(FILE_KEY + fileId);
        bucket.put("kind", "image");
        bucket.put("name", name);
        bucket.put("text", "");
        bucket.put("imgcount", "1");
        bucket.expire(Duration.ofHours(24));
        writeImage(fileId, 1, bytes, mime, location, caption);
        out.add(new UploadedFile(fileId, name, 0, false, "image", List.of(new ImgMeta(1, location, caption))));
        log.info("图片附件暂存: fileId={}, name={}, caption={}", fileId, name, caption);
    }

    /* ---------- 文档（文本 + 抽图） ---------- */

    private DocStoreResult storeDocument(byte[] bytes, String ext, String name,
                              List<UploadedFile> out, int turnImageBudget) throws Exception {
        String text;
        List<RawImage> images = new ArrayList<>();
        boolean scanned = false;
        switch (ext) {
            case "txt", "md" -> text = parsePlain(bytes);
            case "docx" -> {
                var parsed = parseDocx(bytes);
                text = parsed.text();
                images.addAll(parsed.images());
            }
            case "pdf" -> {
                var parsed = parsePdf(bytes);
                text = parsed.text();
                images.addAll(parsed.images());
                scanned = parsed.scanned();
            }
            default -> throw new IllegalArgumentException("不支持的格式: " + ext);
        }
        // 单文档图片限额（过滤后截取）
        if (images.size() > properties.getMaxImagesPerDoc()) {
            images = new ArrayList<>(images.subList(0, properties.getMaxImagesPerDoc()));
        }
        // 单轮图片总额度
        if (images.size() > turnImageBudget) {
            images = new ArrayList<>(images.subList(0, Math.max(turnImageBudget, 0)));
        }
        if (text.isBlank() && images.isEmpty()) {
            throw new IllegalArgumentException("文件「" + name + "」未解析出文本或图片（扫描件需为清晰图片型）");
        }
        // 图文标记已内嵌 text（[此处有图片N：位置]）；caption 并行限流
        List<String> captions = captionAsync(images);
        String fileId = newFileId();
        RMap<String, String> bucket = redissonClient.getMap(FILE_KEY + fileId);
        boolean truncated = text.length() > MAX_CHARS;
        bucket.put("kind", scanned ? "scanned" : (images.isEmpty() ? "text" : "mixed"));
        bucket.put("name", name);
        bucket.put("text", truncated ? text.substring(0, MAX_CHARS) : text);
        bucket.put("truncated", truncated ? "1" : "0");
        bucket.put("imgcount", String.valueOf(images.size()));
        bucket.expire(Duration.ofHours(24));
        List<ImgMeta> metas = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            RawImage ri = images.get(i);
            String caption = i < captions.size() ? captions.get(i) : "";
            writeImage(fileId, i + 1, ri.bytes(), ri.mime(), ri.location(), caption);
            metas.add(new ImgMeta(i + 1, ri.location(), caption));
        }
        out.add(new UploadedFile(fileId, name, text.length(), truncated,
                scanned ? "scanned" : (images.isEmpty() ? "text" : "mixed"), metas));
        log.info("文档附件暂存: fileId={}, name={}, chars={}, images={}, scanned={}",
                fileId, name, text.length(), images.size(), scanned);
        long imageBytes = images.stream().mapToLong(ri -> (long) ri.bytes().length).sum();
        return new DocStoreResult(turnImageBudget - images.size(), imageBytes);
    }

    /* ---------- 解析：docx（段落级位置） ---------- */

    private record ParsedDoc(String text, List<RawImage> images, boolean scanned) {
    }

    private ParsedDoc parseDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        List<RawImage> images = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paras = doc.getParagraphs();
            for (int pi = 0; pi < paras.size(); pi++) {
                XWPFParagraph p = paras.get(pi);
                sb.append(p.getText()).append('\n');
                for (XWPFRun run : p.getRuns()) {
                    for (XWPFPicture pic : run.getEmbeddedPictures()) {
                        byte[] data = pic.getPictureData().getData();
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                        if (img == null || !meaningful(img)) continue;   // 噪声过滤
                        String location = "docx 第" + (pi + 1) + "段";
                        images.add(new RawImage(data, contentTypeOf(pic), location));
                        // 图文对应标记：模型看到 图片N 即此位置
                        sb.append("[此处有图片").append(images.size()).append("：").append(location).append("]\n");
                    }
                }
            }
        }
        return new ParsedDoc(sb.toString(), images, false);
    }

    private String contentTypeOf(XWPFPicture pic) {
        try {
            // POI 的 getPictureType() 返回 int 常量，用 suggestFileExtension 取扩展名更直接
            String ext = pic.getPictureData().suggestFileExtension();
            return "image/" + (ext == null || ext.isBlank() ? "png" : ext);
        } catch (Exception e) {
            return "image/png";
        }
    }

    /* ---------- 解析：pdf（页级位置 + 扫描版检测） ---------- */

    private ParsedDoc parsePdf(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        List<RawImage> images = new ArrayList<>();
        int scannedPages = 0, textPages = 0;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = Math.min(doc.getNumberOfPages(), properties.getMaxPages());
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc);
                List<RawImage> pageImages = extractPageImages(doc, p);
                boolean scannedPage = pageText.trim().length() < 50 && !pageImages.isEmpty();
                if (scannedPage) {
                    scannedPages++;
                    // 扫描页：页图即内容，VL 模型直读（多模态即 OCR）
                    for (RawImage ri : pageImages) {
                        images.add(new RawImage(ri.bytes(), ri.mime(), "第" + p + "页（扫描页）"));
                        sb.append("[此处有图片").append(images.size()).append("：第").append(p)
                                .append("页扫描页，请直接读图]\n");
                    }
                } else {
                    if (!pageText.isBlank()) textPages++;
                    sb.append(pageText);
                    for (RawImage ri : pageImages) {
                        images.add(new RawImage(ri.bytes(), ri.mime(), "第" + p + "页"));
                        sb.append("[此处有图片").append(images.size()).append("：第").append(p).append("页]\n");
                    }
                }
            }
        }
        return new ParsedDoc(sb.toString(), images, scannedPages > 0 && scannedPages >= textPages);
    }

    private List<RawImage> extractPageImages(PDDocument doc, int pageNo) throws Exception {
        List<RawImage> out = new ArrayList<>();
        PDPage page = doc.getPage(pageNo - 1);
        PDResources res = page.getResources();
        if (res == null) return out;
        for (COSName name : res.getXObjectNames()) {
            if (!res.isImageXObject(name)) continue;
            PDImageXObject ximg = (PDImageXObject) res.getXObject(name);
            BufferedImage img = ximg.getImage();
            if (img == null || !meaningful(img)) continue;   // 尺寸/纯色过滤
            String mime = ximg.getSuffix() == null ? "image/png" : "image/" + ximg.getSuffix();
            out.add(new RawImage(imageBytes(img, mime), mime, "第" + pageNo + "页"));
        }
        return out;
    }

    private byte[] imageBytes(BufferedImage img, String mime) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        String fmt = mime.contains("jpeg") ? "jpg" : (mime.contains("webp") ? "png" : mime.substring(6));
        ImageIO.write(img, fmt.equals("jpg") ? "jpg" : "png", bos);
        return bos.toByteArray();
    }

    /** ImageIO 解码失败不抛异常（返回 null 交由魔法字节判定） */
    private BufferedImage safeDecode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /** 魔法字节白名单：JPEG/PNG/GIF/WebP/BMP——ImageIO 解不开但 VL 模型能吃的格式放行 */
    private static boolean knownImageMagic(byte[] b) {
        if (b == null || b.length < 12) return false;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return true;                 // JPEG（含 CMYK）
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;  // PNG
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return true;                        // GIF
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true; // WebP
        return b[0] == 'B' && b[1] == 'M';                                                // BMP
    }

    /* ---------- 图片过滤（尺寸 + 纯色） ---------- */

    /** 包级可见供金标测试：过小或纯色无意义图返回 false */
    public boolean meaningful(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w < properties.getMinImagePx() || h < properties.getMinImagePx()) return false;
        // 5×5 网格 × 3×3 邻域采样色差——单点采样会对棋盘格/细条纹图案产生
        // 混叠（采样点全落同色像素误判纯色），邻域内取极差则必能捕获内容
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int gx = 0; gx < 5; gx++) {
            for (int gy = 0; gy < 5; gy++) {
                int cx = Math.min(w - 1, gx * w / 5 + w / 10);
                int cy = Math.min(h - 1, gy * h / 5 + h / 10);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int x = Math.max(0, Math.min(w - 1, cx + dx));
                        int y = Math.max(0, Math.min(h - 1, cy + dy));
                        int rgb = img.getRGB(x, y);
                        int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                        min = Math.min(min, sum);
                        max = Math.max(max, sum);
                    }
                }
            }
        }
        return max - min >= 36;   // 单通道平均差 < 12 视为纯色
    }

    /* ---------- caption 并行限流 ---------- */

    private List<String> captionAsync(List<RawImage> images) {
        List<String> captions = new ArrayList<>();
        if (images.isEmpty()) return captions;
        int limit = Math.min(images.size(), properties.getCaptionLimit());
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            RawImage ri = images.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> captionService.caption(ri.bytes(), ri.mime())));
        }
        for (CompletableFuture<String> fu : futures) {
            try {
                captions.add(fu.get(properties.getCaptionTimeoutSeconds(), TimeUnit.SECONDS));
            } catch (Exception e) {
                captions.add("");   // 超时/失败降级空 caption，不阻断上传
            }
        }
        return captions;
    }

    /* ---------- Redis 读写 ---------- */

    private String newFileId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void writeImage(String fileId, int idx, byte[] bytes, String mime, String location, String caption) {
        RMap<String, String> img = redissonClient.getMap(FILE_KEY + fileId + ":img:" + idx);
        img.put("b64", Base64.getEncoder().encodeToString(bytes));
        img.put("mime", mime);
        img.put("location", location);
        img.put("caption", caption == null ? "" : caption);
        img.expire(Duration.ofHours(24));
    }

    public String loadText(String fileId) {
        RMap<String, String> bucket = redissonClient.getMap(FILE_KEY + fileId);
        return bucket.get("text");
    }

    public String loadName(String fileId) {
        RMap<String, String> bucket = redissonClient.getMap(FILE_KEY + fileId);
        String name = bucket.get("name");
        return name == null ? "附件" : name;
    }

    /** 读取附件全部图片（当前轮装配 / 追问按需展开共用） */
    public List<StoredImage> loadImages(String fileId) {
        RMap<String, String> bucket = redissonClient.getMap(FILE_KEY + fileId);
        String countStr = bucket.get("imgcount");
        int count = countStr == null ? 0 : Integer.parseInt(countStr);
        List<StoredImage> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            RMap<String, String> img = redissonClient.getMap(FILE_KEY + fileId + ":img:" + i);
            String b64 = img.get("b64");
            if (b64 == null) continue;
            out.add(new StoredImage(i, img.get("mime"), Base64.getDecoder().decode(b64),
                    img.get("location"), img.get("caption") == null ? "" : img.get("caption")));
        }
        return out;
    }

    private String parsePlain(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        return utf8.contains("\uFFFD") ? new String(bytes, Charset.forName("GBK")) : utf8;
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}

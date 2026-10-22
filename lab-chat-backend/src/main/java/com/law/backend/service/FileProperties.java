package com.law.backend.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 私有附件解析、存储、检索、审查与全局上下文预算配置。
 */
@Data
@ConfigurationProperties(prefix = "legal.ai.files")
public class FileProperties {

    /** 单轮咨询图片总数上限（token 成本闸：qwen-vl 单图约千级 token） */
    private int maxImagesPerTurn = 8;

    /** DOCX 图片 OCR 上限（超出明确提示部分覆盖，不影响 PDF 逐页 OCR） */
    private int maxImagesPerDoc = 6;

    /** 最小有效边长 px（小于视为图标/分隔线噪声丢弃） */
    private int minImagePx = 64;

    /** PDF 接受上限（超过拒绝整份文档，不截取前缀） */
    private int maxPages = 200;

    /** 私有目录；容器部署必须挂载持久卷。目录名不来自上传文件名。 */
    private String storageDir = "./data/private-attachments";
    private long maxFileBytes = 20L * 1024 * 1024;
    private long maxImageBytes = 8L * 1024 * 1024;
    private int maxImagePixels = 16_000_000;
    private int maxTextChars = 200_000;
    private int maxFilesPerSession = 3;
    private int queueCapacity = 6;
    private int processingTimeoutSeconds = 180;
    private int ttlHours = 24;
    private boolean ocrEnabled = true;
    private int ocrMaxTokens = 8192;
    private int ocrTimeoutSeconds = 45;
    private int embeddingTimeoutSeconds = 30;
    private int childTokens = 512;
    private int overlapTokens = 128;
    private int parentTokens = 1536;
    private int recallCandidates = 24;
    private int retrievalTopK = 6;
    private int attachmentTokens = 8000;
    /** 保守的部署下限，须不大于任何可路由模型的实际窗口。 */
    private int contextWindowTokens = 32768;
    private int outputReserveTokens = 4096;
    private int safetyReserveTokens = 2048;
    private int imageReserveTokens = 8192;
    /** CL100K 是估算；chat 与 embedding 分开配置，不能声称等于供应商 tokenizer。 */
    private double chatTokenSafetyFactor = 1.5;
    private double embeddingTokenSafetyFactor = 1.25;
    private String embeddingVersion = "qwen3.7-text-embedding-flash";
    private int maxReviewSections = 64;
    private int reviewOutputTokens = 600;

    /** 多模态模型名（需支持视觉输入；当前 deepseek-v4-flash，备选 qwen-vl-max） */
    private String vlModel = "deepseek-v4-flash";

    /** 多模态模型所属 provider（providers _map 键；换模型只改 vl-model + vl-provider 两项） */
    private String vlProvider = "deepseek";
}

package com.law.backend.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "legal.ai.contract-scan")
public class ContractScanProperties {
    private boolean enabled = true;
    private int maxPhotos = 50;
    private int maxPdfPages = 200;
    private long maxBytes = 100L * 1024 * 1024;
    private long maxPhotoBytes = 20L * 1024 * 1024;
    private long maxPhotoPixels = 50_000_000;
    private long storageBytes = 1024L * 1024 * 1024;
    private int maxJobs = 64;
    private int maxQueued = 8;
    private int maxUploads = 4;
    private int retentionHours = 24;
    private int draftHours = 2;
    private int dailyOcrPages = 600;
    private int dailyReviewCalls = 800;
    private String ocrRecipient = "";
    private String reviewRecipient = "";
}

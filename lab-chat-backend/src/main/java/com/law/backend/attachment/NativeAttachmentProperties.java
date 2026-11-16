package com.law.backend.attachment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ConfigurationProperties("legal.ai.files.native")
public class NativeAttachmentProperties {
    private Map<String, Target> targets = new LinkedHashMap<>();

    public enum Protocol { DEEPSEEK_FILES, OPENAI_RESPONSES }

    @Data
    public static class Target {
        private boolean enabled;
        private Protocol protocol;
        private String provider;
        private String label;
        private String model;
        private long maxBytes = 8 * 1024 * 1024L;
        private int maxPdfPages = 30;
    }
}

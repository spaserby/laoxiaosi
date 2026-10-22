package com.law.backend.attachment;

import java.util.List;

/** 检索命中与覆盖信息一起返回；引用原文通过受鉴权的 source 接口读取。 */
public record AttachmentContext(String text, List<Source> sources, List<String> warnings,
                                int selectedSections, int totalSections, boolean fullCoverage) {
    public record Source(String fileId, String name, int parentId, String location, int start, int end, String label) {}
    public static AttachmentContext empty() { return new AttachmentContext("", List.of(), List.of(), 0, 0, false); }
}

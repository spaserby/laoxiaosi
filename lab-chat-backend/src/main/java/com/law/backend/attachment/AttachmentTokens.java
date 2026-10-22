package com.law.backend.attachment;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.law.backend.service.FileProperties;
import org.springframework.stereotype.Component;

/** 两套独立预算的保守估算器；供应商 tokenizer 未公开时不宣称精确计数。 */
@Component
public class AttachmentTokens {
    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private static final java.util.regex.Pattern UNPAIRED = java.util.regex.Pattern.compile("[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]");
    private final FileProperties properties;

    public AttachmentTokens(FileProperties properties) { this.properties = properties; }
    public int chat(String text) { return count(text, properties.getChatTokenSafetyFactor()); }
    public int embedding(String text) { return count(text, properties.getEmbeddingTokenSafetyFactor()); }
    private int count(String text, double factor) {
        // 二分搜索的试探子串可能暂时停在代理对中间，不能让 tokenizer 把原文写入断言错误。
        return text == null || text.isEmpty() ? 0 : (int) Math.ceil(ENCODING.countTokens(UNPAIRED.matcher(text).replaceAll("\uFFFD")) * Math.max(1, factor));
    }
    /** 返回字符边界；不在 UTF-16 代理对之间切分。 */
    public int prefix(String text, int budget, boolean forEmbedding) {
        int lo = 0, hi = Math.min(text.length(), Math.max(8, budget * 4));
        while (hi < text.length() && (forEmbedding ? embedding(text.substring(0, hi)) : chat(text.substring(0, hi))) <= budget)
            hi = Math.min(text.length(), hi * 2);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            int count = forEmbedding ? embedding(text.substring(0, mid)) : chat(text.substring(0, mid));
            if (count <= budget) lo = mid; else hi = mid - 1;
        }
        if (lo > 0 && lo < text.length() && Character.isLowSurrogate(text.charAt(lo))) lo--;
        return lo;
    }
    public int suffixStart(String text, int budget) {
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (embedding(text.substring(text.length() - mid)) <= budget) lo = mid; else hi = mid - 1;
        }
        int start = text.length() - lo;
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        return start;
    }
}

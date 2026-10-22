package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentChunkerTest {
    final FileProperties properties = new FileProperties();
    final AttachmentTokens tokens = new AttachmentTokens(properties);
    final AttachmentChunker chunker = new AttachmentChunker(properties, tokens);
    @Test void parentsPreserveEveryCharacterAndChildrenRespectEmbeddingBudget() {
        String text = "第一条 付款义务\n" + "付款须在验收之后完成，但质量不合格的除外。\n".repeat(400) + "第二条 责任例外\n除非另行约定。";
        var result = chunker.split(text, List.of(new AttachmentChunker.Source(0, text.length(), "第1页")));
        assertEquals(text, String.join("", result.parents().stream().map(AttachmentChunker.Parent::text).toList()));
        assertTrue(result.parents().size() > 2);
        for (var child : result.children()) {
            assertEquals(text.substring(child.start(), child.end()), child.text());
            assertTrue(tokens.embedding(child.source() + "\n" + child.text()) <= properties.getChildTokens());
            assertTrue(child.text().contains("付款") || child.text().contains("例外") || child.text().contains("约定"));
        }
        assertTrue(result.children().stream().anyMatch(c -> c.source().contains("第二条")));
    }
    @Test void sentenceSplitsHaveNoOverlapAndHardSplitsHaveOverlap() {
        String sentences = "This is a synthetic legal provision with an exception.\n".repeat(90);
        var soft = chunker.split(sentences, List.of());
        for (int i = 1; i < soft.children().size(); i++) {
            var a = soft.children().get(i - 1); var b = soft.children().get(i);
            if (a.parentId() == b.parentId()) assertEquals(a.end(), b.start());
        }
        String hardText = "不可抗力abcdef0123456789".repeat(500);
        var hard = chunker.split(hardText, List.of());
        boolean overlap = false;
        for (int i = 1; i < hard.children().size(); i++) {
            var a = hard.children().get(i - 1); var b = hard.children().get(i);
            if (a.parentId() == b.parentId()) {
                assertTrue(b.start() < a.end()); assertTrue(b.start() > a.start()); overlap = true;
            }
        }
        assertTrue(overlap);
    }
    @Test void doesNotSplitUnicodeSurrogatePairs() {
        String text = "合成资料😀".repeat(1000);
        for (var child : chunker.split(text, List.of()).children()) {
            assertFalse(Character.isLowSurrogate(child.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(child.text().charAt(child.text().length() - 1)));
        }
    }
}

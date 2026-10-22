package com.law.backend.attachment;

import com.law.backend.rag.*;
import com.law.backend.service.FileProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentRetrievalTest {
    final FileProperties properties = new FileProperties();
    final AttachmentTokens tokens = new AttachmentTokens(properties);
    final AttachmentRepository repository = mock(AttachmentRepository.class);
    final AttachmentEmbedding embedding = mock(AttachmentEmbedding.class);
    final RerankService reranker = mock(RerankService.class);
    final RagProperties rag = new RagProperties();
    final AttachmentRepository.Scope scope = new AttachmentRepository.Scope("synthetic-session", "u:101", 1000);
    AttachmentRepository.FileRecord file(String id) {
        return new AttachmentRepository.FileRecord(id, id + ".txt", "txt", "READY", "text", 100000, 1, 1, List.of(), List.of(), "", 10000, 100000);
    }
    AttachmentRetrieval retrieval() { rag.setRerankEnabled(false); return new AttachmentRetrieval(repository, embedding, reranker, rag, properties, tokens); }
    @Test void shortDocumentsUseFullTextAndOneCombinedBudget() {
        var rows = List.of(new AttachmentRepository.ParentRow("a", 0, 0, 12, "第1条", "付款须经验收。"),
                new AttachmentRepository.ParentRow("b", 0, 0, 12, "第1条", "不合格除外。"));
        when(repository.parents(scope, List.of("a", "b"))).thenReturn(rows);
        var result = retrieval().retrieve(scope, List.of(file("a"), file("b")), "付款条件", 500);
        assertTrue(result.fullCoverage()); assertEquals(2, result.sources().size()); assertTrue(tokens.chat(result.text()) <= 500);
        verifyNoInteractions(embedding, reranker);
    }
    @Test void retrievesTailAfterTwentyThousandCharactersAndExpandsAdjacentException() {
        var filler = new AttachmentRepository.ParentRow("a", 0, 0, 25000, "开头", "无关背景。".repeat(6000));
        var condition = new AttachmentRepository.ParentRow("a", 1, 25000, 25030, "第三十条", "保修期限为七年，自验收起算。");
        var exception = new AttachmentRepository.ParentRow("a", 2, 25030, 25060, "第三十一条", "但人为损坏的，不适用前条保修。");
        when(repository.parents(scope, List.of("a"))).thenReturn(List.of(filler, condition, exception));
        when(repository.children(scope, List.of("a"))).thenReturn(List.of(new AttachmentRepository.Hit("a", 1, 1, 25000, 25030, "第三十条", condition.text(), 0)));
        when(embedding.query(anyString())).thenThrow(new IllegalStateException());
        var result = retrieval().retrieve(scope, List.of(file("a")), "保修期限与例外", 600);
        assertTrue(result.text().contains("保修期限为七年")); assertTrue(result.text().contains("人为损坏"));
        assertTrue(result.sources().stream().anyMatch(s -> s.start() >= 25000)); assertFalse(result.fullCoverage());
        assertTrue(tokens.chat(result.text()) <= 600);
    }
    @Test void zeroRelevantResultsAreExplicitAndNotFilledWithUnrelatedTopK() {
        when(repository.parents(scope, List.of("a"))).thenReturn(List.of(new AttachmentRepository.ParentRow("a", 0, 0, 100000, "正文", "无关背景。".repeat(6000))));
        when(repository.children(scope, List.of("a"))).thenReturn(List.of(new AttachmentRepository.Hit("a", 0, 0, 0, 30, "正文", "合成背景记录", 0)));
        when(embedding.query(anyString())).thenThrow(new IllegalStateException());
        var result = retrieval().retrieve(scope, List.of(file("a")), "解除劳动关系的经济补偿", 600);
        assertTrue(result.sources().isEmpty()); assertTrue(result.warnings().stream().anyMatch(w -> w.contains("不能据此断言")));
    }
    @Test void denseSqlFailureDoesNotRetryWithoutPermissionFilters() {
        when(repository.parents(scope, List.of("a"))).thenReturn(List.of(new AttachmentRepository.ParentRow("a", 0, 0, 100000, "正文", "无关背景。".repeat(6000))));
        when(repository.children(scope, List.of("a"))).thenReturn(List.of());
        when(embedding.query(anyString())).thenReturn(new float[]{1, 0});
        when(repository.dense(eq(scope), eq(List.of("a")), any(), anyInt(), anyString())).thenThrow(new IllegalStateException("synthetic filter failure"));
        assertThrows(IllegalStateException.class, () -> retrieval().retrieve(scope, List.of(file("a")), "付款", 600));
        verify(repository, times(1)).dense(eq(scope), eq(List.of("a")), any(), anyInt(), anyString());
    }
}

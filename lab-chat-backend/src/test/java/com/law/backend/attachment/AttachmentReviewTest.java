package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttachmentReviewTest {
    final FileProperties properties = new FileProperties();
    final AttachmentTokens tokens = new AttachmentTokens(properties);
    final AttachmentRepository repository = mock(AttachmentRepository.class);
    final AttachmentReviewModel model = mock(AttachmentReviewModel.class);
    final AttachmentRepository.Scope scope = new AttachmentRepository.Scope("synthetic-session", "u:101", 1000);
    AttachmentReview review() { return new AttachmentReview(repository, model, tokens, properties); }
    AttachmentRepository.FileRecord file(String status) {
        return new AttachmentRepository.FileRecord("a", "synthetic.txt", "txt", status, "text", 40000, 31,
                "PARTIAL".equals(status) ? 30 : 31, "PARTIAL".equals(status) ? List.of(12) : List.of(),
                "PARTIAL".equals(status) ? List.of("第12页 OCR 未完成") : List.of(), "", Long.MAX_VALUE, 40000);
    }
    List<AttachmentRepository.ParentRow> parents(int count) {
        return IntStream.range(0, count).mapToObj(i -> new AttachmentRepository.ParentRow("a", i, i * 1000, (i + 1) * 1000, "第" + (i + 1) + "节", "BODY_" + i + " 条款与例外")).toList();
    }
    @Test void visitsEverySectionIncludingTailInsteadOfTopK() {
        var rows = parents(12); when(repository.parents(scope, List.of("a"))).thenReturn(rows);
        when(model.inspect(anyString(), anyString(), eq(false))).thenReturn("已检查，无明显异常。");
        var result = review().review(scope, List.of(file("READY")), "全面审查", 2000, stage -> {}, () -> false);
        assertEquals(12, result.selectedSections()); assertEquals(12, result.totalSections()); assertTrue(result.fullCoverage());
        for (var parent : rows) verify(model).inspect(eq("全面审查"), contains(parent.text()), eq(false));
    }
    @Test void hierarchicalReductionIncludesEveryCompletedSection() {
        when(repository.parents(scope, List.of("a"))).thenReturn(parents(20));
        when(model.inspect(anyString(), anyString(), eq(false))).thenAnswer(inv -> "风险与证据 ".repeat(80));
        when(model.inspect(anyString(), anyString(), eq(true))).thenReturn("合并保留风险、条件、例外及证据定位。");
        var result = review().review(scope, List.of(file("READY")), "全面审查", 400, stage -> {}, () -> false);
        assertEquals(20, result.selectedSections()); assertTrue(result.fullCoverage()); assertTrue(tokens.chat(result.text()) <= 400);
        verify(model, times(20)).inspect(anyString(), anyString(), eq(false)); verify(model, atLeastOnce()).inspect(anyString(), anyString(), eq(true));
    }
    @Test void modelFailureAndOcrGapsCannotClaimFullCoverage() {
        when(repository.parents(scope, List.of("a"))).thenReturn(parents(3));
        when(model.inspect(anyString(), anyString(), eq(false))).thenReturn("已检查").thenThrow(new IllegalStateException()).thenReturn("已检查");
        var result = review().review(scope, List.of(file("PARTIAL")), "全面审查", 1000, stage -> {}, () -> false);
        assertEquals(2, result.selectedSections()); assertEquals(3, result.totalSections()); assertFalse(result.fullCoverage());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未完成")));
    }
    @Test void stopsBeforeCallingModelWhenCancelledOrOverSectionLimit() {
        when(repository.parents(scope, List.of("a"))).thenReturn(parents(1));
        assertThrows(IllegalArgumentException.class, () -> review().review(scope, List.of(file("READY")), "审查", 1000, stage -> {}, () -> true));
        properties.setMaxReviewSections(1); when(repository.parents(scope, List.of("a"))).thenReturn(parents(2));
        assertThrows(IllegalArgumentException.class, () -> review().review(scope, List.of(file("READY")), "审查", 1000, stage -> {}, () -> false));
        verifyNoInteractions(model);
    }
}

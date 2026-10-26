package com.law.backend.attachment;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentQualityMetricsTest {
    private final Map<String, String> docs = Map.of("a", "主规则：七日退还。但未结费用须先抵扣。");
    private AttachmentContext.Source source(String file, int start, int end) {
        return new AttachmentContext.Source(file, "synthetic", 0, "正文", start, end, "P1");
    }
    private AttachmentContext context(String text, AttachmentContext.Source... sources) {
        return new AttachmentContext(text, List.of(sources), List.of(), sources.length, 2, false);
    }
    private List<AttachmentQualityMetrics.Evidence> gold() {
        return List.of(new AttachmentQualityMetrics.Evidence("rule", "a", 0, 9, docs.get("a").substring(0, 9)),
                new AttachmentQualityMetrics.Evidence("exception", "a", 9, docs.get("a").length(), docs.get("a").substring(9)));
    }
    @Test void missingExceptionMustFailAllEvidenceEvenWhenRuleIsPresent() {
        var score = AttachmentQualityMetrics.score(context(docs.get("a").substring(0, 9), source("a", 0, 9)), gold(), docs);
        assertEquals(0.5, score.recall()); assertFalse(score.allRequired());
    }
    @Test void duplicatedChunksCannotIncreaseTheEvidenceDenominatorOrNumerator() {
        String text = docs.get("a");
        var score = AttachmentQualityMetrics.score(context(text + text, source("a", 0, text.length()), source("a", 0, text.length())), gold(), docs);
        assertEquals(2, score.required()); assertEquals(2, score.covered());
        assertEquals(text.length(), score.evidenceChars());
    }
    @Test void sourceMetadataWithoutActualTextDoesNotCountAsEvidence() {
        var score = AttachmentQualityMetrics.score(context("未提供原文", source("a", 0, docs.get("a").length())), gold(), docs);
        assertEquals(0, score.covered()); assertFalse(score.sourceLocationsValid());
    }
    @Test void noAnswerIsExcludedRatherThanGivenPerfectRecall() {
        var score = AttachmentQualityMetrics.score(context(""), List.of(), docs);
        assertNull(score.recall()); assertFalse(score.allRequired());
    }
    @Test void adjacentActualFragmentsCanJointlyCoverOneStableEvidenceUnit() {
        String text = docs.get("a");
        var score = AttachmentQualityMetrics.score(context(text.substring(0, 5) + "[next]" + text.substring(5),
                source("a", 0, 5), source("a", 5, text.length())), gold(), docs);
        assertTrue(score.allRequired()); assertTrue(score.sourceLocationsValid());
    }
}

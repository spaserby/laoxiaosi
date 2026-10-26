package com.law.backend.attachment;

import java.util.*;

/** Stable original-text evidence units; chunk IDs are deliberately not gold labels. */
final class AttachmentQualityMetrics {
    record Evidence(String id, String fileId, int start, int end, String text) {}
    record Score(int required, int covered, boolean allRequired, int contextChars,
                 int evidenceChars, boolean sourceLocationsValid) {
        Double recall() { return required == 0 ? null : (double) covered / required; }
    }

    static Score score(AttachmentContext context, List<Evidence> gold, Map<String, String> documents) {
        int covered = 0, evidenceChars = 0;
        boolean valid = true;
        for (var source : context.sources()) {
            String original = documents.get(source.fileId());
            valid &= original != null && source.start() >= 0 && source.end() <= original.length()
                    && source.end() > source.start()
                    && context.text().contains(original.substring(source.start(), source.end()));
        }
        for (var evidence : gold) {
            List<int[]> intervals = new ArrayList<>();
            for (var source : context.sources()) {
                if (!source.fileId().equals(evidence.fileId())) continue;
                int start = Math.max(source.start(), evidence.start());
                int end = Math.min(source.end(), evidence.end());
                if (end > start && context.text().contains(documents.get(evidence.fileId()).substring(start, end)))
                    intervals.add(new int[]{start, end});
            }
            intervals.sort(Comparator.comparingInt(a -> a[0]));
            int cursor = evidence.start();
            for (int[] range : intervals) {
                if (range[0] > cursor) break;
                cursor = Math.max(cursor, range[1]);
            }
            if (cursor >= evidence.end()) { covered++; evidenceChars += evidence.end() - evidence.start(); }
        }
        return new Score(gold.size(), covered, !gold.isEmpty() && covered == gold.size(),
                context.text().length(), evidenceChars, valid);
    }
}

package com.law.backend.attachment;

import com.law.backend.rag.RagProperties;
import com.law.backend.rag.RerankService;
import com.law.backend.service.FileProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 私有附件混合召回。所有候选先在数据库中限定 owner + session + expiry，再做融合/精排。 */
@Component
public class AttachmentRetrieval {
    private final AttachmentRepository repository;
    private final AttachmentEmbedding embedding;
    private final RerankService reranker;
    private final RagProperties rag;
    private final FileProperties properties;
    private final AttachmentTokens tokens;
    public AttachmentRetrieval(AttachmentRepository repository, AttachmentEmbedding embedding, RerankService reranker,
                               RagProperties rag, FileProperties properties, AttachmentTokens tokens) {
        this.repository = repository; this.embedding = embedding; this.reranker = reranker;
        this.rag = rag; this.properties = properties; this.tokens = tokens;
    }
    public AttachmentContext retrieve(AttachmentRepository.Scope scope, List<AttachmentRepository.FileRecord> files,
                                      String question, int budget) {
        if (files.isEmpty()) return AttachmentContext.empty();
        var ids = files.stream().map(AttachmentRepository.FileRecord::fileId).toList();
        var parents = repository.parents(scope, ids);
        List<String> warnings = files.stream().flatMap(f -> f.warnings().stream()).distinct().collect(Collectors.toCollection(ArrayList::new));
        Map<String, String> names = files.stream().collect(Collectors.toMap(AttachmentRepository.FileRecord::fileId, AttachmentRepository.FileRecord::name, (a, b) -> a, LinkedHashMap::new));
        Selection full = new Selection(names, budget, parents.size(), warnings);
        parents.forEach(full::add);
        if (full.rows.size() == parents.size() && !parents.isEmpty()) return full.result(files.stream().allMatch(f -> "READY".equals(f.status())));

        var children = repository.children(scope, ids);
        Set<String> terms = terms(question);
        List<AttachmentRepository.Hit> sparse = children.stream()
                .map(h -> new AttachmentRepository.Hit(h.fileId(), h.chunkId(), h.parentId(), h.start(), h.end(), h.source(), h.text(), lexical(terms, h.source() + "\n" + h.text())))
                .filter(h -> h.score() > 0).sorted(Comparator.comparingDouble(AttachmentRepository.Hit::score).reversed())
                .limit(properties.getRecallCandidates()).toList();
        List<AttachmentRepository.Hit> dense = List.of();
        float[] vector = null;
        try { vector = embedding.query(question); }
        catch (Exception e) { warnings.add("向量检索暂不可用，本轮使用授权附件中的关键词检索"); }
        // 数据库/授权 SQL 失败直接向上抛出，不能删过滤条件重试。
        if (vector != null) dense = repository.dense(scope, ids, vector, properties.getRecallCandidates(), properties.getEmbeddingVersion())
                .stream().filter(h -> h.score() >= 0.45).toList();
        Map<String, Double> scores = new HashMap<>();
        Map<String, AttachmentRepository.Hit> hits = new LinkedHashMap<>();
        for (var route : List.of(sparse, dense)) for (int i = 0; i < route.size(); i++) {
            var h = route.get(i); String key = h.fileId() + ":" + h.chunkId();
            scores.merge(key, 1.0 / (60 + i + 1), Double::sum); hits.put(key, h);
        }
        List<AttachmentRepository.Hit> ranked = scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> hits.get(e.getKey())).limit(properties.getRecallCandidates()).toList();
        if (rag.isRerankEnabled() && !ranked.isEmpty()) {
            try {
                var candidates = ranked;
                ranked = reranker.rerank(question, candidates.stream().map(h -> h.source() + "\n" + h.text()).toList(), properties.getRetrievalTopK())
                        .stream().filter(h -> h.index() >= 0 && h.index() < candidates.size() && h.score() >= 0.05)
                        .map(h -> candidates.get(h.index())).toList();
            } catch (Exception e) { warnings.add("精排暂不可用，本轮使用混合召回顺序"); }
        }
        ranked = ranked.stream().limit(properties.getRetrievalTopK()).toList();
        Selection selection = new Selection(names, budget, parents.size(), warnings);
        Map<String, AttachmentRepository.ParentRow> lookup = parents.stream().collect(Collectors.toMap(p -> p.fileId() + ":" + p.parentId(), p -> p));
        // 先加入各命中父块，再加入相邻块；避免第一条邻块耗尽其他文件的预算。
        for (var h : ranked) {
            var parent = lookup.get(h.fileId() + ":" + h.parentId());
            if (parent != null && !selection.add(parent)) {
                selection.add(new AttachmentRepository.ParentRow(h.fileId(), h.parentId(), h.start(), h.end(), h.source(), h.text()));
            }
        }
        for (var h : ranked) for (int delta : new int[]{-1, 1}) {
            var neighbor = lookup.get(h.fileId() + ":" + (h.parentId() + delta));
            if (neighbor != null) selection.add(neighbor);
        }
        if (selection.rows.isEmpty()) warnings.add("未找到足够相关的附件片段；不能据此断言文档中不存在相关约定");
        else warnings.add("本轮为相关片段问答，未覆盖全文；全面风险检查请使用全文审查");
        return selection.result(false);
    }
    static Set<String> terms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("请|帮我|这个|那个|文档|附件|合同|什么|如何|是否|一下|中的|关于|进行", " ");
        Set<String> result = new LinkedHashSet<>();
        var matcher = java.util.regex.Pattern.compile("[a-z0-9_]{2,}|[\\p{IsHan}]{2,}").matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < term.length() - 1; i++) result.add(term.substring(i, i + 2));
            } else result.add(term);
        }
        return result;
    }
    private double lexical(Set<String> terms, String text) {
        String input = text.toLowerCase(Locale.ROOT);
        return terms.stream().filter(input::contains).count();
    }
    private final class Selection {
        final Map<String, String> names;
        final int total;
        final List<String> warnings;
        final List<AttachmentRepository.ParentRow> rows = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final StringBuilder text = new StringBuilder();
        final List<AttachmentContext.Source> sources = new ArrayList<>();
        final int budget;
        Selection(Map<String, String> names, int budget, int total, List<String> warnings) {
            this.names = names; this.budget = budget; this.total = total; this.warnings = warnings;
        }
        boolean add(AttachmentRepository.ParentRow p) {
            String key = p.fileId() + ":" + p.parentId();
            if (seen.contains(key)) return true;
            String label = "附件" + (new ArrayList<>(names.keySet()).indexOf(p.fileId()) + 1) + "·P" + (p.parentId() + 1);
            String part = "\n[" + label + "]《" + names.get(p.fileId()) + "》 " + p.source() + "\n" + p.text() + "\n";
            if (tokens.chat(text.toString() + part) > budget) return false;
            seen.add(key); rows.add(p); text.append(part);
            sources.add(new AttachmentContext.Source(p.fileId(), names.get(p.fileId()), p.parentId(), p.source(), p.start(), p.end(), label));
            return true;
        }
        AttachmentContext result(boolean full) {
            return new AttachmentContext(text.toString(), List.copyOf(sources), List.copyOf(warnings), rows.size(), total, full);
        }
    }
}

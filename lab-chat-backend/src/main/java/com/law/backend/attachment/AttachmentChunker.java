package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 结构优先：标题/条款是父块边界，段落/句末是子块边界；仅硬切产生重叠。 */
@Component
public class AttachmentChunker {
    public static final String VERSION = "structure-v1";
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:#{1,6}\\s+.+|第[一二三四五六七八九十百千万零〇0-9]+[编章节条].*|[一二三四五六七八九十]+[、．.].{1,60})$");
    public record Source(int start, int end, String location) {}
    public record Child(int id, int parentId, int start, int end, String source, String text) {}
    public record Parent(int id, int start, int end, String source, String text) {}
    public record Chunks(List<Parent> parents, List<Child> children) {}
    private final FileProperties properties;
    private final AttachmentTokens tokens;

    public AttachmentChunker(FileProperties properties, AttachmentTokens tokens) {
        this.properties = properties; this.tokens = tokens;
    }

    public Chunks split(String text, List<Source> sources) {
        if (properties.getChildTokens() < 64 || properties.getParentTokens() < properties.getChildTokens()
                || properties.getOverlapTokens() < 0 || properties.getOverlapTokens() >= properties.getChildTokens() / 2 + 1) {
            throw new IllegalArgumentException("附件分块配置无效");
        }
        List<Integer> boundaries = new ArrayList<>(List.of(0));
        var matcher = HEADING.matcher(text);
        while (matcher.find()) if (matcher.start() > 0) boundaries.add(matcher.start());
        boundaries.add(text.length());
        List<Parent> parents = new ArrayList<>();
        List<Child> children = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            int pos = boundaries.get(i), sectionEnd = boundaries.get(i + 1);
            String heading = text.substring(pos, Math.min(sectionEnd, text.indexOf('\n', pos) < 0 ? sectionEnd : text.indexOf('\n', pos)));
            if (heading.length() > 100) heading = "正文";
            while (pos < sectionEnd) {
                int end = boundary(text, pos, sectionEnd, properties.getParentTokens());
                int parentId = parents.size();
                String source = location(sources, pos, end) + " / " + heading;
                source = source.substring(0, tokens.prefix(source, properties.getChildTokens() / 3, true));
                parents.add(new Parent(parentId, pos, end, source, text.substring(pos, end)));
                int childStart = pos;
                while (childStart < end) {
                    // 元数据标题也占 embedding 输入预算。
                    int allowance = Math.max(32, properties.getChildTokens() - tokens.embedding(source + "\n"));
                    int childEnd = boundary(text, childStart, end, allowance);
                    children.add(new Child(children.size(), parentId, childStart, childEnd, source, text.substring(childStart, childEnd)));
                    if (childEnd == end) break;
                    boolean hard = "\n。！？；.!?;".indexOf(text.charAt(childEnd - 1)) < 0;
                    int next = childEnd;
                    if (hard && properties.getOverlapTokens() > 0) {
                        String segment = text.substring(childStart, childEnd);
                        int cut = tokens.suffixStart(segment, properties.getOverlapTokens());
                        next = childStart + cut;
                    }
                    childStart = Math.max(childStart + 1, next);
                }
                pos = end;
            }
        }
        return new Chunks(List.copyOf(parents), List.copyOf(children));
    }

    private int boundary(String text, int start, int limit, int budget) {
        int end = start + tokens.prefix(text.substring(start, limit), budget, true);
        if (end == start) throw new IllegalArgumentException("附件分块预算不足");
        if (end < limit) {
            int min = start + (end - start) / 2;
            for (int i = end - 1; i >= min; i--) {
                if ("\n。！？；.!?;".indexOf(text.charAt(i)) >= 0) return i + 1;
            }
        }
        return end;
    }
    private String location(List<Source> sources, int start, int end) {
        var locations = sources.stream().filter(s -> s.end() > start && s.start() < end).map(Source::location).distinct().toList();
        if (locations.isEmpty()) return "正文";
        return locations.size() == 1 ? locations.get(0) : locations.get(0) + " 至 " + locations.get(locations.size() - 1);
    }
}

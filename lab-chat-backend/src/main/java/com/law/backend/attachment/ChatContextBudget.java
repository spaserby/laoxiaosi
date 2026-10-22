package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** 一次请求共用预算：system（含法条）、历史、用户、图片预留、输出与安全余量。 */
@Component
public class ChatContextBudget {
    public record Allocation(List<Message> history, int attachmentTokens) {}
    private final AttachmentTokens tokens;
    private final FileProperties properties;
    public ChatContextBudget(AttachmentTokens tokens, FileProperties properties) { this.tokens = tokens; this.properties = properties; }
    public Allocation allocate(String system, String user, List<Message> history, int images, boolean attachments) {
        int remaining = properties.getContextWindowTokens() - properties.getOutputReserveTokens()
                - properties.getSafetyReserveTokens() - tokens.chat(system) - tokens.chat(user) - 128
                - images * properties.getImageReserveTokens();
        int reserved = attachments ? Math.min(properties.getAttachmentTokens(), Math.max(0, remaining)) : 0;
        if (remaining < 0 || (attachments && reserved < 512)) throw new IllegalArgumentException("本轮上下文超过模型预算，请缩短问题或减少图片");
        int historyBudget = remaining - reserved;
        List<Message> kept = new ArrayList<>();
        List<Message> input = history == null ? List.of() : history;
        // 从最近的完整 user/assistant 轮次保留，避免把截断的旧回复当作依据。
        int cursor = input.size();
        while (cursor > 0) {
            int start = cursor - 1;
            while (start > 0 && input.get(start).getMessageType() != org.springframework.ai.chat.messages.MessageType.USER) start--;
            List<Message> turn = input.subList(start, cursor);
            int size = turn.stream().mapToInt(m -> tokens.chat(m.getText()) + 16).sum();
            if (size > historyBudget) break;
            kept.addAll(0, turn); historyBudget -= size; cursor = start;
        }
        return new Allocation(List.copyOf(kept), reserved);
    }
    public void verify(String system, String user, List<Message> history, int images) {
        int total = tokens.chat(system) + tokens.chat(user) + 128 + images * properties.getImageReserveTokens()
                + history.stream().mapToInt(m -> tokens.chat(m.getText()) + 16).sum()
                + properties.getOutputReserveTokens() + properties.getSafetyReserveTokens();
        if (total > properties.getContextWindowTokens()) throw new IllegalArgumentException("本轮上下文超过模型预算，请减少附件或缩短问题");
    }
}

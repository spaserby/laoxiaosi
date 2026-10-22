package com.law.backend.attachment;

import com.law.backend.service.FileProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatContextBudgetTest {
    final FileProperties properties = new FileProperties();
    final AttachmentTokens tokens = new AttachmentTokens(properties);
    final ChatContextBudget budget = new ChatContextBudget(tokens, properties);
    @Test void reservesOneAttachmentBudgetSharedByAllFilesAndKeepsRecentCompleteTurns() {
        String system = "法律依据与角色规则。".repeat(400);
        List<Message> history = List.of(new UserMessage("旧问题".repeat(10000)), new AssistantMessage("旧答复".repeat(10000)),
                new UserMessage("最近的问题"), new AssistantMessage("最近的答复"));
        var allocation = budget.allocate(system, "本轮咨询", history, 0, true);
        assertEquals(8000, allocation.attachmentTokens()); assertEquals(2, allocation.history().size());
        String attachment = "相关资料。".repeat(100);
        budget.verify(system, attachment + "本轮咨询", allocation.history(), 0);
    }
    @Test void rejectsOversizedSystemOrPicturesBeforeModelCall() {
        assertThrows(IllegalArgumentException.class, () -> budget.allocate("法律依据。".repeat(10000), "问题", List.of(), 0, true));
        assertThrows(IllegalArgumentException.class, () -> budget.allocate("规则", "问题", List.of(), 8, true));
    }
    @Test void finalCheckIncludesAttachmentsAndOutputReserve() {
        assertThrows(IllegalArgumentException.class, () -> budget.verify("规则", "附件资料。".repeat(10000), List.of(), 0));
    }
}

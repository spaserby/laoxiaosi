package com.law.backend.memory;

import com.law.backend.attachment.AttachmentTokens;
import com.law.backend.service.FileProperties;
import com.law.backend.service.SessionAccessDeniedException;
import com.law.backend.service.SessionOwnershipService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Spring 事务 + MyBatis SQL；仅替换外部摘要模型。 */
class PgChatMemoryTransactionTest {
    static final String SESSION = "synthetic-memory";
    static final SessionOwnershipService.Owner OWNER = new SessionOwnershipService.Owner("u:900001", null);
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    org.springframework.ai.chat.memory.ChatMemory memory;
    MessageMapper messages;
    SummaryMapper summaries;
    ConversationTurnService turns;
    ConversationHistoryService history;
    ChatMemoryProperties properties;
    TransactionTemplate transaction;
    RollingSummaryModel model;
    ConversationSummaryWorker worker;

    @BeforeEach void setUp() {
        context = new AnnotationConfigApplicationContext(MemoryTestDatabase.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute(MemoryTestDatabase.SCHEMA);
        jdbc.update("insert into chat_session(session_id,owner_key) values(?,?)", SESSION, OWNER.key());
        memory = context.getBean(org.springframework.ai.chat.memory.ChatMemory.class); messages = context.getBean(MessageMapper.class);
        summaries = context.getBean(SummaryMapper.class); turns = context.getBean(ConversationTurnService.class);
        history = context.getBean(ConversationHistoryService.class); properties = context.getBean(ChatMemoryProperties.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        model = mock(RollingSummaryModel.class);
        worker = new ConversationSummaryWorker(messages, summaries, properties, model, new AttachmentTokens(new FileProperties()));
        when(model.summarize(anyString(), anyString())).thenReturn("用户陈述：合成付款争议。待核实日期、金额与合同。来源消息ID见证据区。");
    }
    @AfterEach void close() { if (worker != null) worker.close(); if (context != null) context.close(); }

    void rounds(int n) {
        for (int i = 1; i <= n; i++) {
            var turn = turns.begin(SESSION, OWNER.key(), "合成问题" + i);
            turns.finish(turn, "合成答复" + i, "COMPLETE");
        }
    }
    @Test void fiftyRoundsSurviveAndHistoryNeverContainsInternalSummary() {
        rounds(50);
        assertEquals(100, messages.countBySession(SESSION));
        List<String> contents = new ArrayList<>();
        Long before = null;
        do {
            var page = history.page(SESSION, OWNER, before, 17);
            assertTrue(page.data().stream().allMatch(m -> Set.of("user","assistant").contains(m.get("role"))));
            contents.addAll(0, page.data().stream().map(m -> (String)m.get("content")).toList());
            before = page.hasMore() ? Long.valueOf(page.nextCursor()) : null;
        } while (before != null);
        assertEquals(100, contents.size()); assertEquals("合成问题1", contents.get(0));
        assertEquals("合成答复50", contents.get(99));
        for (int i = 0; i < 6; i++) worker.scan();
        assertEquals(92, summaries.state(SESSION).coveredUntilMessageId());
        assertEquals(100, messages.countBySession(SESSION));
        var view = memory.get(SESSION);
        assertEquals(8, view.stream().filter(m -> m.getMessageType()!=MessageType.SYSTEM).count());
        assertTrue(view.get(0) instanceof SystemMessage);
        assertEquals("合成问题47", view.get(1).getText());
    }
    @Test void failedBatchAndOuterRollbackKeepAllOriginals() {
        rounds(10);
        assertThrows(DataAccessException.class, () -> memory.add(SESSION,
                List.of(new UserMessage("new"), new AssistantMessage("reject"))));
        transaction.executeWithoutResult(tx -> { memory.add(SESSION, List.of(new UserMessage("rolled back"))); tx.setRollbackOnly(); });
        assertEquals(20, messages.countBySession(SESSION));
        assertEquals(0, summaries.state(SESSION).coveredUntilMessageId());
        verifyNoInteractions(model);
    }
    @Test void persistedEvidenceSurvivesFreshHistoryReadWithoutReretrieval() {
        var turn=turns.begin(SESSION,OWNER.key(),"合成证据问题");
        String evidence="{\"version\":1,\"citations\":[{\"lawName\":\"合成规则\",\"articleNo\":\"第1条\",\"text\":\"合成证据原文\"}],"
                + "\"attachmentContext\":{\"sources\":[{\"fileId\":\"synthetic-file\",\"parentId\":2}],\"warnings\":[],\"selectedSections\":1,\"totalSections\":3,\"fullCoverage\":false}}";
        turns.finish(turn,"合成答案","COMPLETE",evidence);
        var restored=history.page(SESSION,OWNER,null,50).data().get(1);
        assertEquals(true,restored.get("evidenceAvailable"));
        assertTrue(restored.get("citations").toString().contains("合成证据原文"));
        assertTrue(restored.get("attachmentContext").toString().contains("synthetic-file"));
        rounds(1);
        assertEquals(false,history.page(SESSION,OWNER,null,50).data().get(3).get("evidenceAvailable"));
    }
    @Test void publicHistoryControllerReturnsOriginalsWithoutSummaryAfterFiveRounds() {
        rounds(5); worker.scan();
        var controller = new com.law.backend.controller.SessionController(
                mock(com.law.backend.service.SessionIndexService.class), history,
                context.getBean(SessionOwnershipService.class), mock(com.law.backend.service.ChatFileService.class));
        var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/session/" + SESSION + "/messages").build());
        var response = controller.messages(SESSION,900001L,exchange,null,50).block(java.time.Duration.ofSeconds(5));
        var data = (List<?>) response.get("data");
        assertEquals(10,data.size());
        assertFalse(data.toString().contains("summary"));
        assertTrue(data.toString().contains("合成问题1"));
        assertEquals(false,response.get("hasMore"));
    }
    @Test void ownershipIsEnforcedByServiceAndSqlAndCursorOnlyMovesBackward() {
        rounds(10);
        var other = new SessionOwnershipService.Owner("u:900002", null);
        assertThrows(SessionAccessDeniedException.class, () -> history.page(SESSION, other, null, 50));
        assertTrue(messages.history(SESSION, other.key(), Long.MAX_VALUE, 100).isEmpty());
        var page = history.page(SESSION, OWNER, 10L, 4);
        assertEquals(List.of("6","7","8","9"), page.data().stream().map(m -> m.get("id")).toList());
        assertEquals("6", page.nextCursor()); assertTrue(page.hasMore());
        assertThrows(IllegalArgumentException.class, () -> history.page(SESSION, OWNER, 0L, 50));
    }
    @Test void summaryFailureDoesNotAdvanceAndFreshWorkerRetriesFromDatabase() {
        rounds(10);
        when(model.summarize(anyString(),anyString())).thenThrow(new IllegalStateException("synthetic failure"));
        worker.scan();
        assertEquals(0, summaries.state(SESSION).coveredUntilMessageId());
        assertEquals(20, messages.countBySession(SESSION));
        assertEquals(1, jdbc.queryForObject("select failures from chat_summary", Integer.class));
        jdbc.update("update chat_summary set next_attempt_at=?", LocalDateTime.now().minusSeconds(1));
        doReturn("待核实的合成付款争议，来源消息ID1至12。").when(model).summarize(anyString(),anyString());
        worker.close();
        worker = new ConversationSummaryWorker(messages, summaries, properties, model, new AttachmentTokens(new FileProperties()));
        worker.scan();
        assertEquals(12, summaries.state(SESSION).coveredUntilMessageId());
        assertEquals(0, jdbc.queryForObject("select failures from chat_summary", Integer.class));
    }
    @Test void staleAndDeletedSummaryTasksCannotOverwriteOrResurrect() {
        rounds(6);
        var now = LocalDateTime.now();
        assertEquals(1, summaries.claim(SESSION,"old",now,now.plusMinutes(10)));
        jdbc.update("update chat_summary set lease_until=?", now.minusSeconds(1));
        assertEquals(1, summaries.claim(SESSION,"new",now,now.plusMinutes(10)));
        assertEquals(0, summaries.commit(SESSION,"old",0,0,2,"stale",now));
        assertEquals(1, summaries.commit(SESSION,"new",0,0,2,"current",now));
        assertEquals(0, summaries.commit(SESSION,"new",0,0,4,"stale version",now));
        assertEquals("current", summaries.state(SESSION).content());
        jdbc.update("update chat_summary set next_attempt_at=?", now.minusSeconds(1));
        when(model.summarize(anyString(),anyString())).thenAnswer(call -> { history.delete(SESSION, OWNER); return "late result"; });
        worker.process(SESSION);
        assertNull(summaries.state(SESSION)); assertEquals(0, messages.countBySession(SESSION));
    }
    @Test void partialTurnsFinalizeOnceAndDeletedTurnCannotReturn() {
        var turn = turns.begin(SESSION, OWNER.key(), "合成中断问题");
        assertThrows(IllegalArgumentException.class, () -> turns.begin(SESSION, OWNER.key(), "并行请求"));
        assertTrue(memory.get(SESSION).isEmpty());
        turns.finish(turn,"部分答复","INTERRUPTED");
        turns.finish(turn,"重复完成","COMPLETE");
        assertEquals(2, messages.countBySession(SESSION));
        assertTrue(memory.get(SESSION).get(1).getText().contains("INTERRUPTED"));
        var running = turns.begin(SESSION, OWNER.key(), "合成待完成问题");
        history.delete(SESSION,OWNER);
        turns.finish(running,"迟到答复","COMPLETE");
        assertEquals(0,messages.countBySession(SESSION));
    }
    @Test void oversizedTurnIsSummarizedInBoundedInputsBeforeSingleCursorCommit() {
        properties.setSummaryInputTokens(2600); properties.setSummaryMaxTokens(300);
        var turn = turns.begin(SESSION,OWNER.key(),"合成内容。".repeat(1500));
        turns.finish(turn,"合成结束答复","COMPLETE"); rounds(4);
        when(model.summarize(anyString(),anyString())).thenAnswer(call -> {
            assertEquals(0,summaries.state(SESSION).coveredUntilMessageId());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return "用户陈述待核实。来源消息ID1和2。";
        });
        worker.scan();
        assertEquals(2,summaries.state(SESSION).coveredUntilMessageId());
        assertEquals(10,messages.countBySession(SESSION));
        verify(model,atLeast(2)).summarize(anyString(),anyString());
    }
    @Test void abandonedRunningTurnIsRecoveredWithoutInventingAnswer() {
        turns.begin(SESSION,OWNER.key(),"合成重启前问题");
        jdbc.update("update chat_message set created_at=?", LocalDateTime.now().minusHours(2));
        properties.setSummaryEnabled(false); worker.scan();
        assertEquals("INTERRUPTED", jdbc.queryForObject("select status from chat_message",String.class));
        assertEquals(1,messages.countBySession(SESSION));
        assertDoesNotThrow(() -> turns.begin(SESSION,OWNER.key(),"重启后问题"));
    }
}

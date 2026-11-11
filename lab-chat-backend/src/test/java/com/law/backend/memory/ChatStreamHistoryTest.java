package com.law.backend.memory;

import com.law.backend.agent.*;
import com.law.backend.attachment.*;
import com.law.backend.model.*;
import com.law.backend.rag.RagProperties;
import com.law.backend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import reactor.core.publisher.Flux;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** 实际 ChatService 流式管道到真实 PostgreSQL Mapper/H2 存储；只替换模型、附件与 Redis 网络边界。 */
class ChatStreamHistoryTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ChatService chat;
    ChatClient.StreamResponseSpec response;
    AtomicBoolean stop = new AtomicBoolean();
    ModelRouter router;
    com.law.backend.rag.LawArticleMapper lawArticles;
    ChatClient.ChatClientRequestSpec primaryRequest;
    static final SessionOwnershipService.Owner OWNER = new SessionOwnershipService.Owner("u:900001",null);
    @BeforeEach void prepare() throws Exception {
        context = new AnnotationConfigApplicationContext(MemoryTestDatabase.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class)); jdbc.execute(MemoryTestDatabase.SCHEMA);
        jdbc.update("insert into chat_session(session_id,owner_key) values('synthetic-stream',?)",OWNER.key());
        var dependencies=new HashMap<Class<?>,Object>();
        for (var type : ChatService.class.getConstructors()[0].getParameterTypes()) dependencies.put(type,mock(type));
        dependencies.put(ChatMemory.class,context.getBean(ChatMemory.class));
        dependencies.put(ConversationTurnService.class,context.getBean(ConversationTurnService.class));
        var fileProperties=new FileProperties(); dependencies.put(FileProperties.class,fileProperties);
        dependencies.put(ChatContextBudget.class,new ChatContextBudget(new AttachmentTokens(fileProperties),fileProperties));
        dependencies.put(ModelRouteProperties.class,new ModelRouteProperties());
        dependencies.put(IdentityProperties.class,new IdentityProperties());
        var rag=new RagProperties(); rag.setEnabled(false); dependencies.put(RagProperties.class,rag);
        var client=mock(ChatClient.class); var request=mock(ChatClient.ChatClientRequestSpec.class,RETURNS_SELF);
        primaryRequest=request;
        response=mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(request); when(request.stream()).thenReturn(response);
        var routed=new RoutedClient("cheap",client,List.of(),0);
        router=(ModelRouter)dependencies.get(ModelRouter.class);
        lawArticles=(com.law.backend.rag.LawArticleMapper)dependencies.get(com.law.backend.rag.LawArticleMapper.class);
        when(router.route(anyString())).thenReturn(routed); when(router.fallback()).thenReturn(routed);
        var planner=(PlannerAgent)dependencies.get(PlannerAgent.class);
        when(planner.execute(any())).thenAnswer(call -> { AgentContext ctx=call.getArgument(0); ctx.setPlan(Plan.single(ctx.getQuestion())); return ctx; });
        var files=(ChatFileService)dependencies.get(ChatFileService.class);
        var turn=new ChatFileService.Turn(List.of(),List.of(),"");
        when(files.turn(anyString(),any(),any(),anyString())).thenReturn(turn);
        when(files.context(anyString(),any(),any(),anyString(),anyInt(),anyBoolean(),any(),any())).thenReturn(AttachmentContext.empty());
        var redis=mock(RedissonClient.class,RETURNS_DEEP_STUBS); dependencies.put(RedissonClient.class,redis);
        when(redis.getBucket(anyString()).isExistsAsync()).thenAnswer(call -> new CompletableFutureWrapper<>(stop.get()));
        var ctor=ChatService.class.getConstructors()[0];
        chat=(ChatService)ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(dependencies::get).toArray());
    }
    @AfterEach void close() { context.close(); }
    ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    Flux<org.springframework.http.codec.ServerSentEvent<String>> stream() { return chat.chat("synthetic-stream","合成问题",null,List.of(),OWNER,false); }
    String userStatus() { return jdbc.queryForObject("select status from chat_message where role='USER'",String.class); }
    @Test void normalDoneIsSentAfterBothOriginalsCommit() {
        when(response.chatResponse()).thenReturn(Flux.just(answer("合成完整答复")));
        var events=stream().collectList().block(Duration.ofSeconds(10));
        assertTrue(events.stream().anyMatch(e -> "done".equals(e.event())));
        assertEquals(2,jdbc.queryForObject("select count(*) from chat_message",Integer.class));
        assertEquals("COMPLETE",userStatus());
    }
    @Test void clientDisconnectSavesPartialAnswerAndTerminalState() {
        when(response.chatResponse()).thenReturn(Flux.concat(Flux.just(answer("合成部分答复")),Flux.never()));
        stream().takeUntil(e -> "token".equals(e.event())).blockLast(Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(5)).until(() -> "INTERRUPTED".equals(userStatus()));
        assertEquals("合成部分答复",jdbc.queryForObject("select content from chat_message where role='ASSISTANT'",String.class));
    }
    @Test void explicitStopRecordsStoppedPartialTurn() {
        when(response.chatResponse()).thenReturn(Flux.concat(Flux.just(answer("合成停止答复")),Flux.never()));
        stream().doOnNext(e -> { if ("token".equals(e.event())) stop.set(true); }).blockLast(Duration.ofSeconds(10));
        assertEquals("STOPPED",userStatus());
        assertEquals(2,jdbc.queryForObject("select count(*) from chat_message",Integer.class));
    }
    @Test void providerFailureKeepsQuestionAndMarksFailedWithoutFabricatingAnswer() {
        when(response.chatResponse()).thenReturn(Flux.error(new IllegalStateException("synthetic model failure")));
        var events=stream().collectList().block(Duration.ofSeconds(10));
        assertTrue(events.stream().anyMatch(e -> "error".equals(e.event())));
        assertEquals("FAILED",userStatus());
        assertEquals(1,jdbc.queryForObject("select count(*) from chat_message",Integer.class));
    }
    @Test void fallbackReplacesPartialAttemptInsteadOfConcatenatingIt() {
        when(response.chatResponse()).thenReturn(Flux.concat(Flux.just(answer("SYNTHETIC_PRIMARY_PARTIAL")), Flux.error(new IllegalStateException("synthetic upstream failure"))));
        var backup=mock(ChatClient.class); var request=mock(ChatClient.ChatClientRequestSpec.class,RETURNS_SELF);
        var output=mock(ChatClient.StreamResponseSpec.class); when(backup.prompt()).thenReturn(request); when(request.stream()).thenReturn(output);
        when(output.chatResponse()).thenReturn(Flux.just(answer("SYNTHETIC_BACKUP_FINAL")));
        when(router.fallback()).thenReturn(new RoutedClient("cheap",backup,List.of(),0));
        var events=stream().collectList().block(Duration.ofSeconds(10));
        assertTrue(events.stream().anyMatch(e -> "retry".equals(e.event())));
        assertEquals("SYNTHETIC_BACKUP_FINAL",jdbc.queryForObject("select content from chat_message where role='ASSISTANT'",String.class));
        assertEquals("COMPLETE",userStatus());
        var tools=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(primaryRequest).toolContext(tools.capture());
        assertEquals(0,((com.law.backend.tool.ToolBudget)tools.getValue().get(com.law.backend.tool.ToolBudget.KEY)).maxCalls());
    }
    @Test void streamedCitationIsRestoredFromHistoryAfterGeneration() {
        var law=new com.law.backend.rag.LawArticleEntity(); law.setId(900001L); law.setLawName("合成规则"); law.setArticleNo("第1条"); law.setContent("合成条文证据原文");
        when(lawArticles.findByLawNameAndArticleNo("合成规则","第1条")).thenReturn(law);
        when(response.chatResponse()).thenReturn(Flux.just(answer("依据《合成规则》第1条，给出合成答复。")));
        var events=stream().collectList().block(Duration.ofSeconds(10));
        assertTrue(events.stream().anyMatch(e -> "citation".equals(e.event())));
        var restored=context.getBean(ConversationHistoryService.class).page("synthetic-stream",OWNER,null,50).data().get(1);
        assertEquals(true,restored.get("evidenceAvailable"));
        assertTrue(restored.get("citations").toString().contains("合成条文证据原文"));
    }
}

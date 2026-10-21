package com.law.backend.memory;

import com.law.backend.model.ModelRegistry;
import com.law.backend.model.ModelRouteProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用真实 Mapper、数据库及 Spring 代理验证事务；只替换摘要外部依赖。 */
class PgChatMemoryTransactionTest {

    private static final String SESSION = "transaction-test";

    private AnnotationConfigApplicationContext context;
    private ChatMemory memory;
    private JdbcTemplate jdbc;
    private SummaryMapper summaries;
    private ModelRegistry models;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        memory = context.getBean(ChatMemory.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        summaries = context.getBean(SummaryMapper.class);
        models = context.getBean(ModelRegistry.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        context.getBean(ChatMemoryProperties.class).setVerbatimRounds(1);

        // CHECK 用于在批量追加途中注入数据库错误。
        jdbc.execute("create table chat_message (id bigserial primary key, "
                + "session_id varchar(64) not null, role varchar(20) not null, "
                + "content text check (content <> 'reject'))");
        // 外键用于让裁剪 SQL 在数据库中失败，而非只模拟 Mapper 抛异常。
        jdbc.execute("create table retained_message (message_id bigint references chat_message(id))");
        jdbc.update("insert into chat_message(session_id, role, content) values (?, 'USER', 'old user')", SESSION);
        jdbc.update("insert into chat_message(session_id, role, content) values (?, 'ASSISTANT', 'old assistant')", SESSION);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("裁剪失败：整批新增回滚，旧窗口保留，不启动摘要")
    void deleteFailureRollsBackAllInserts() {
        jdbc.update("insert into retained_message select min(id) from chat_message");

        assertThrows(DataAccessException.class, () -> memory.add(SESSION, List.of(
                new UserMessage("new user"), new AssistantMessage("new assistant"))));

        assertEquals(List.of("old user", "old assistant"), contents());
        verifyNoInteractions(summaries, models);
    }

    @Test
    @DisplayName("批量插入途中失败：已插入的前半批也回滚")
    void laterInsertFailureRollsBackEarlierInsert() {
        assertThrows(DataAccessException.class, () -> memory.add(SESSION, List.of(
                new UserMessage("new user"), new AssistantMessage("reject"))));

        assertEquals(List.of("old user", "old assistant"), contents());
        verifyNoInteractions(summaries, models);
    }

    @Test
    @DisplayName("摘要等待外层事务提交，且只包含被裁掉的旧消息")
    void summaryStartsOnlyAfterCommit() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(models.getRawClient("test", "summary")).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("summary");
        AtomicReference<List<String>> visibleAtSummary = new AtomicReference<>();
        when(summaries.select(SESSION)).thenAnswer(invocation -> {
            visibleAtSummary.set(contents());
            return null;
        });

        transaction.executeWithoutResult(status -> {
            memory.add(SESSION, List.of(new UserMessage("new user"), new AssistantMessage("new assistant")));
            assertEquals(List.of("new user", "new assistant"), contents());
            verify(summaries, after(200).never()).select(SESSION);
            verifyNoInteractions(summaries, models);
        });

        verify(summaries, timeout(3000)).upsert(SESSION, "summary");
        assertEquals(List.of("new user", "new assistant"), visibleAtSummary.get());
        verify(client.prompt().system(anyString())).user("USER:old user\nASSISTANT:old assistant\n");
    }

    @Test
    @DisplayName("外层事务回滚：撤销新增与裁剪，不启动摘要")
    void outerRollbackDoesNotStartSummary() {
        transaction.executeWithoutResult(status -> {
            memory.add(SESSION, List.of(new UserMessage("new user"), new AssistantMessage("new assistant")));
            status.setRollbackOnly();
        });

        assertEquals(List.of("old user", "old assistant"), contents());
        verify(summaries, after(200).never()).select(SESSION);
        verifyNoInteractions(summaries, models);
    }

    @Test
    @DisplayName("关闭摘要仍正常提交新增与裁剪")
    void summaryDisabledStillTrimsWindow() {
        context.getBean(ChatMemoryProperties.class).setSummaryEnabled(false);

        memory.add(SESSION, List.of(new UserMessage("new user"), new AssistantMessage("new assistant")));

        assertEquals(List.of("new user", "new assistant"), contents());
        verifyNoInteractions(summaries, models);
    }

    private List<String> contents() {
        return jdbc.queryForList("select content from chat_message where session_id = ? order by id",
                String.class, SESSION);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(PgChatMemory.class)
    static class TestConfig {

        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        MessageMapper messageMapper(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            var sqlSessionFactory = factory.getObject();
            sqlSessionFactory.getConfiguration().addMapper(MessageMapper.class);
            return new SqlSessionTemplate(sqlSessionFactory).getMapper(MessageMapper.class);
        }

        @Bean
        SummaryMapper summaryMapper() {
            return mock(SummaryMapper.class);
        }

        @Bean
        ModelRegistry modelRegistry() {
            return mock(ModelRegistry.class);
        }

        @Bean
        ModelRouteProperties routeProperties() {
            ModelRouteProperties properties = new ModelRouteProperties();
            properties.setRoutes(Map.of("cheap", "test/summary"));
            return properties;
        }
    }
}

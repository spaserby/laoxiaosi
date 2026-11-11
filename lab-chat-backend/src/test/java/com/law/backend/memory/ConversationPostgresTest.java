package com.law.backend.memory;

import com.law.backend.service.SessionOwnershipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只允许显式本机 PG；所有合成数据位于随机 schema，结束后删除该 schema。 */
class ConversationPostgresTest {
    @Test
    @EnabledIfSystemProperty(named="memory.test.pg.url", matches="jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[^?]+")
    void migrationIsRepeatableAndRealPostgresQueriesPreserveHistory() throws Exception {
        var ds = new DriverManagerDataSource(System.getProperty("memory.test.pg.url"),
                System.getenv().getOrDefault("MEMORY_TEST_PG_USER","postgres"), System.getenv().getOrDefault("MEMORY_TEST_PG_PASSWORD",""));
        var admin = new JdbcTemplate(ds);
        String schema = "memory_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + schema);
        try {
            var scoped = new DriverManagerDataSource(ds.getUrl(),ds.getUsername(),ds.getPassword());
            var options = new Properties(); options.setProperty("options","-c search_path=" + schema); scoped.setConnectionProperties(options);
            var jdbc = new JdbcTemplate(scoped);
            jdbc.execute("create table chat_session(session_id varchar(64) primary key,owner_key varchar(80),title varchar(64),created_at bigint,updated_at bigint)");
            jdbc.execute("create table chat_message(id bigserial primary key,session_id varchar(64),role varchar(20),content text,created_at timestamp default current_timestamp)");
            jdbc.execute("create table chat_summary(session_id varchar(64) primary key,content text not null,updated_at timestamp default current_timestamp)");
            jdbc.update("insert into chat_session(session_id,owner_key) values('synthetic-pg','u:900001')");
            jdbc.update("insert into chat_summary(session_id,content) values('synthetic-pg','合成旧版摘要，不还原原文')");
            String migration = Files.readString(Path.of("../scripts/migration-04-conversation-history.sql"));
            jdbc.execute(migration); jdbc.execute(migration);
            jdbc.execute("create table app_user(id bigserial primary key)");
            String securityMigration = Files.readString(Path.of("../scripts/migration-05-security-and-evidence.sql"));
            jdbc.execute(securityMigration); jdbc.execute(securityMigration);
            var config = new org.apache.ibatis.session.Configuration(new Environment("memory-pg",new JdbcTransactionFactory(),scoped));
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(MessageMapper.class); config.addMapper(SummaryMapper.class); config.addMapper(SessionMapper.class);
            try (var sql = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                var messages = sql.getMapper(MessageMapper.class); var summaries = sql.getMapper(SummaryMapper.class);
                var properties = new ChatMemoryProperties();
                var memory = new PgChatMemory(messages,summaries,properties);
                var owners = new SessionOwnershipService(sql.getMapper(SessionMapper.class));
                var history = new ConversationHistoryService(messages,summaries,sql.getMapper(SessionMapper.class),owners);
                var turns = new ConversationTurnService(messages,summaries);
                for (int i=0;i<50;i++) {
                    var turn=turns.begin("synthetic-pg","u:900001","合成PG问题"+i);
                    turns.finish(turn,"合成PG答复"+i,"COMPLETE");
                }
                assertEquals(100,messages.countBySession("synthetic-pg"));
                var owner = new SessionOwnershipService.Owner("u:900001",null);
                var page=history.page("synthetic-pg",owner,null,50);
                assertEquals(50,page.data().size()); assertTrue(page.hasMore()); assertEquals("51",page.nextCursor());
                assertEquals(50,history.page("synthetic-pg",owner,51L,50).data().size());
                assertTrue(summaries.state("synthetic-pg").legacy());
                assertTrue(messages.history("synthetic-pg","u:900002",null,50).isEmpty());
                assertEquals(10,memory.get("synthetic-pg").size()); // 摘要 + 覆盖提示 + 8条原文
                var now=LocalDateTime.now(); assertEquals(1,summaries.claim("synthetic-pg","lease",now,now.plusMinutes(10)));
                assertEquals(1,summaries.commit("synthetic-pg","lease",0,0,92,"合成新摘要",now));
                assertEquals(0,summaries.commit("synthetic-pg","lease",0,0,94,"迟到结果",now));
                assertEquals(9,memory.get("synthetic-pg").size());
                assertEquals(100,messages.countBySession("synthetic-pg"));
                history.delete("synthetic-pg",owner);
                assertEquals(0,summaries.commit("synthetic-pg","lease",1,92,94,"删除后结果",now));
            }
        } finally { admin.execute("drop schema " + schema + " cascade"); }
    }
}

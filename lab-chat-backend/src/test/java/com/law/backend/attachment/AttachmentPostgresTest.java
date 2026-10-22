package com.law.backend.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式指定本机测试库时运行；不读取应用/生产数据源配置。 */
class AttachmentPostgresTest {
    @Test
    @EnabledIfSystemProperty(named = "attachment.test.pg.url", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[^?]+")
    void realPgvectorSearchIsScopedAndDeletionCascades() throws Exception {
        var adminDs = new DriverManagerDataSource(System.getProperty("attachment.test.pg.url"),
                System.getenv().getOrDefault("ATTACHMENT_TEST_PG_USER", "postgres"), System.getenv().getOrDefault("ATTACHMENT_TEST_PG_PASSWORD", ""));
        var admin = new JdbcTemplate(adminDs);
        String schema = "attachment_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create extension if not exists vector"); admin.execute("create schema " + schema);
        try {
            var scopedDs = new DriverManagerDataSource(adminDs.getUrl(), adminDs.getUsername(), adminDs.getPassword());
            var connection = new Properties(); connection.setProperty("options", "-c search_path=" + schema + ",public"); scopedDs.setConnectionProperties(connection);
            var jdbc = new JdbcTemplate(scopedDs);
            jdbc.execute("create table chat_session(session_id varchar(64) primary key,owner_key varchar(80),title varchar(64),created_at bigint,updated_at bigint)");
            jdbc.execute("create table chat_message(id bigserial primary key,session_id varchar(64),role varchar(20),content text)");
            jdbc.update("insert into chat_session(session_id,owner_key) values('session-a','u:101'),('session-b','u:202')");
            String ddl = Files.readString(Path.of("../scripts/migration-03-private-attachments.sql"));
            for (String sql : ddl.split(";")) if (!sql.replaceAll("(?m)--.*", "").isBlank()) jdbc.execute(sql);
            var repository = new AttachmentRepository(new NamedParameterJdbcTemplate(scopedDs), new DataSourceTransactionManager(scopedDs));
            long now = System.currentTimeMillis();
            var alice = new AttachmentRepository.Scope("session-a", "u:101", now);
            var bob = new AttachmentRepository.Scope("session-b", "u:202", now);
            String a = "a".repeat(32), b = "b".repeat(32);
            for (var scope : List.of(alice, bob)) {
                String id = scope == alice ? a : b;
                repository.create(scope, id, "synthetic.txt", "txt", 10, now + 60000, 3, "parser", "chunker", "embedding-test");
                repository.start(id, now);
                var parsed = new AttachmentParser.Parsed("合成条款", List.of(), 1, 1, List.of(), List.of(), "text");
                repository.complete(id, parsed, new AttachmentChunker.Chunks(List.of(new AttachmentChunker.Parent(0, 0, 4, "正文", "合成条款")),
                        List.of(new AttachmentChunker.Child(0, 0, 0, 4, "正文", "合成条款"))), List.of(new float[]{1, 0, 0}), List.of(), now);
            }
            var hits = repository.dense(alice, List.of(a, b), new float[]{1, 0, 0}, 10, "embedding-test");
            assertEquals(1, hits.size()); assertEquals(a, hits.get(0).fileId()); assertEquals(1.0, hits.get(0).score(), 0.0001);
            assertTrue(repository.dense(alice, List.of(a), new float[]{1, 0, 0}, 10, "other-version").isEmpty());
            repository.expire(alice, a); repository.purge(a, now);
            assertTrue(repository.children(alice, List.of(a)).isEmpty()); assertFalse(repository.exists(a));
            verifySessionTitles(scopedDs, jdbc);
        } finally { admin.execute("drop schema " + schema + " cascade"); }
    }

    private void verifySessionTitles(DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
        var config = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("test",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        config.addMapper(com.law.backend.memory.SessionMapper.class);
        var factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        try (var sql = factory.openSession(true)) {
            var mapper = sql.getMapper(com.law.backend.memory.SessionMapper.class);
            var ownership = new com.law.backend.service.SessionOwnershipService(mapper);
            var owner = new com.law.backend.service.SessionOwnershipService.Owner("u:101", null);
            ownership.register("title-upload", owner);
            assertEquals("新对话", jdbc.queryForObject("select title from chat_session where session_id='title-upload'", String.class));
            ownership.register("title-upload", owner, "合成测试：付款条件");
            assertEquals("合成测试：付款条件", jdbc.queryForObject("select title from chat_session where session_id='title-upload'", String.class));
            jdbc.update("insert into chat_message(session_id,role,content) values('title-upload','USER','合成测试：付款条件')");
            ownership.register("title-upload", owner, "合成测试：后续追问");
            ownership.register("title-upload", owner);
            assertEquals("合成测试：付款条件", jdbc.queryForObject("select title from chat_session where session_id='title-upload'", String.class));
            assertEquals(0, mapper.upsert("title-upload", "不得覆盖", 1, 1, "u:202"));
            ownership.register("literal-placeholder", owner, "新对话");
            jdbc.update("insert into chat_message(session_id,role,content) values('literal-placeholder','USER','新对话')");
            ownership.register("literal-placeholder", owner, "合成测试：后续追问");
            assertEquals("新对话", jdbc.queryForObject("select title from chat_session where session_id='literal-placeholder'", String.class));
        }
    }
}

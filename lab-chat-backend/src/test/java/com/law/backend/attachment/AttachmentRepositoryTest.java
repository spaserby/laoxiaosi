package com.law.backend.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.law.backend.service.SessionAccessDeniedException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SQL + 真实事务验证 ACL 与原子发布；不连接业务数据库。 */
class AttachmentRepositoryTest {
    JdbcTemplate jdbc;
    AttachmentRepository repository;
    final AttachmentRepository.Scope alice = new AttachmentRepository.Scope("session-a", "u:101", 1000);
    final AttachmentRepository.Scope bob = new AttachmentRepository.Scope("session-b", "u:202", 1000);
    final String id = "a".repeat(32);
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table chat_session(session_id varchar(64) primary key,owner_key varchar(80))");
        jdbc.update("insert into chat_session values('session-a','u:101'),('session-b','u:202')");
        String ddl = Files.readString(Path.of("../scripts/migration-03-private-attachments.sql"));
        ddl = ddl.replaceAll("(?m)^CREATE EXTENSION.*;", "").replace("embedding vector", "embedding varchar(40000)");
        for (String sql : ddl.split(";")) if (!sql.replaceAll("(?m)--.*", "").isBlank()) jdbc.execute(sql);
        repository = new AttachmentRepository(new NamedParameterJdbcTemplate(ds), new DataSourceTransactionManager(ds));
    }
    void create(String fileId, AttachmentRepository.Scope scope) {
        repository.create(scope, fileId, "synthetic.txt", "txt", 32, 10000, 3, "test-parser", "test-chunker", "test-embedding");
    }
    void ready() {
        create(id, alice); assertTrue(repository.start(id, 1001));
        String text = "付款以验收为前提，但质量不合格除外。";
        var parsed = new AttachmentParser.Parsed(text, List.of(), 1, 1, List.of(), List.of(), "text");
        var parent = new AttachmentChunker.Parent(0, 0, text.length(), "第1条", text);
        var child = new AttachmentChunker.Child(0, 0, 0, text.length(), "第1条", text);
        repository.complete(id, parsed, new AttachmentChunker.Chunks(List.of(parent), List.of(child)), List.of(), List.of(), 1002);
    }
    @Test void rejectsCrossUserCrossSessionAndExpiredAccessOnEveryRead() {
        ready();
        assertEquals(1, repository.parents(alice, List.of(id)).size());
        assertThrows(SessionAccessDeniedException.class, () -> repository.require(bob, id));
        assertTrue(repository.parents(bob, List.of(id)).isEmpty()); assertTrue(repository.children(bob, List.of(id)).isEmpty());
        assertThrows(SessionAccessDeniedException.class, () -> repository.text(bob, id));
        var anotherSession = new AttachmentRepository.Scope("session-b", "u:101", 1000);
        assertTrue(repository.list(anotherSession).isEmpty());
        assertThrows(SessionAccessDeniedException.class, () -> repository.require(anotherSession, id));
        var expired = new AttachmentRepository.Scope("session-a", "u:101", 10001);
        assertTrue(repository.list(expired).isEmpty()); assertTrue(repository.parents(expired, List.of(id)).isEmpty());
        assertTrue(repository.children(expired, List.of(id)).isEmpty()); assertThrows(SessionAccessDeniedException.class, () -> repository.require(expired, id));
    }
    @Test void anonymousUpgradeRequiresMatchingProofAndAuthoritativeSessionOwner() {
        jdbc.update("update chat_session set owner_key='g:syntheticGuestKey' where session_id='session-a'");
        var guest = new AttachmentRepository.Scope("session-a", "g:syntheticGuestKey", 1000);
        create(id, guest);
        jdbc.update("update chat_session set owner_key='u:101' where session_id='session-a'");
        assertTrue(repository.list(guest).isEmpty()); // 老游客凭据在升级后立即失效
        repository.migrateGuest(alice, "wrongGuestProof"); assertTrue(repository.list(alice).isEmpty());
        repository.migrateGuest(alice, "syntheticGuestKey"); assertEquals(1, repository.list(alice).size());
        assertThrows(SessionAccessDeniedException.class, () -> repository.require(guest, id));
    }
    @Test void publishesTextParentsChildrenAndStatusInOneTransaction() {
        create(id, alice); repository.start(id, 1001);
        String text = "合成条款";
        var parsed = new AttachmentParser.Parsed(text, List.of(), 1, 1, List.of(), List.of(), "text");
        var parent = new AttachmentChunker.Parent(0, 0, 4, "正文", text);
        var child = new AttachmentChunker.Child(0, 0, 0, 4, "正文", text);
        assertThrows(RuntimeException.class, () -> repository.complete(id, parsed,
                new AttachmentChunker.Chunks(List.of(parent), List.of(child, child)), List.of(), List.of(), 1002));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_attachment_parent", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_attachment_chunk", Integer.class));
        assertEquals("PROCESSING", repository.require(alice, id).status()); assertEquals("", repository.text(alice, id));
    }
    @Test void deletedOrExpiredTaskCannotPublishLateResultsAndPurgeCascades() {
        ready(); repository.expire(alice, id);
        var parsed = new AttachmentParser.Parsed("late", List.of(), 1, 1, List.of(), List.of(), "text");
        repository.complete(id, parsed, new AttachmentChunker.Chunks(List.of(), List.of()), List.of(), List.of(), 1002);
        repository.purge(id, 1002);
        assertFalse(repository.exists(id));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_attachment_parent", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_attachment_chunk", Integer.class));
    }
    @Test void scopesMutationAndLimitsSessionCapacity() {
        create(id, alice);
        repository.expire(bob, id); assertEquals(1, repository.list(alice).size());
        assertFalse(repository.retry(bob, id));
        create("b".repeat(32), alice); create("c".repeat(32), alice);
        assertThrows(IllegalArgumentException.class, () -> create("d".repeat(32), alice));
        repository.fail(id, "synthetic failure", 1001);
        assertTrue(repository.retry(alice, id)); assertFalse(repository.retry(alice, id));
    }
    @Test void filterOrDatabaseFailurePropagatesInsteadOfReturningUnfilteredData() {
        ready(); jdbc.execute("drop table chat_session");
        assertThrows(RuntimeException.class, () -> repository.parents(alice, List.of(id)));
        assertThrows(RuntimeException.class, () -> repository.children(alice, List.of(id)));
        assertThrows(RuntimeException.class, () -> repository.require(alice, id));
    }
}

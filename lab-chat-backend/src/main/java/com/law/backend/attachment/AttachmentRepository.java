package com.law.backend.attachment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.law.backend.service.SessionAccessDeniedException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** 所有业务读取均经过同一个授权 SQL 条件；向量失败也不能移除它。 */
@Repository
public class AttachmentRepository {
    public record Scope(String sessionId, String ownerKey, long now) {
        public Scope {
            if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,64}") || ownerKey == null || "anon".equals(ownerKey))
                throw new SessionAccessDeniedException();
        }
    }
    public record FileRecord(String fileId, String name, String extension, String status, String kind,
                             int chars, int totalPages, int processedPages, List<Integer> failedPages,
                             List<String> warnings, String error, long expiresAt, long sizeBytes) {}
    public record Hit(String fileId, int chunkId, int parentId, int start, int end, String source, String text, double score) {}
    public record ParentRow(String fileId, int parentId, int start, int end, String source, String text) {}
    private static final String AUTH = " a.session_id=:session and a.owner_key=:owner and a.expires_at>:now"
            + " and exists(select 1 from chat_session s where s.session_id=a.session_id and s.owner_key=:owner) ";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    public AttachmentRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }
    private MapSqlParameterSource params(Scope scope) {
        return new MapSqlParameterSource().addValue("session", scope.sessionId()).addValue("owner", scope.ownerKey()).addValue("now", scope.now());
    }
    public List<FileRecord> list(Scope scope) {
        return jdbc.query("select a.* from chat_attachment a where" + AUTH + " order by a.created_at,a.file_id", params(scope), this::file);
    }
    public FileRecord require(Scope scope, String id) {
        List<FileRecord> files = jdbc.query("select a.* from chat_attachment a where" + AUTH + " and a.file_id=:id",
                params(scope).addValue("id", id), this::file);
        if (files.isEmpty()) throw new SessionAccessDeniedException();
        return files.get(0);
    }
    public void migrateGuest(Scope scope, String guestKey) {
        if (guestKey == null || !scope.ownerKey().startsWith("u:")) return;
        jdbc.update("update chat_attachment a set owner_key=:owner where a.session_id=:session and a.owner_key=:guest"
                        + " and exists(select 1 from chat_session s where s.session_id=a.session_id and s.owner_key=:owner)",
                params(scope).addValue("guest", "g:" + guestKey));
    }
    /** 会话行锁使容量检查与登记原子化；上传正文完成后才登记。 */
    public void create(Scope scope, String id, String name, String extension, long size, long expires, int maxFiles,
                       String parser, String chunker, String embedder) {
        tx.executeWithoutResult(status -> {
            var owner = jdbc.queryForList("select owner_key from chat_session where session_id=:session for update", params(scope), String.class);
            if (owner.isEmpty() || !scope.ownerKey().equals(owner.get(0))) throw new SessionAccessDeniedException();
            Integer count = jdbc.queryForObject("select count(*) from chat_attachment a where" + AUTH, params(scope), Integer.class);
            if (count != null && count >= maxFiles) throw new IllegalArgumentException("每个会话最多保留 " + maxFiles + " 个附件，请移除后重试");
            var p = params(scope).addValue("id", id).addValue("name", name).addValue("ext", extension).addValue("size", size)
                    .addValue("expires", expires).addValue("parser", parser).addValue("chunker", chunker).addValue("embedder", embedder);
            jdbc.update("insert into chat_attachment(file_id,session_id,owner_key,name,extension,size_bytes,status,parser_version,chunker_version,embedding_version,created_at,updated_at,expires_at)"
                    + " values(:id,:session,:owner,:name,:ext,:size,'QUEUED',:parser,:chunker,:embedder,:now,:now,:expires)", p);
        });
    }
    /** 工作者只接受尚未开始且未过期的任务；重试、重复调度不会并行处理同一个文件。 */
    public boolean start(String id, long now) {
        return jdbc.update("update chat_attachment set status='PROCESSING',updated_at=:now,error='' where file_id=:id and status='QUEUED' and expires_at>:now",
                new MapSqlParameterSource("id", id).addValue("now", now)) == 1;
    }
    public void complete(String id, AttachmentParser.Parsed parsed, AttachmentChunker.Chunks chunks,
                         List<float[]> vectors, List<String> warnings, long now) {
        tx.executeWithoutResult(status -> {
            var state = jdbc.queryForList("select status from chat_attachment where file_id=:id and expires_at>:now for update",
                    new MapSqlParameterSource("id", id).addValue("now", now), String.class);
            if (state.isEmpty() || !"PROCESSING".equals(state.get(0))) return;
            var p = new MapSqlParameterSource("id", id);
            jdbc.update("delete from chat_attachment_chunk where file_id=:id", p);
            jdbc.update("delete from chat_attachment_parent where file_id=:id", p);
            for (var parent : chunks.parents()) {
                jdbc.update("insert into chat_attachment_parent(file_id,parent_id,start_offset,end_offset,source,content) values(:id,:parent,:start,:end,:source,:content)",
                        new MapSqlParameterSource("id", id).addValue("parent", parent.id()).addValue("start", parent.start()).addValue("end", parent.end())
                                .addValue("source", parent.source()).addValue("content", parent.text()));
            }
            for (var child : chunks.children()) {
                float[] vector = vectors.size() > child.id() ? vectors.get(child.id()) : null;
                var c = new MapSqlParameterSource("id", id).addValue("chunk", child.id()).addValue("parent", child.parentId())
                        .addValue("start", child.start()).addValue("end", child.end()).addValue("source", child.source()).addValue("content", child.text());
                String value = vector == null ? "NULL" : "cast(:vector as vector)";
                if (vector != null) c.addValue("vector", vectorLiteral(vector));
                jdbc.update("insert into chat_attachment_chunk(file_id,chunk_id,parent_id,start_offset,end_offset,source,content,embedding) values(:id,:chunk,:parent,:start,:end,:source,:content," + value + ")", c);
            }
            boolean incomplete = !parsed.failedPages().isEmpty() || !parsed.warnings().isEmpty();
            jdbc.update("update chat_attachment set text_content=:text,chars=:chars,total_pages=:total,processed_pages=:processed,failed_pages=:failed,warnings=:warnings,kind=:kind,status=:status,updated_at=:now where file_id=:id",
                    p.addValue("text", parsed.text()).addValue("chars", parsed.text().length()).addValue("total", parsed.totalPages())
                            .addValue("processed", parsed.processedPages()).addValue("failed", encode(parsed.failedPages())).addValue("warnings", encode(warnings))
                            .addValue("kind", parsed.kind()).addValue("status", incomplete ? "PARTIAL" : "READY").addValue("now", now));
        });
    }
    public void fail(String id, String message, long now) {
        jdbc.update("update chat_attachment set status='FAILED',error=:error,updated_at=:now where file_id=:id and status in ('QUEUED','PROCESSING')",
                new MapSqlParameterSource("id", id).addValue("error", message).addValue("now", now));
    }
    public boolean retry(Scope scope, String id) {
        return jdbc.update("update chat_attachment a set status='QUEUED',error='',updated_at=:now where" + AUTH + " and a.file_id=:id and (a.status in ('FAILED','PARTIAL') or (a.status='READY' and a.warnings like '%向量化%'))",
                params(scope).addValue("id", id)) == 1;
    }
    public List<ParentRow> parents(Scope scope, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select p.* from chat_attachment_parent p join chat_attachment a on a.file_id=p.file_id where" + AUTH
                + " and a.file_id in (:ids) and a.status in ('READY','PARTIAL') order by a.created_at,p.file_id,p.parent_id",
                params(scope).addValue("ids", ids), (r, n) -> new ParentRow(r.getString("file_id"), r.getInt("parent_id"), r.getInt("start_offset"), r.getInt("end_offset"), r.getString("source"), r.getString("content")));
    }
    public List<Hit> children(Scope scope, List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select c.*,0 as score from chat_attachment_chunk c join chat_attachment a on a.file_id=c.file_id where" + AUTH
                + " and a.file_id in (:ids) and a.status in ('READY','PARTIAL') order by c.file_id,c.chunk_id",
                params(scope).addValue("ids", ids), this::hit);
    }
    public List<Hit> dense(Scope scope, List<String> ids, float[] vector, int limit, String version) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select c.*,1-(c.embedding <=> cast(:vector as vector)) as score from chat_attachment_chunk c join chat_attachment a on a.file_id=c.file_id where"
                + AUTH + " and a.file_id in (:ids) and a.embedding_version=:version and a.status in ('READY','PARTIAL')"
                + " and c.embedding is not null and vector_dims(c.embedding)=:dimensions order by c.embedding <=> cast(:vector as vector) limit :limit",
                params(scope).addValue("ids", ids).addValue("vector", vectorLiteral(vector)).addValue("dimensions", vector.length).addValue("limit", limit).addValue("version", version), this::hit);
    }
    public String text(Scope scope, String id) {
        var result = jdbc.queryForList("select a.text_content from chat_attachment a where" + AUTH + " and a.file_id=:id", params(scope).addValue("id", id), String.class);
        if (result.isEmpty()) throw new SessionAccessDeniedException();
        return result.get(0);
    }
    public void expire(Scope scope, String id) {
        jdbc.update("update chat_attachment a set expires_at=:now where" + AUTH + " and a.file_id=:id", params(scope).addValue("id", id));
    }
    public void expireSession(Scope scope) {
        jdbc.update("update chat_attachment a set expires_at=:now where" + AUTH, params(scope));
    }
    // 下列维护方法不对 HTTP 调用方暴露；先删除磁盘，再删除元数据，失败的清理可重试。
    public List<String> expired(long now) { return jdbc.queryForList("select file_id from chat_attachment where expires_at<=:now limit 100", Map.of("now", now), String.class); }
    public void purge(String id, long now) { jdbc.update("delete from chat_attachment where file_id=:id and expires_at<=:now", Map.of("id", id, "now", now)); }
    public boolean exists(String id) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat_attachment where file_id=:id)", Map.of("id", id), Boolean.class)); }
    public void recoverInterrupted(long before, long now) {
        jdbc.update("update chat_attachment set status='FAILED',error='处理被中断，请重试',updated_at=:now where status in ('PROCESSING','QUEUED') and updated_at<:before",
                Map.of("now", now, "before", before));
    }
    private Hit hit(ResultSet r, int n) throws SQLException {
        return new Hit(r.getString("file_id"), r.getInt("chunk_id"), r.getInt("parent_id"), r.getInt("start_offset"), r.getInt("end_offset"), r.getString("source"), r.getString("content"), r.getDouble("score"));
    }
    private FileRecord file(ResultSet r, int n) throws SQLException {
        return new FileRecord(r.getString("file_id"), r.getString("name"), r.getString("extension"), r.getString("status"), r.getString("kind"), r.getInt("chars"),
                r.getInt("total_pages"), r.getInt("processed_pages"), decode(r.getString("failed_pages"), new TypeReference<>() {}),
                decode(r.getString("warnings"), new TypeReference<>() {}), r.getString("error"), r.getLong("expires_at"), r.getLong("size_bytes"));
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("附件元数据编码失败"); } }
    private <T> T decode(String value, TypeReference<T> type) { try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("附件元数据损坏"); } }
    public static String vectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("空向量");
        for (float value : vector) if (!Float.isFinite(value)) throw new IllegalArgumentException("非法向量");
        return Arrays.toString(vector);
    }
}

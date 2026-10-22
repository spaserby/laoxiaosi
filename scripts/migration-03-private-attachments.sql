-- 私有附件与公共法条向量彻底分表；无公开资源 URL，应用查询必须同时限制会话、归属、期限。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS chat_attachment (
    file_id VARCHAR(32) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(80) NOT NULL,
    name VARCHAR(255) NOT NULL,
    extension VARCHAR(12) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    kind VARCHAR(20) NOT NULL DEFAULT 'text',
    error TEXT NOT NULL DEFAULT '',
    warnings TEXT NOT NULL DEFAULT '[]',
    text_content TEXT NOT NULL DEFAULT '',
    chars INTEGER NOT NULL DEFAULT 0,
    total_pages INTEGER NOT NULL DEFAULT 0,
    processed_pages INTEGER NOT NULL DEFAULT 0,
    failed_pages TEXT NOT NULL DEFAULT '[]',
    parser_version VARCHAR(80) NOT NULL,
    chunker_version VARCHAR(80) NOT NULL,
    embedding_version VARCHAR(160) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_attachment_scope ON chat_attachment(owner_key, session_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_attachment_expiry ON chat_attachment(expires_at);
CREATE TABLE IF NOT EXISTS chat_attachment_parent (
    file_id VARCHAR(32) NOT NULL REFERENCES chat_attachment(file_id) ON DELETE CASCADE,
    parent_id INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    source TEXT NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY(file_id, parent_id)
);
CREATE TABLE IF NOT EXISTS chat_attachment_chunk (
    file_id VARCHAR(32) NOT NULL REFERENCES chat_attachment(file_id) ON DELETE CASCADE,
    chunk_id INTEGER NOT NULL,
    parent_id INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    source TEXT NOT NULL,
    content TEXT NOT NULL,
    embedding vector,
    PRIMARY KEY(file_id, chunk_id)
);
-- 单会话最多三个有界文档，先在授权集合内精确搜索；避免 ANN 过滤不足和维度绑定。
CREATE INDEX IF NOT EXISTS idx_attachment_chunk_parent ON chat_attachment_chunk(file_id, parent_id);

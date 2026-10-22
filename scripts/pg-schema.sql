-- ============================================================
-- PostgreSQL 全量底账 schema（底账 + 记忆 + 同步指纹 + 向量表）
-- 向量表 vector_store 与余弦 HNSW 索引在此一并创建（后端 initialize-schema 已关闭：
--   表/索引由本脚本显式管理。2026-10-14 阿里云内核升级 20260915 后余弦索引缺陷已修复，
--   索引可用默认方式创建；完整事故记录见 application.yml 中 vectorstore 段注释）
-- 执行：psql -U postgres -d lab_legal_kb -f pg-schema.sql
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- vector_store.id 的 uuid_generate_v4() 依赖

-- 向量表 + 余弦 HNSW 索引（列结构与 Spring AI PgVectorStore 的 DDL 一致）
-- 索引名刻意与 Spring AI initialize-schema 保持一致（spring_ai_vector_index），
-- 日后若把 initialize-schema 改回 true 也不会重复建。
-- ★ 建索引后务必用 pg-hnsw-cosine-selfcheck.sql 复验"向量找自己"（应 100% 命中）：
--   阿里云 RDS 曾在内核 20260830 上把 vector_cosine_ops 静默改写成私有 rds_vector_cosine_ops，
--   该实现在 1024 维下返回错误邻居；20260915 内核已修复（自检 2/200 → 200/200）。
--   ★ 若要在**未修复**的实例上建（兜底方案）：必须同会话、在建索引之前先执行
--       SET rds_vector_cosine_ops_enabled = off;   -- 仅阿里云 RDS 特有 GUC，stock pgvector 会报错
--     否则又被改写成 rds_*；已建成的 rds_* 索引改 GUC 也救不活，只能 DROP 后重建。
CREATE TABLE IF NOT EXISTS vector_store (
  id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
  content   text,
  metadata  json,
  embedding vector(1024)
);
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
  ON vector_store USING hnsw (embedding vector_cosine_ops);

-- ★ 历史库升级补列：CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，
--   老库不会自动长出后加的列（doc_type / owner_key），必须显式 ALTER 兜底，
--   否则升级后应用一写入就报 column does not exist。
ALTER TABLE law_article  ADD COLUMN IF NOT EXISTS doc_type  VARCHAR(32);
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS owner_key VARCHAR(80);

-- 法条底账（唯一事实源，从 MySQL 迁来；列名与旧表一致保证迁移零映射）
CREATE TABLE IF NOT EXISTS law_article (
  id           BIGINT PRIMARY KEY,
  law_name     VARCHAR(255) NOT NULL,
  article_no   VARCHAR(64)  NOT NULL,
  category     VARCHAR(64),                   -- 业务领域：劳动/民事/商事/刑事/行政/经济/程序/宪法/环境/社会
  doc_type     VARCHAR(32),                   -- 效力位阶：宪法/法律/行政法规/司法解释/监察法规
  title        VARCHAR(255),
  version_info VARCHAR(128),
  chapter_info VARCHAR(128),
  section_info VARCHAR(128),
  content      TEXT NOT NULL,
  create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted      SMALLINT DEFAULT 0
);
-- 稀疏路检索（取代 MySQL FULLTEXT ngram）：pg_trgm GIN 支持中文 ILIKE 加速
CREATE INDEX IF NOT EXISTS idx_law_content_trgm ON law_article USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_law_name_trgm ON law_article USING gin (law_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_law_deleted ON law_article(deleted);
CREATE INDEX IF NOT EXISTS idx_law_doc_type ON law_article(doc_type);

-- 用户表（MySQL 表名 user 是 PG 保留字，迁 PG 更名 app_user）
CREATE TABLE IF NOT EXISTS app_user (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(64) UNIQUE,          -- 手机注册用户可空（PG 唯一约束允许多 NULL）
  password_hash VARCHAR(100) NOT NULL,
  phone         VARCHAR(20) UNIQUE,
  role          VARCHAR(20) NOT NULL DEFAULT 'USER',
  status        SMALLINT NOT NULL DEFAULT 0,
  avatar        VARCHAR(16) DEFAULT NULL,    -- 头像编号 1-12（NULL=默认图标）
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 会话索引（取代 Redis Hash chat:session:index；与消息同库，悬空问题根除）
-- 会话索引（取代 Redis Hash chat:session:index；与消息同库，悬空问题根除）
-- owner_key：会话归属，列表/读取/删除/停止生成按它鉴权（P0 安全整改）
--   登录 u:{userId} / 匿名 g:{guestKey}（前端 localStore 随机串，X-Guest-Key 头）
--   / legacy = 整改前的历史数据，任何调用方都读不到
CREATE TABLE IF NOT EXISTS chat_session (
  session_id VARCHAR(64) PRIMARY KEY,
  title      VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  owner_key  VARCHAR(80)
);
CREATE INDEX IF NOT EXISTS idx_session_updated ON chat_session(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_owner ON chat_session(owner_key, updated_at DESC);

-- 会话消息（取代 Redis RList chat:memory:{sid}；id 自增即时序）
CREATE TABLE IF NOT EXISTS chat_message (
  id         BIGSERIAL PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  role       VARCHAR(20) NOT NULL,
  content    TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id, id);

-- 早期历史滚动摘要（取代 Redis Bucket chat:summary:{sid}）
CREATE TABLE IF NOT EXISTS chat_summary (
  session_id VARCHAR(64) PRIMARY KEY,
  content    TEXT NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量同步指纹底账（取代 Redis Hash rag:article:hash；value 格式 "md5;docId1,docId2"）
CREATE TABLE IF NOT EXISTS rag_ledger (
  article_id   VARCHAR(32) PRIMARY KEY,
  ledger_value TEXT NOT NULL
);


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

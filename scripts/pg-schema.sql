-- ============================================================
-- PostgreSQL 全量底账 schema（底账 + 记忆 + 同步指纹）
-- 向量表 vector_store 由 Spring AI PgVectorStore initialize-schema 自动创建
-- 执行：psql -U postgres -d lab_legal_kb -f pg-schema.sql
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 法条底账（唯一事实源，从 MySQL 迁来；列名与旧表一致保证迁移零映射）
CREATE TABLE IF NOT EXISTS law_article (
  id           BIGINT PRIMARY KEY,
  law_name     VARCHAR(255) NOT NULL,
  article_no   VARCHAR(64)  NOT NULL,
  category     VARCHAR(64),
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
CREATE TABLE IF NOT EXISTS chat_session (
  session_id VARCHAR(64) PRIMARY KEY,
  title      VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_session_updated ON chat_session(updated_at DESC);

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

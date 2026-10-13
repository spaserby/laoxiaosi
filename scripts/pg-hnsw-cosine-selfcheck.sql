-- ============================================================
-- HNSW 余弦索引自检：用向量"找自己"，Top-1 必须回到自己且距离 = 0
-- 用途：30 秒判定实例的余弦 HNSW 是否可信。背景（2026-10-13 线上事故）：
--   阿里云 RDS PostgreSQL 的 vector 分叉会在 CREATE INDEX 时把 vector_cosine_ops
--   静默换成自家的 rds_vector_cosine_ops，该实现在 1024 维上返回错误邻居
--   （实测自身命中 0/20，社区 opclass 同一份数据 20/20），导致 RAG 强制检索
--   打底 0 命中且无任何报错。本脚本在任何 PG 上都可跑；若 self_hits 明显低于 total，
--   说明索引不可信，按下面处理：
--     SET rds_vector_cosine_ops_enabled = off;   -- 仅阿里云 RDS 有此 GUC
--     DROP INDEX <坏索引>;  CREATE INDEX <新索引> USING hnsw (embedding vector_cosine_ops);
--   （GUC 只需 DDL 时生效；已建成的 rds_* 索引改 GUC 救不活，必须重建。）
-- 用法：psql -U <user> -d <db> -f pg-hnsw-cosine-selfcheck.sql   （结尾自动清理临时表）
-- ============================================================
\pset pager off
DROP TABLE IF EXISTS _hnsw_selfcheck;
CREATE TABLE _hnsw_selfcheck (id int PRIMARY KEY, v vector(1024));

-- 确定性伪随机向量（md5(行号:维度号) 取 28 位）——可重复、保证每行互不相同
INSERT INTO _hnsw_selfcheck (id, v)
SELECT g, ('[' || string_agg(val::text, ',' ORDER BY i) || ']')::vector
FROM (
  SELECT g, i,
         ((('x' || substr(md5(g::text || ':' || i::text), 1, 7))::bit(28)::int + 0.5) / 268435456.0) AS val
  FROM generate_series(1, 200) g CROSS JOIN generate_series(1, 1024) i
) s
GROUP BY g;

CREATE INDEX _hnsw_selfcheck_cos ON _hnsw_selfcheck USING hnsw (v vector_cosine_ops);
ANALYZE _hnsw_selfcheck;

-- ① 索引真正落在哪个 opclass：出现 rds_vector_cosine_ops 即已被阿里云分叉接管（高危信号）
SELECT i.relname AS index_name, c.opcname AS opclass
FROM pg_index x
JOIN pg_class i ON i.oid = x.indexrelid
JOIN unnest(x.indclass) AS oc(oid) ON true
JOIN pg_opclass c ON c.oid = oc.oid
WHERE x.indrelid = '_hnsw_selfcheck'::regclass AND i.relname <> '_hnsw_selfcheck_pkey';

-- ② 强制走索引做"向量找自己"（enable_seqscan=off 保证不是退化成精确扫描）
SET enable_seqscan = off;
SET enable_sort = off;
SET hnsw.ef_search = 200;
SELECT count(*) AS total,
       count(*) FILTER (WHERE self_id = id) AS self_hits
FROM (
  SELECT s.id, (SELECT t.id FROM _hnsw_selfcheck t ORDER BY t.v <=> s.v LIMIT 1) AS self_id
  FROM _hnsw_selfcheck s
) q;
\echo '判定：self_hits 必须 = total（如 200/200）。明显偏低 = 余弦 HNSW 索引不可信，按脚本头部处置'

-- ③ 明细核对（可选）：同一行自身距离应为 0.0000，且 dist 必须非递减
SELECT id AS qid, v::text AS qv FROM _hnsw_selfcheck WHERE id = 3 \gset
SELECT id, id = 3 AS is_self, round((v <=> :'qv'::vector)::numeric, 4) AS dist
FROM _hnsw_selfcheck
ORDER BY v <=> :'qv'::vector
LIMIT 5;

DROP TABLE _hnsw_selfcheck;

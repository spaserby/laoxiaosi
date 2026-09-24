-- ============================================================
-- 劳小司 · 数据库初始化脚本（单文件，陌生人可直接执行）
--   结构：scripts/pg-schema.sql 为单一事实源（本文件已内联）
--   数据：law_article 全量（从现役库 1:1 导出，共 @@ROWS@@ 条）
--   幂等：可重复执行（CREATE IF NOT EXISTS + ON CONFLICT UPSERT）
-- 用法：
--   本机：  psql -U postgres -d lab_legal_kb -f db/init.sql
--   Docker：docker exec -i lab-pg psql -U postgres -d lab_legal_kb < db/init.sql
-- 执行后：
--   1) 启动后端（见 deploy/runbook.md）
--   2) 注册账号 + BOOTSTRAP_ADMIN 提权
--   3) 管理端「向量库同步」重建向量（embedding 一次性费用）：
--        全量同步：一次把库内全部法条向量化
--        按法条勾选：只同步选中的法律，未勾选法条的向量不受影响（适合分批次、控成本）
-- 数据来源：
--   law_article 全部来自国家法律法规数据库（flk.npc.gov.cn）官方 docx 原件，
--   仅收录现行有效版本；重新导入/扩充见 scripts/ingest-law-map.tsv 与 IngestOfficial.java
-- ============================================================

@@SCHEMA@@

-- ============================================================
-- 数据区：法条底账（现役库 1:1 导出）
--   ON CONFLICT (id) DO UPDATE：重跑即刷新，不产生重复行
-- ============================================================
@@DATA@@

-- ============================================================
-- 验收查询（应返回 @@ROWS@@）
-- ============================================================
-- select count(*) from law_article where deleted = 0;

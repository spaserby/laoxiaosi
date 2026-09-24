-- ============================================================
-- 迁移 02：会话归属（P0 安全整改）
--   ① chat_session 增加 owner_key：登录 u:{userId} / 匿名 g:{guestKey}
--   ② 按 (owner_key, updated_at) 建索引，列表查询走索引
--   ③ 存量会话无归属 → 标记 legacy：任何调用方都读不到（安全的默认，
--      宁可有人的历史列表少几条，也不能让全站会话被匿名读取）
-- 幂等：可重复执行
-- ============================================================

alter table chat_session add column if not exists owner_key varchar(80);
create index if not exists idx_session_owner on chat_session(owner_key, updated_at desc);

update chat_session set owner_key = 'legacy' where owner_key is null;

-- 验收：应全部有归属（legacy 表示整改前的历史数据）
select coalesce(owner_key, '<NULL>') as owner_key, count(*) as cnt
from chat_session group by 1 order by 2 desc;

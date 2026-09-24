-- ============================================================
-- 迁移 01：法条底账字段与存量清洗（2026-09-24 官方源重建配套）
--   ① law_article 增补 doc_type（效力位阶）——新库由 pg-schema.sql 建出，老库靠本步兜底
--   ② 删除 14 条与正式导入重复的手挑样本行（同号重复会让同一条法条在检索里出现两次）
--   ③ 《诉讼费用交纳办法》归入「程序」域（国务院令第 481 号，行政法规）
--   ④ 补录《女职工劳动保护特别规定》附录（官方 docx 含实体规则，此前被解析器截断）
-- 幂等：可重复执行（DELETE / UPDATE 均按主键与法名限定）
-- 说明：正文数据由 db/init.sql 全量 upsert 覆盖，本文件只处理"老库多出来的行"
-- ============================================================

alter table law_article add column if not exists doc_type varchar(32);

delete from law_article where id in
  (1005, 1006, 1007, 1008, 2011, 2012, 2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020);

update law_article set category = '程序' where law_name = '《诉讼费用交纳办法》';

-- 附录正文以 db/init.sql 的 900001 行为准（此处仅保证老库存在该条目时可被覆盖更新）
select 'law_article(deleted=0)' as k, count(*)::text as v from law_article where deleted = 0;

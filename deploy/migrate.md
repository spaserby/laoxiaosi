# 数据迁移决策（本地 PG → 服务器 PG）

## 私有附件升级

现有库先执行 `scripts/migration-03-private-attachments.sql`，再启动新后端；`deploy.sh` 已包含该幂等迁移。
新库的 `scripts/pg-schema.sql` 和 `db/init.sql` 同样包含附件表。三个附件表与公共法条、公共向量分离，未改变法条分块。
不要从业务库向测试环境导出用户附件。原件保存在 Compose 的 `attachment_data` 私有卷，必须与数据库共同保护；
删除数据库中的附件记录后，原件由孤儿回收处理；正常删除/到期会同步清除原件、正文、块与向量。
附件到期立即不能被接口读取，后台每分钟重试物理回收；数据库或磁盘不可用时回收会延后。

旧版本 Redis 附件没有可信归属，不能自动导入；它们按原 TTL 消失，用户需重新上传。
回滚旧版本前停止新请求，保留附件卷与新表；旧代码不会使用新附件。不做破坏性 DROP 或历史数据改写。

## 两条路线

| 路线 | 内容 | 适用 | 成本 |
|------|------|------|------|
| A 全量 dump/restore（推荐） | 含 `law_article` + `app_user` + **`vector_store`**（pgvector 可随 dump 走） | 本地已有同步好的向量 | 零 embedding 费用 |
| B 重建 + 重同步 | **db/init.sql（单文件：结构+全量法条数据，幂等可重跑）** → 管理端"立即同步"重跑 embedding | 本地向量缺失/想换维度/开源用户首次部署 | 468 条 embedding 一次性费用（几分钱级） |

## 路线 A 命令（本地执行导出 → 服务器执行导入）

```bash
# 本地：导出（含向量表；--no-owner 避免用户差异）
pg_dump -h 127.0.0.1 -U postgres -d lab_legal_kb --no-owner --no-privileges -f lab_legal_kb.dump

# 传输
scp lab_legal_kb.dump root@<服务器>:~/

# 服务器：compose 起库后导入（库为空时）
docker exec -i lab-pg pg_restore -U postgres -d lab_legal_kb --no-owner < lab_legal_kb.dump
```

导入后验证：`select count(*) from law_article where deleted=0;`（应 468）与
`select count(*) from vector_store;`（应 ≥468）。

## 路线 B 命令（服务器 / 开源用户首次部署）

```bash
# 单文件搞定：建表（全部底账表）+ 468 条法条（upsert 幂等，可重复执行）
docker exec -i lab-pg psql -U postgres -d lab_legal_kb < db/init.sql
# 本机部署同理：psql -U postgres -d lab_legal_kb -f db/init.sql

# 向量：启动后端 → 登录 ADMIN → 设置 → 向量库同步 → 立即同步
```

说明：`db/init.sql` 由 `scripts/GenInitSql.java` 从现役库 1:1 生成（结构内联自
pg-schema.sql），含 `law_article` 全部字段（含章节属/版本年份）；
`labor-src/*.html` 保留为官方源溯源存档（不做运行时依赖）。

## 禁止事项（人工确认清单成员）

- 禁止对非空库直接 restore（会主键冲突/双份数据）；先 `select count(*) from law_article;` 确认空库；
- 禁止 `drop schema public cascade` 类操作未经人工确认；
- Redis 不做迁移（缓存态，重启自然重建；会话丢失可接受）。

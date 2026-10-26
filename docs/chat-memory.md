# 原文历史与模型记忆

`chat_message` 是用户原文归档，窗口大小只控制模型上下文，不删除数据库记录。
历史已被旧版本删除的原文不恢复，也不从摘要反推。正常删除会话仍会删除原文和内部摘要。

## 读取与授权

- `ConversationHistoryService` 查询原文；SQL 同时限定 `session_id`、`owner_key` 和 USER/ASSISTANT 角色。
- `GET /session/{id}/messages?limit=50&beforeMessageId=...` 默认最近50条、上限100条。
  `data` 为时间正序，外层另有 `hasMore` 与 `nextCursor`。游标和 ID 为十进制字符串，防止前端 BIGINT 精度丢失。
- 历史中有 `id/role/content/createdAt/turnId/status`；没有摘要、提示词或工具消息。
- 前端按 ID 去重，加载更早记录时保留滚动位置；仅渲染 user/assistant，加载失败可重试。
- `PgChatMemory.get` 仅供模型：内部摘要、覆盖缺口提示与最近4个终态轮次，再交统一 token 预算裁剪。
  未纳入本次上下文的旧原文仍可分页查看。无身份凭据的请求不读取或保存已有会话历史。

## 生成生命周期

每个新轮次有独立 `turn_id`。用户消息先以 RUNNING 保存，生成结束时在短事务里更新终态并追加助手回复。
同会话行锁排除并行轮次；终态条件更新保证重复回调只落一次助手回复。
终态包括 COMPLETE、STOPPED、FAILED、INTERRUPTED。错误、主动停止、浏览器断线均保留已接收的部分答复并标记状态，
不会把推理流、阶段事件或引用事件写入原文。审校重试清空第一次尝试，仅保存最终尝试。

正常完成的回复提交成功后才发出 SSE `done`。断线收尾在 boundedElastic 执行，包含阻塞 prepare 与取消竞态处理。
进程被强制终止时尚未落库的流式 token 无法保证保留；数据库中超过60分钟的 RUNNING 记录由后台标记 INTERRUPTED，
不伪造助手回答。该期限必须大于允许的单次生成时长；目前没有逐 token 数据库写入或客户端断点续传。

会话删除将摘要、原文和索引置于同一事务；与生成收尾共用会话行锁。
迟到生成只更新仍在运行的原有 `turn_id`，摘要任务只能更新其原有租约行，均不能重新创建已删除的内容。
附件删除沿用现有私有文件清理流程，SQL 与磁盘操作没有跨资源事务。

## 增量摘要

摘要行记录 `covered_until_message_id`、`version`、租约及重试时间。每15秒扫描数据库，每实例只运行一个扫描，
每次最多10个会话、每会话最多8个旧轮次；最近4轮留作原文。数据库租约排除多实例重复执行，失效任务不能提交。
模型调用在事务外，提交用版本、原游标和随机租约同时匹配的 CAS。摘要内容和游标在同一条 SQL 更新。

摘要包含用户陈述/待核实事实、日期金额、来源消息 ID 与待解决问题；不将助手建议升格为已核实证据。
单次输入保守估算不超过12000 token，摘要上限1500 token。超长单轮分段增量合并，整个批次成功后才前进游标。
任何模型错误、空输出、预算超限均保留原文及原游标，15秒起指数退避、最多1小时后继续重试；重启后扫描接续。
关闭摘要仍保留全部原文。摘要滞后时模型只拿有界窗口并收到覆盖缺口提示，不把全部历史塞入模型。

旧摘要保留并标记 `legacy=true`，其早期覆盖范围未知；从现有原文继续增量处理，不宣称原文已经完整恢复。
摘要是有损上下文，不能代替证据原文、跨会话用户画像或法律事实核验。

## 升级

1. 备份私有数据库，停止旧后端，避免旧裁剪逻辑在迁移期间继续删除消息。
2. 使用 `psql -v ON_ERROR_STOP=1 -f scripts/migration-04-conversation-history.sql` 执行增量迁移。
3. 启动新后端和前端。`deploy/deploy.sh` 已按这个顺序接入；首次升级不要使用 `--no-db`。
4. 用合成咨询发送至少10轮，刷新后分页确认全部原文；确认后台摘要游标推进、前端没有摘要气泡。

新库使用 `scripts/pg-schema.sql` 或公开 `db/init.sql`。已有库先执行迁移04再运行 schema。
`python scripts/sync_init_schema.py` 可同步公开初始化脚本中的 schema，不读取数据库、不改变公开法条数据区。
迁移为加列和索引，幂等重跑保留已推进游标。回滚旧应用会重新启用物理裁剪，应修复后前进发布，不能直接退回旧后端。

## 验证

```bash
mvn -pl lab-chat-backend -am test
cd super-law-frontend
node --test src/stores/chat-title.test.js src/stores/chat-history.test.js src/utils/attachments.test.js
npm run build
```

默认测试使用独立 H2 和真实 Spring 事务/MyBatis，模型边界使用合成响应。
覆盖50轮100条留存、授权分页、摘要失败/重启重试、过期任务和删除竞态、长轮次预算、断线配对及前端真实组件渲染。
`ConversationPostgresTest` 仅显式指定本机测试库时执行，随机建 schema 并在结束后清理，不读取应用数据源。

```bash
# 测试密码只经 MEMORY_TEST_PG_PASSWORD 环境变量提供；不要写入命令或文档。
mvn -pl lab-chat-backend -am -Dtest=ConversationPostgresTest \
  -Dmemory.test.pg.url=jdbc:postgresql://127.0.0.1:5432/postgres \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

# 账号安全与回答证据修复

本次修复覆盖输出净化、凭据撤销、隐私日志、短信并发限额、跨账号缓存、模型降级、中文输入、错误缓冲和历史证据回放。

## 升级顺序

1. 备份私有数据库，停止旧后端。
2. 先确认历史迁移04已执行，再运行 `psql -v ON_ERROR_STOP=1 -f scripts/migration-05-security-and-evidence.sql`。
3. 部署新版后端与前端。`deploy/deploy.sh` 已包含迁移05；新库初始化脚本也已同步。
4. 现有旧版 JWT 没有认证版本字段，升级后需要重新登录。此次迁移保留账号、密码哈希、原文和摘要。

新增 `app_user.auth_version`，修改密码时同一条 SQL 更新哈希并递增版本。JWT 每次请求在弹性线程读取当前账号状态、角色与版本，
无效凭据返回401，匿名公共请求继续可用。封禁、删除账号会被拒绝；降权按当前角色生效，不再沿用旧 token 的管理权限。
角色切换不强制退出当前账号；密码重置撤销其所有旧 token。未使用认证结果缓存，避免撤权传播延迟。

## 输出与缓存

回复先解析 Markdown，再使用 DOMPurify 的 HTML profile 净化；禁用事件属性、危险协议、表单与嵌入内容。
没有 DOM 的 SSR 环境只输出转义文本。不能在净化后重新拼接不可信 HTML。
失败回复和无证据回复显示通用 AI 答复标签，不宣称“依据已核验”。

登录、注册、退出和跨标签页身份变化会停止当前 SSE、清理消息/分页/附件缓存及 object URL，然后加载当前身份的会话列表。
每次身份切换递增本地身份版本；旧请求、旧 SSE 和定时器不能把上个身份的数据写回来。
网络暂时不可用不会被当成凭据过期自动退出，明确的401/403才触发登录态清理。

## 短信和日志

短信重发间隔和日额度由同一 Redis Lua 操作原子预占，同一手机号的键使用相同 hash tag，兼容集群槽位要求。
发送失败按独立预占令牌回滚日计数和重发窗口，不能撤销后来的请求；投递成功后不因清理失败而返还额度。
仅在实际使用的私有 Redis 中运行；测试使用显式指定的独立本机 Redis，不调用短信供应商。

日志保留操作、长度、路线和错误类别，不记录咨询正文、检索问题、工具金额/日期、验证码或供应商异常正文。
手机号脱敏；mock 模式也不记录验证码。纯本地调试若需要合成验证码，可显式设置 `SMS_MOCK_ECHO=true` 从响应读取；
生产必须保持关闭并使用真实短信通道。

## 尝试隔离与证据

切换备用模型和审校重试都先发送 retry，清理前后端正文、推理、引用和审校缓冲，再恢复本轮基础证据与必要警示。
失败尝试的部分正文不再与备用完整回答拼接。正常完成、错误与主动停止统一清理前端缓冲定时器；
错误时先保留已接收的正文，再追加错误说明。中文组合输入期间 Enter 不触发发送。

新增 `chat_message.evidence` 文本 JSON：随助手回复在同一事务里保存当轮引用与附件来源、覆盖信息。
只保存来源元数据，不复制附件正文；保留 fileId/parentId 等标识，由已有授权和期限检查控制原文访问。
历史接口恢复 citations 与 attachmentContext；旧消息没有保存过这些数据，会标记 evidenceAvailable=false，不能重新检索后冒充历史证据。
附件过期或被删除时，历史中仍可看到当时的来源信息，点击原文会明确提示不可用，不能绕过当前授权。

## 验证

```bash
mvn -pl lab-chat-backend -am test
cd super-law-frontend
node --test src/stores/review-fixes.test.js src/stores/chat-history.test.js src/stores/chat-title.test.js src/utils/attachments.test.js
npm run build
```

本机 PostgreSQL 和 Redis 验证显式启用，使用独立测试实例/随机 schema，不读取应用生产配置：

```bash
# MEMORY_TEST_PG_USER / MEMORY_TEST_PG_PASSWORD 使用私有环境变量。
mvn -pl lab-chat-backend -am \
  -Dmemory.test.pg.url=jdbc:postgresql://127.0.0.1:55439/postgres \
  -Dsms.test.redis.address=redis://127.0.0.1:55449 test
```

PostgreSQL 验证迁移重复执行与真实分页/CAS；Redis 验证并发发送只放行一次、日额度和失败回滚。
外部模型、短信通道使用合成边界；浏览器 HTML 净化通过 jsdom+DOMPurify 验证，同时保留正常 Markdown 表格和链接。

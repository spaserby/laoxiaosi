# 会话附件

上传原件流式写入私有目录，单线程解析后，正文、结构化父子块和向量在同一事务发布到独立附件表。
公共法条表与向量表不变。队列在进程内有界；进程中断后的任务会显示失败并允许重试，不宣称可靠消息队列。

## 行为

- 支持 TXT/MD/PDF/DOCX 和 PNG/JPEG/WebP。DOCX 按正文顺序读取段落、表格，附带页眉页脚、脚注尾注。
- PDF 逐页提取文字；文字稀少且含图的页渲染后做完整文字转录。OCR 是独立调用，短 caption 不参与 OCR。
- 接受上限默认文档20MB、图片8MB/1600万像素、PDF200页、正文20万字符、每会话3个附件。
  超限明确拒绝并提示拆分；不会以截取前2万字或前30页冒充成功。
- 解析/OCR 缺页或图表未转录时状态为 `PARTIAL`，显示失败页和处理提示。OCR 与图像理解均须核对原件。
  复杂版式、公式、文字框与非正文修订等格式不保证保真；不得将程序覆盖率视为识别准确率或法律风险检出率。
- 父块先按标题/条款拆分，再按段落/句末控制大小；父块默认1536 token、子块512 token。
  仅硬切采用最多128 token重叠，保留源位置和原文偏移。CL100K 加安全系数是保守估算，并非供应商精确 tokenizer。
- 短文能放入共同预算时直接注入全文；长文每轮按改写后的问题做关键词/向量召回、RRF、可选精排与父/邻块扩展。
  关键词通道使用中文二元词项匹配，不宣称 BM25。没有相关命中时明确提示，不能推断全文不存在该约定。
- `qa` 是相关片段问答。`review` 遍历全部父块，逐章提取风险、例外及证据后分层汇总，报告实际完成比例；
  最多64个父块，一次只执行一个全文审查。任一章节失败或原件解析不完整，都不能显示完整覆盖。
- 全局预算计算系统规则、法条、历史、问题、附件、图片及输出余量。全部附件共用8000 token初始额度。
  最新完整对话优先；超限明确拒绝，不静默丢弃附件尾部。附件轮使用无工具客户端，防止后续工具结果绕过预算。

## 身份和期限

所有接口必须带登录 `Authorization` 或匿名 `X-Guest-Key`；附件同时绑定 `owner_key`、`session_id` 和有效期。
读取、来源、重试、删除、检索都使用相同的 SQL 过滤，数据库过滤失败不能去掉条件重试。
同一浏览器匿名转登录须同时持有原游客凭据，且会话归属已升级，才能迁移附件归属；旧游客身份立即不能读取。

原件名不参与磁盘路径，目录不映射 Web 静态资源。默认24小时到期，逻辑访问立即拒绝；后台每分钟回收原件、全文、块与向量。
删除会话也会删除附件。回收失败会重试，因此物理删除可能延后；数据库和私有卷的备份需另行设置保留策略。
后续轮即使没有重新携带 fileIds，也会检索同一会话的有效附件。已知无效或未就绪附件不能被静默跳过。

## 接口

| 请求 | 用途 |
| --- | --- |
| `POST /chat/files?sessionId=...` multipart `files` | 返回 `QUEUED` 元数据，最多3个 |
| `GET /chat/files?sessionId=...` | 查询会话附件及处理状态 |
| `POST /chat/files/{id}/retry?sessionId=...` | 重试失败、部分解析或向量化失败附件，不更改有效期 |
| `DELETE /chat/files/{id}?sessionId=...` | 立即使附件不可读并回收 |
| `GET /chat/files/{id}/source?sessionId=...&parentId=...` | 查询受鉴权的引用原文 |
| `POST /chat/stream` | JSON中增加 `mode: "qa" / "review"`，默认 `qa` |

状态为 `QUEUED → PROCESSING → READY/PARTIAL/FAILED`；文件列表不返回过期记录。
SSE 的 `attachment` 事件包含 `sources`、`warnings`、`selectedSections`、`totalSections`、`fullCoverage`，不传全文。
前端把附件留在所属会话，轮询异步处理；状态不可用或未就绪时禁止发送。原文预览使用纯文本，不能执行附件 HTML。

## 升级与验收

先运行 [迁移03](../scripts/migration-03-private-attachments.sql)，挂载 Compose 私有卷后重建应用，详见 [迁移说明](../deploy/migrate.md)。
旧 Redis 附件没有可信归属，需重新上传。解析器、分块器、embedding版本写入元数据；更换 embedding 时同步版本并重试旧附件。
默认单后端实例；多实例需共享私有文件存储，并替换进程内任务队列及实例内审查并发限制。

```bash
mvn -B -pl lab-chat-backend -am test
node --test super-law-frontend/src/utils/attachments.test.js
```

离线测试使用生成的合成资料，覆盖长文尾部、31页PDF、表格、跨块例外、混合检索、追问、预算、越权、到期回收和事务失败。
真实 pgvector 测试仅在显式指定本机测试库时执行，测试会创建并删除独立临时 schema；不读取业务数据源。
通过环境变量 `ATTACHMENT_TEST_PG_USER` / `ATTACHMENT_TEST_PG_PASSWORD` 配置测试凭据后运行：

```bash
mvn -pl lab-chat-backend -Dtest=AttachmentPostgresTest \
  -Dattachment.test.pg.url=jdbc:postgresql://127.0.0.1:5432/attachment_test test
```

上线前还需用真实模型验证 OCR、embedding 和精排兼容性，以合成金标评估尾部召回、跨条款条件/例外召回、无答案拒答、引用定位与全文覆盖。
分块参数是初始值，不能据离线功能测试宣称检索质量已经达到最优。

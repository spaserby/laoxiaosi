# 会话附件

选中文档和图片后，劳小司把原件流式保存到私有目录。发送时原件随问题交给页面提示的接收方。
前端在选文件时检查格式、数量和大小，后端复核真实字节、文件格式和 PDF 总页数。处理失败时保留本轮输入和已选附件。

## 片段问答与逐章审查

这两条链路留在后端。需要使用旧接口时，可设置 `ATTACHMENT_LEGACY_MODES_ENABLED=true`。
- 支持 TXT、Markdown、PDF、DOC、DOCX 和 PNG/JPEG/WebP。DOCX 按正文顺序读取段落、表格，附带页眉页脚、脚注尾注。
  旧版 DOC 在片段问答或逐章审查中提取正文与表格文字，并提示核对原件中的图表和布局。
- PDF 逐页提取文字；文字稀少且含图的页渲染后做完整文字转录。OCR 是独立调用，短 caption 不参与 OCR。
- 接受上限默认文档 20 MiB、图片 8 MiB / 1600 万像素、PDF 200 页、正文 20 万字符。每轮最多 3 个附件，每会话最多保留 12 个有效原件。
  单实例默认最多同时接收 4 个上传，私有原件合计最多 512 MiB。上传开始时预留该格式的单文件额度。
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

## 原件直传

在私有环境配置 `ATTACHMENT_DEFAULT_TARGET` 选择公开页面唯一的接收方，默认 `deepseek-files`，也可配置为 `openai-responses`。
同时启用对应的 `ATTACHMENT_DEEPSEEK_DIRECT_ENABLED` 或 `ATTACHMENT_OPENAI_DIRECT_ENABLED` 并提供 API Key。
`application.yml` 的 `legal.ai.files.native.targets` 指向 `legal.ai.model.providers` 中的端点，并配置模型和每轮文件限额。
输入框展示接收方和 PDF 页数上限。每轮默认最多 3 个文件，文档上限 20 MiB，单图上限 8 MiB，实际单件上限同时受该接收方的总额约束。法条仍由现有检索流程提供。
直传跳过应用内的附件 OCR、切块和向量化。供应商会按自己的协议解析，页面提示用户核对原件。

| 协议 | 原件处理 | 默认每轮限额 |
| --- | --- | --- |
| DeepSeek Files | PDF、DOC、DOCX 使用版面识别，图片作为图像输入。文件设置 1 小时过期，调用结束尝试删除 | 20 MiB，PDF 合计 30 页 |
| OpenAI Responses | 文件和图片随请求发送，设定 `store: false`。PDF 可读取页面图像，Word 文档按文本提取 | 8 MiB，PDF 合计 20 页 |

供应商拒绝、失败或超时时显示错误，输入仍可修改或重试。只有 YAML 指定的接收方能收到本轮原件。
直传使用本轮问题和所选原件，追问时请写明要核对的事项。接收方或限额有变化时，前端更新提示并等待用户再次发送。
供应商的数据处理和留存仍适用各自的政策。建议用合成资料确认账号支持的模型、版式识别效果和费用后启用。

[DeepSeek 文件解析](https://api-docs.deepseek.com/guides/document_parsing/) · [OpenAI 文件输入](https://developers.openai.com/api/docs/guides/file-inputs) · [OpenAI 数据控制](https://developers.openai.com/api/docs/guides/your-data)

## 身份和期限

列出状态、上传、下载和其他附件操作须带登录 `Authorization` 或匿名 `X-Guest-Key`。附件同时绑定 `owner_key`、`session_id` 和有效期。
读取、下载、来源、重试、删除、检索都使用相同的 SQL 过滤，数据库过滤失败不能去掉条件重试。
同一浏览器匿名转登录须同时持有原游客凭据，且会话归属已升级，才能迁移附件归属；旧游客身份立即不能读取。

原件名不参与磁盘路径，目录不映射 Web 静态资源。默认24小时到期，逻辑访问立即拒绝；后台每分钟回收原件、全文、块与向量。
删除会话也会删除附件。回收失败会重试，因此物理删除可能延后；数据库和私有卷的备份需另行设置保留策略。
发送后，附件作为卡片显示在本轮用户消息下，刷新历史也可以下载。有需要时可删除原件，已有问答继续保留。
有效期内点「用于下一条」即可在同会话重新选用。
原件从应用的私有卷提供，切换模型端点不会改变下载地址或有效期。旧消息没有已保存的附件关联，历史页仅对新绑定的附件显示卡片。

## 接口

| 请求 | 用途 |
| --- | --- |
| `GET /chat/files/policy` | 当前接收方、版本、格式和限额。附件不可用时返回 204 |
| `POST /chat/files?sessionId=...&attachmentPolicyVersion=...` multipart `files` | 按实际字节限制这一批，保存原件并返回 `STORED` 元数据 |
| `GET /chat/files/capabilities` | 兼容接口，查询已配置的直传能力 |
| `GET /chat/files?sessionId=...` | 查询会话附件及处理状态 |
| `POST /chat/files/{id}/retry?sessionId=...` | 重试失败、部分解析或向量化失败附件，不更改有效期 |
| `DELETE /chat/files/{id}?sessionId=...` | 立即使附件不可读并回收 |
| `GET /chat/files/{id}/source?sessionId=...&parentId=...` | 查询受鉴权的引用原文 |
| `GET /chat/files/{id}/original?sessionId=...` | 下载受鉴权的原始文件 |
| `POST /chat/stream` | 有附件时提供 `fileIds` 和策略返回的 `revision` 作为 `attachmentPolicyVersion`。过期版本返回 409 |

状态为 `STORED → QUEUED → PROCESSING → READY/PARTIAL/FAILED`。直传可直接使用 `STORED` 原件，文件列表只返回有效附件。
前端始终传 `fileIds`，空数组表示本轮为纯文字。显式启用旧模式后，旧客户端可继续使用原有 `qa`、`review` 请求。
SSE 的 `attachment` 事件包含 `sources`、`warnings`、`selectedSections`、`totalSections`、`fullCoverage`，不传全文。
原文片段在预览中按纯文本显示。

## 升级与验收

已部署附件和会话历史表的环境直接重建应用即可。初次部署运行 [迁移03](../scripts/migration-03-private-attachments.sql) 和会话历史相关迁移，详见 [迁移说明](../deploy/migrate.md)。
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

合成金标、固定预算分块对照、真实模型缓存回放与指标口径见[附件质量评估](attachment-evaluation.md)。

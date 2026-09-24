# 劳小司 · 智能法律助手（legal-assistant）

> 一个以**学习 Spring AI 技术栈**为核心的渐进式项目：从"能流式聊天"起步，沿着七个学习阶段演进为一个**劳动法专精、引用可溯源、可部署运营**的法律 AI 助手（劳小司）。法律业务是承载 AI 能力的场景外壳，每个阶段解决一类独立的工程问题。

## 学习进程（七个阶段）

### 阶段一 · 流式对话内核 —— 把"能聊天"做扎实

| 能力 | 关键实现 |
|---|---|
| SSE 流式聊天 + 停止生成 | `Flux<ServerSentEvent>` + `Sinks.Empty` + `takeUntilOther`（Reactor cancel 传播） |
| 会话记忆持久化 | 实现 Spring AI `ChatMemory` 接口；后演进为"原文窗口 + 早期摘要"两级上下文压缩 |
| 思考过程可见化 | reasoning_content 透传，前端 ThinkingPanel 折叠展示 |
| 流式与滚动体验 | 长回答滚动锚定、停止生成后的前端状态治理 |

### 阶段二 · 工具调用 —— 让模型会做事

| 能力 | 关键实现 |
|---|---|
| Function Calling | `@Tool` 注解：时间/计算器通用工具 + 法律计算器（经济补偿金/加班费/诉讼时效/诉讼费） |
| 联网搜索 | `webSearch` 工具直调联网子模型；`enable_search` 是厂商私有参数，Spring AI 不透传，手写 HTTP |
| 工具预算治理 | ToolBudget 多工具共享预算；路线差异化挂载（闲聊零工具，专业路线全量工具 + 调用上限） |

### 阶段三 · RAG 知识库 —— 让模型懂法律

| 能力 | 关键实现 |
|---|---|
| 检索增强起步 | 法条向量化 + Top-K 检索注入 Prompt |
| 知识库底账化 | `law_article` 唯一事实源 + MD5 指纹增量同步（新增/变更/删除三态） |
| 检索工具化（Agentic RAG） | `searchLaw` 由模型自主决定检索时机与次数，支持多跳追问 |
| 检索质量工程 | 两阶段宽进窄出 + BM25 混合检索 + Cross-Encoder 精排 + Small-to-Big 整条还原（详见下文管线） |
| 引用可信度 | 结构化 `citation` 事件（法名/条号/出处/相关度）；前端引用卡默认折叠、点击展开全文 |

### 阶段四 · 多模型路由 —— 成本与质量平衡

| 能力 | 关键实现 |
|---|---|
| 五路模型端点 | default / reasoning / cheap / legal / fallback，配置驱动（加模型只改 yml） |
| 三级级联路由 | 关键词硬规则（零成本）→ 语义向量快筛（1 次 embedding）→ LLM 分类兜底，能省则省 |
| 路线级差异化 | temperature / 思考强度 / 工具集 / 预算按路线独立配置 |

### 阶段五 · 安全与质量门 —— 守住底线

| 能力 | 关键实现 |
|---|---|
| 确定性安全层 | 纯规则引擎（零 LLM）：拒答拦截/紧急置顶/落空提示/免责声明——"不让 LLM 自己看守自己" |
| 强制检索打底 | Grounded ReAct：严肃路线进模型前代码层必检一次，堵住模型跳检幻觉 |
| 质量门审校 | 纯规则审校（法条引用/长度下限），不通过经 SSE `retry` 事件重试一轮，仍不通过放行 |
| 评估门禁 | 离线金标集 `mvn test` 一票否决 + 端到端链路评估（可选，手动触发） |
| 多轮指代消解 | PlannerAgent 用廉价模型改写"那家公司"类指代，单轮零额外调用，失败降级原问题 |

### 阶段六 · 产品化与用户体系 —— 从 Demo 到产品

| 能力 | 关键实现 |
|---|---|
| 用户体系 | JWT 无状态认证 + RBAC 五角色 + 短信验证码（注册/登录/重置） |
| 前端工程 | Vue3 + Element Plus + Pinia 公报问答台：流式渲染/思考面板/引用卡/事实栏 |
| 可运营性 | 分层配额（guest/user/lawyer/member/admin 五档速率 + 日配额）+ 管理端（向量同步等） |
| 门禁分级 | 律师模式等角色差异化的审校强度与提示策略 |
| 劳动法专精 | laborTools（城市劳动基准数据等）+ 条文章节属解析 |
| 多模态附件 | PDF/DOCX/图片解析（PDFBox/POI/VL 模型），token/噪声/caption/页数四坑防御限额 |
| 体验精修 | 移动端适配（局域网真机可测）、身份与头像体系 |

### 阶段七 · 存储演进与部署 —— 工程化收尾

| 能力 | 关键实现 |
|---|---|
| 存储层迁移 | MySQL → PostgreSQL：底账/会话记忆/向量同库（pgvector + HNSW），MyBatis 取代 JPA |
| 部署契约 | `deploy/`：Docker Compose + nginx + 环境变量模板 + 运维手册 + 数据迁移双路线 |

## 技术栈

| 层级 | 技术 | 说明 |
|---|---|---|
| 语言/框架 | JDK 17 + Spring Boot 3.5.11 + Spring AI 1.1.7 | Servlet 栈 + WebFlux SSE |
| Chat 模型 | DeepSeek（OpenAI 兼容协议） | 五路线级联路由，配置驱动 |
| Embedding | 阿里云百炼 DashScope | qwen3.7-text-embedding-flash（1024 维） |
| Rerank | 百炼 gte-rerank-v2 | Cross-Encoder 精排，失败降级融合序 |
| 知识库 | PostgreSQL 18 + pgvector（HNSW）+ MyBatis | 底账 `law_article` / 向量 `vector_store` / 会话记忆同库 |
| 缓存 | Redis + Redisson 3.50 | 分布式锁 / 配额计数 / 易失态缓存 |
| 认证 | Spring Security + JWT + 阿里云短信（dypnsapi） | 无状态认证，RBAC 五角色 |
| 前端 | Vue3 + Element Plus + Pinia + Vite | SSE 流式渲染 + 思考过程面板 + 引用卡片 |
| 部署 | Docker Compose + nginx | 配置全部环境变量化（`deploy/env.template`） |

## 项目结构

```
legal-assistant/
├── lab-chat-backend/      后端唯一模块（端口 8082，包名 com.law.backend）
│   ├── controller/          SSE 流式接口 + 认证/管理端点
│   ├── service/             ChatService（SSE/停止生成/强制检索打底/审校重试）+ 附件/配额等
│   ├── model/               多路线模型注册 + 三级级联路由
│   ├── rag/                 两阶段混合检索管线（pgvector 稠密 + pg_trgm BM25 + RRF + rerank）
│   ├── tool/                Function Calling 工具（法律计算器/联网搜索/检索，路线差异化预算）
│   ├── safety/              确定性安全层（纯规则引擎，零 LLM）
│   ├── auth/                JWT 无状态认证 + RBAC + 短信验证码
│   ├── memory/              会话记忆（原文窗口 + 早期摘要）
│   └── src/test/            评估门禁（安全/路由离线金标，mvn test 一票否决）
├── super-law-frontend/    前端（端口 5173，dev 代理到 8082）
├── db/
│   └── init.sql             数据库一键初始化（建表 + 468 条劳动法法条，幂等可重复执行）
├── scripts/               工具集
│   ├── pg-schema.sql        底账 schema 单一事实源（db/init.sql 由它内联生成）
│   ├── GenInitSql.java      从现役库 1:1 重新生成 db/init.sql（维护数据用）
│   └── RunInitSql.java      无 psql 环境的 init.sql 执行通道（JDBC 整文件提交）
├── labor-src/             劳动法官方源 HTML 溯源存档（不做运行时依赖）
├── deploy/                部署契约（docker-compose + env.template + migrate.md/runbook.md）
├── docker-compose.yml     本地开发基础设施（PG + pgvector + Redis，首次启动自动跑 db/init.sql）
└── pom.xml                父 POM（依赖版本集中治理）
```

## 快速开始

```bash
# 1. 启动基础设施（PG + pgvector + Redis；首次启动自动执行 db/init.sql 建表并导入 468 条法条）
docker compose up -d
# 本机已有 PG/Redis（如系统自带）时跳过本步，手动初始化：
#   createdb lab_legal_kb && psql -U postgres -d lab_legal_kb -f db/init.sql
#   无 psql 环境（Windows sandbox 等）：java -cp postgresql-42.7.4.jar scripts/RunInitSql.java

# 2. 注入密钥后启动后端（yml 不含默认密钥；完整变量清单见 deploy/env.template）
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx        # embedding + rerank + 备用多模态
export PG_PASSWORD=postgres REDIS_PASSWORD=local-dev-redis   # 与 docker-compose.yml 默认值一致
mvn spring-boot:run -pl lab-chat-backend

# 3. 前端（另开终端）
cd super-law-frontend && npm install && npm run dev
# 浏览器打开 http://localhost:5173

# 4. 首个管理员：注册账号后设 BOOTSTRAP_ADMIN=<用户名> 重启后端提权，
#    登录管理端「设置 → 向量库同步 → 立即同步」重建向量（一次性 embedding 费用）
```

调试备选：后端内置聊天页 `http://localhost:8082`；评估门禁 `mvn test -pl lab-chat-backend`（端到端链路评估 `-Deval.e2e=true` 手动触发，消耗模型额度）。

## 检索管线（五阶段）

```
问题（Planner 改写后）
  ① 稠密路粗召回   pgvector KNN，recallTopK=12，similarity-threshold=0.55 截断
  ② 稀疏路召回     pg_trgm GIN（补"第四十七条"类离散编号的精确匹配），topK=10
  ③ RRF 融合       score = Σ 1/(60+rank)，条文级去重
  ④ rerank 精排    gte-rerank-v2 Cross-Encoder 取 topK=5（失败降级融合序）
  ⑤ Small-to-Big   chunk 子块命中 → 按 articleId 还原整条全文注入
```

引用卡上的 `TOP 0.xx` 是 **rerank 相关性分**（越高越好），与 ① 阶段的余弦相似度阈值（0.55）不是同一量表。任一阶段失败均降级不阻断，全链失败退化为纯 LLM 对话。

## 关键设计

- **底账与索引分工**：PG `law_article` 是唯一事实源，`vector_store` 只是可随时重建的检索索引；MD5 指纹增量同步，管理端一键重嵌
- **多路模型配置驱动**：`legal.ai.model`（providers 端点 / routes 路线 / router 决策链 / examples 语义语料），加模型只改 yml
- **安全在架构层兜底**：拒答/紧急置顶/免责声明由规则引擎 100% 保证，不指望提示词软约束下模型自觉
- **质量门零 LLM**：审校（引用/长度下限）是纯规则，不通过走 SSE retry 重试一轮，仍不通过放行不无限回炉
- **成本意识贯穿**：路由三级级联能省则省；闲聊路线不挂检索工具（零成本）；摘要/改写用廉价路线
- **配置与密钥分离**：真实值只存环境变量（`deploy/env.template` 一一对应），仓库内只有空占位

## 踩坑备忘

- Spring Boot 3 的 `@RequestParam` 依赖编译期 `-parameters`，maven-compiler-plugin 必须锁 3.x 并显式开启，否则参数名丢失报 500
- BOM import（spring-boot-dependencies）只管依赖不管插件：surefire 不锁版本会退回 2.12.4，JUnit 5 测试静默不执行（Tests run: 0）
- `enable_search` 是 DashScope/DeepSeek 私有 body 参数，Spring AI 的 OpenAiChatOptions 不透传，需手写 HTTP（见 WebSearchTool）
- Spring AI 官方核心库无联网搜索工具，网上教程的 `spring-ai-tavily-search` 坐标是虚构的
- MyBatis 驼峰映射前缀是根级 `mybatis.*`，写在 `spring.mybatis.*` 不生效——曾导致登录读出的密码哈希恒为 null
- Windows 下 JDK 单文件源码模式按平台默认编码（GBK）解码 .java，生成工具里的中文一律放 UTF-8 资源文件（见 GenInitSql 注释）

## 部署

服务器部署、数据迁移双路线（pg_dump 全量 / init.sql 重建）、环境变量清单与运维手册：见 `deploy/runbook.md`、`deploy/migrate.md`、`deploy/env.template`。

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

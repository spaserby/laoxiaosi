# 附件 RAG 质量评估

这是独立、显式启用的合成实验。不会启动 Spring 应用、连接业务数据库或加载业务上传目录。
评估工具复用当前 `AttachmentChunker`、`AttachmentRetrieval`、`AttachmentParser`；不修改生产分块参数。

## 冻结数据与实验设计

- 金标：[attachment-quality-set.json](../lab-chat-backend/src/test/resources/golden/attachment-quality-set.json)。7份合成资料、24题；材料和标注由开发代理预先编写，未经过独立人工法律审核。
- 调参集：4个文档主题、16题，其中12题有答案、4题无答案。
- 保留集：场地使用、主补培训协议两个新主题，3份资料、8题，其中6题有答案、2题无答案。
- 每题标注固定原文证据句，不把随配置改变的 chunk ID 当金标。
- 固定共同上下文预算8000个估算聊天token、24个候选、topK=6、精排开启、向量阈值0.45、精排阈值0.05。
- 六组配置：基线1536/512/128；仅改变子块为256或768、父块为768或2304、硬切重叠为0。顺序为父块/子块/重叠，单位均为估算embedding token。
- CL100K乘系数：聊天1.5、embedding 1.25；不声称精确对应供应商tokenizer。
- 候选配置只按调参集选择：全必要证据题数降序、证据微平均召回降序、索引子块数升序、配置ID升序。选择后只对基线与候选运行保留集和回答验证。

## 两种运行方式

先准备JDK17、Maven和Python。默认测试只运行离线指标回归，完整评估默认跳过：

```bash
mvn -B -pl lab-chat-backend -am test
```

**首次真实实验**会消耗embedding、精排、OCR和回答模型额度。模型ID与实验版本固定在评估代码中，需确认所用端点支持它们；凭据只通过私有环境变量配置。默认使用已有应用的公开供应商端点：

```bash
# 环境中已配置 DASHSCOPE_API_KEY、DEEPSEEK_API_KEY；不要把实际值写入命令或文档。
python scripts/run_attachment_quality.py --live-models
```

也可通过已有SSH认证在服务器端使用容器中的模型配置，凭据不会传回本机。目标JSON必须保存在仓库外，仅含 `host`、`user`、`port` 元数据；容器名由操作者指定：

```bash
python scripts/run_attachment_quality.py --live-models \
  --target-json /private/location/target.json --container your-backend-container
```

该方式只在远端内存中执行模型客户端，不发布应用、不迁移数据库。SSH使用已知主机校验与非交互认证，不读取SSH私钥正文。
端点可通过 `ATTACHMENT_EVAL_EMBEDDING_URL`、`ATTACHMENT_EVAL_RERANK_URL`、`ATTACHMENT_EVAL_CHAT_URL` 覆盖；它们是模型供应商接口，不能指向不可信代理。

**无网络回放**需要先保存真实模型缓存。修改文档或模型后缺少缓存时失败，不伪造向量或把它当关键词实验继续：

```bash
python scripts/run_attachment_quality.py
python scripts/run_attachment_quality.py --report-only
```

默认产物位于被Git忽略的 `lab-chat-backend/target/attachment-quality/`，可用 `--directory` 指定私有目录。实验完成后应归档整个目录，避免 `mvn clean` 删除缓存。
缓存按模型与输入内容索引；生成提示词协议为 `answer-v1`。改变提示词、模型参数或调用协议时，使用新实验目录并升级协议版本，不混用旧缓存。

## 指标与输出

| 输出 | 含义 |
| --- | --- |
| `corpus.json` | 展开后的正文、固定原文证据偏移、题目和各配置索引规模 |
| `dev-results.json` / `holdout-results.json` | 逐题最终上下文、实际来源、证据覆盖与三次预热后本地回放耗时 |
| `selection.json` | 在查看保留集指标前确定的候选及选择规则 |
| `parsing-results.json` | 31页PDF、DOCX表格、清晰/模糊合成扫描图的独立解析结果 |
| `generation-results.json` | 保留集真实回答、引用标签、规则要点匹配和无答案行为 |
| `model-*.json` | 可恢复的真实模型结果缓存、网络耗时、供应商usage和脱敏错误类别 |
| `model-*.calls.jsonl` | 新运行按批次追加的原始回执，便于核对唯一输入与实际请求数 |
| `summary.json` / `report.md` | 汇总、模型用量、额外预检调用数、生产代码/数据/评估代码SHA256 |

证据召回以最终上下文中有实际文字且来源偏移覆盖原文证据句计分，重复块不会增加分子。
微平均为全部命中证据句数/全部必要证据句数；宏平均为逐题召回的平均值。
全必要证据成功率只在有答案题计算；无答案题单列，不按完美召回计分。
答案正则要点覆盖和引用来源证据覆盖都是确定性代理指标，不能命名为语义faithfulness或法律风险检出率。

## 范围与复现限制

本实验的向量是**真实模型输出**，查询使用内存穷举余弦相似度；不是模拟向量，也没有调用生产pgvector SQL。
精排使用真实服务的结果回放，遇到已记录的精排错误时保留应用的降级行为和失败计数。
三次本地回放计时不包含网络、数据库、HTTP会话和生成；网络耗时与实际usage另外列出，不把它们简单相加冒充线上P95。
两个扫描图使用真实VL转录缓存经过生产解析器，但不能代表中文复杂扫描件OCR准确率。
小型合成保留集只能提供有限证据，不能据此自动认定某组参数是生产最优。

## 独立的低预算诊断

主实验完成后可另建私有/忽略目录，复制同一轮的embedding与精排缓存，只在调参题上做2000 token预算诊断。
它不能混入8000 token主实验的配置选择，也不等同于候选在保留集上的验证：

```bash
mvn -B -pl lab-chat-backend -Dtest=AttachmentQualityEvalTest \
  -Dattachment.eval.phase=evaluate-dev -Dattachment.eval.budget=2000 \
  -Dattachment.eval.dir=your-private-stress-directory test
```

该目录须已包含 `model-embedding.json` 与 `model-rerank.json`；数据和模型协议必须与主实验一致。
如要导出图表，另安装可选绘图库 `matplotlib==3.10.7` 后运行 `scripts/plot_attachment_quality.py --help`。

可选的真实HTTP补充验收：

```bash
python scripts/verify_attachment_quality_http.py --base-url http://127.0.0.1:8082
```

该脚本确实在应用中上传合成资料、发起一次问答，遵守正常游客限额，最后删除合成会话并确认来源不可读。
它与无业务数据库的组件对照实验分开记录。只有SSE完成还不够，报告中的精排降级警告也必须如实披露。

指标设计参考 [Microsoft RAG evaluators](https://learn.microsoft.com/en-us/azure/foundry/concepts/evaluation-evaluators/rag-evaluators?view=foundry-classic)、[Ragas Context Recall](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/)、[Ragas Faithfulness](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/faithfulness/)。

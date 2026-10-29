# 扩充语料治理

## 2026-10-29 中文增量

当前扩展索引 170 篇、7278 片段，其中中文 98 篇、中医药中文标题 32 篇；台账 178 条，72 核心、98 背景、8 隔离。本轮新增 30 篇中文原文，2 核心、28 背景；4 篇 CC BY，26 篇明确 CC BY-NC，仅限非商业研究且不进入通用交付 ZIP。恢复作者提供的中文元数据、来源纠偏及独立中文检查见 `chinese-corpus-expansion.md` 与 `chinese-corpus-increment.json`。下面 2026-10-28 的规模与结果为历史记录，不代表当前统计。

扩充语料使用独立的 `EXPANDED` 版本，永远不覆盖冻结的 `BASELINE`（4 份文档、11 个切片）。`data/expanded-sources.json` 是资料台账，只有来源 URL、全文、许可状态和正文 SHA-256 均核验通过的记录才能进入正文索引。

2026-10-28 台账共 148 篇，分类为 70 篇 `core`、71 篇 `background`、7 篇 `excluded`。默认索引包含前两类，共 141 篇、6810 个片段；原文和隔离记录仍保留。与 `c2da130` 的 100 篇相比新增 48 条，其中 42 篇核心、2 篇背景、4 篇隔离。新增全部为英文开放全文，不计作新增中文语料。实际数量以校验工具和 `docs/corpus-increment.json` 为准，不以摘要、生成文本或伪造译文补足。

资料数量、主题相关性、全文访问和再分发许可是独立状态。每条记录有 `corpusRole`、`topicReview`、正文双主题命中及出处；分类依据见 `docs/corpus-topic-review.json`，具体纠偏见 `docs/corpus-role-decisions.json`。核心要求标题主题及正文同时支持中医药和心血管；单主题有用资料降为背景，明确无关或不足以建立目标范围的资料退出默认索引。自动筛查和来源辅助复核不是专家临床或法律认证，不推断疗效、剂量或临床证据等级。旧 `topicCounts/manualReviewRequired` 仅为元数据诊断，不能替代新分类。

增量采集先生成候选清单，再核验纳入，不整体覆盖现有台账。工具对 Europe PMC 搜索使用游标分页、30 秒超时、请求间隔、失败记录及续采状态；`--pages` 限制每个查询的分页数，`--limit` 限制本批核心纳入目标。XML 结构化解析保留章节正文，不以摘要冒充全文。缺全文、许可不明、主题不符以及 PMCID、DOI、来源 URL 或正文哈希重复均不纳入；标题近似重复进入单独复核。

```text
python tools/collect_expanded_sources.py --discover --pages 2
python tools/collect_expanded_sources.py --ingest --limit 40
python tools/review_expanded_sources.py
python tools/report_corpus_increment.py
```

候选与断点在 `runtime/corpus-collection/state.json`；本轮可审计快照为 `docs/corpus-candidates.json`。337 条候选中仍有 191 条待处理，不宣称穷尽中文来源。对新增资料分别记录 XML 许可原文、链接、访问时间、正文及 XML SHA-256，优先明确 CC BY、CC0 或公有领域授权。全文可访问不等于可再分发。

构建命令：

```text
python tools/validate_expanded_sources.py
python tools/build_expanded_corpus.py
python tools/review_expanded_sources.py --check
python tools/build_expanded_corpus.py --check
```

切片使用 620 字上限，生成 `backend/src/main/resources/data/expanded/knowledge-documents.json` 和 `knowledge-base.json`。历史评测服务固定使用 `BASELINE`，扩充语料上的查询结果不属于 Experiment A。

新分类仅扩展台账，旧记录和问答 API 兼容；旧记录缺分类时沿用既有纳入行为，显式 `excluded` 不进索引。仓库中原有 72 条受限或需许可复核资料不删除，但不放进对外 ZIP。交付工具只分发明确 `permissive-verified` 且未隔离的正文、台账和索引子集，清除受限来源的 JSON 引用摘录；隔离清单保留编号和原因。使用 `python tools/package_delivery.py` 后运行 `python tools/verify_delivery.py runtime/delivery/qihuang-rag.zip` 核验实际包。包内索引数量不同于完整工作区，不能套用完整库的评测成绩。

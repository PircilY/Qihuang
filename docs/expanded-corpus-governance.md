# 扩充语料治理

扩充语料使用独立的 `EXPANDED` 版本，永远不覆盖冻结的 `BASELINE`（4 份文档、11 个切片）。`data/expanded-sources.json` 是资料台账，只有来源 URL、全文、许可状态和正文 SHA-256 均核验通过的记录才能进入正文索引。

当前已核验资料以 Europe PMC/PMC 开放全文为主。台账当前真实记录数量、语言分布和缺口以 `python tools/validate_expanded_sources.py` 输出为准；中文资料不足或总量未达到 100 篇时，系统不得将其标记为达标，也不得用题录、摘要、搜索摘要或生成文本补足。

资料数量核验与主题相关性核验是两个独立状态。校验器会根据标题和摘要/描述输出 `topicCounts`，并将不是“中医药+心血管”双主题的记录标记为 `manualReviewRequired`。当前 100 条记录的全文、来源、许可和校验值已通过自动检查，但主题分类仍需人工复核；不得仅凭总数、语言分布或全文存在就宣称全部资料都是高相关中医药心血管证据。

构建命令：

```text
python tools/validate_expanded_sources.py
python tools/build_expanded_corpus.py
```

切片使用 620 字上限，生成 `backend/src/main/resources/data/expanded/knowledge-documents.json` 和 `knowledge-base.json`。历史评测服务固定使用 `BASELINE`，扩充语料上的查询结果不属于 Experiment A。

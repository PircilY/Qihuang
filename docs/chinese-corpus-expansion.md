# 中文与中医药语料扩充

日期：2026-10-29。以 `21a2457` 为扩充前快照，独立补充来源自带的中文全文、标题及摘要，不生成译文，不把摘要计作全文。所有资料为研究检索用途，不支持个人处方、剂量或停药建议。

## 实际增量

| 项目 | 扩充前 | 本轮完成 |
|---|---:|---:|
| 扩展库索引文档 | 141 | 170 |
| 扩展库片段 | 6810 | 7278 |
| 中文索引文档 | 69 | 98 |
| 中文片段 | 1548 | 2016 |
| 来源自带中文标题 | 1 | 98 |
| 来源自带中文摘要 | 0 | 95 |
| 中文标题支持中医药主题 | 2 | 32 |
| 中文核心 / 背景 | 2 / 67 | 4 / 94 |

新增 30 篇中文原文，2 篇双主题核心、28 篇中医药背景。原台账 70 条中文记录恢复了保留 XML 中作者提供的标题及摘要；其中 1 篇原 API 标题与保留原文不符的资料退出默认索引，所以中文文档净增 29 篇，不是 30 篇。台账现共 178 条：72 核心、98 背景、8 隔离。统一入口合并未变的 BASELINE 后为 174 文档、7289 片段；BASELINE 仍为 4 文档、11 片段。

核心增量为三参通脉合剂心肌氧化损伤、理气活血方剂动脉粥样硬化模型研究。背景增量覆盖证型、方剂、针灸、中药成分与研究方法，并包括糖尿病、代谢综合征等非心血管主题。背景可供资料检索，但不能计作心血管疗效证据。黄芪桂枝五物汤的新增来源研究的是其他疾病及神经毒性，不能冒充黄芪心衰临床证据。中文黄芪心衰、丹参冠心病等重点方向仍有缺口。

## 来源、许可与隔离

本批 245 候选：30 纳入、214 拒绝、1 失败。失败 `PMC11914004` 为全文接口 HTTP 500，未生成正文或填充替代文本。分页、重试、请求间隔、断点及历次主题拒绝记录保留在本地采集状态；可发布的候选、许可原文、出处、访问时间和正文 / XML SHA-256 快照见 `chinese-corpus-increment.json`。

新增许可为 4 篇 CC BY、26 篇 CC BY-NC。工具默认仍只纳入明确宽松授权；BY-NC 需要显式 `--allow-noncommercial`，仅用于非商业研究，页面正文详情保留授权链接与限制。资料遵循各自来源授权，不因项目代码许可而变更。对外通用 ZIP 不包含这 26 篇受限原文、索引或 JSON 引用摘录。开放访问与再分发分别核验；未明授权、ND、SA 不按 BY-NC 纳入。

新增全文均解析保留 JATS XML 的正文和章节，并按 PMCID、DOI、来源 URL、正文哈希去重。近似标题进入单独复核。资料角色来自标题双主题及正文筛查和来源纠偏，不是专家临床质量审读；证据等级保持“未评级”。

两项纠偏保留了原文和记录：

- `ZH-PMC7342940`：原 API 标题为急性冠脉综合征，保留 XML 实为血液系统恶性肿瘤的细胞外囊泡研究。以原文为准隔离，不删除原件。
- `ZH-PMC11914022`：`paraventricular` 不等于心脏 `ventricular`，改为针灸背景；正文内的心血管讨论仍可追溯。只更正新检查的角色出处，不改问题、目标文档及支持原文。

许可解释可核对 PMC Copyright Notice 与 Creative Commons BY-NC 4.0 官方页面。来源开放访问本身不构成再分发授权，BY-NC 不允许商业使用。

## 可复现流程

```text
python tools/collect_expanded_sources.py --discover --language zh --pages 2 --topics tcm-syndromes tcm-materials tcm-formulas --state runtime/corpus-collection/chinese-20261029.json
python tools/collect_expanded_sources.py --ingest --language zh --limit 30 --include-background --allow-noncommercial --state runtime/corpus-collection/chinese-20261029.json
python tools/prepare_chinese_corpus.py --restore-metadata
python tools/review_expanded_sources.py
python tools/build_expanded_corpus.py
python tools/prepare_chinese_corpus.py --report --check
python tools/check_terminology_consistency.py
python tools/check_chinese_retrieval.py
```

台账增量追加，重跑不覆盖不同原文，不自动重写已有检查标签。采集不足或失败按实际记录，不以泛高血压命中补足中医药核心数量。

## 验证口径

独立固定 12 个中文正向资料问题和 6 个负向问题，每个正向预先指定目标文档及两个原文 anchors。有效 Top3 必须命中目标文档且支持两个 anchors，引用 quote 必须逐字等于索引正文。`chinese-retrieval-results.json` 分别保存扩充前、旧文档元数据与来源纠偏后、最终扩充后结果。

中间场景名称 `metadataOnly` 为兼容保留，同时包含旧资料的来源身份与主题复核，不是纯元数据因果实验。三个场景使用同一最终引擎、hash/BM25、bridge 关闭和 0.9 安全阈值；10 个预先提名的新文档在旧库不存在，因此结果不能外推为通用问答准确率。原 24+6 检查、七题探测和原 50 题标签保留。

冷启动全量测试曾在 180 秒内未达到 READY；诊断复现逐片嵌入包装与磁盘缓存开销。修复仅让本地确定性 hash 直接调用相同计算算法，不写逐片缓存；语义模型缓存不变，新增数值完全一致及不产生缓存文件的回归。未提高 READY 等待或降低安全阈值。其后一次运行因旧测试仍要求 hash 写缓存而失败，已改用模拟语义后端验证缓存。两次失败的 JUnit 均保留，不用失败运行冒充验收。

## 检索结果

| 同一最终引擎的场景 | 文档 / 片段 | 有效 Top3 | 负向拒答 | 引用逐字回链 / 已引响应 | 中位耗时 ms |
|---|---:|---:|---:|---:|---:|
| 扩充前 | 141 / 6810 | 2 / 12 | 6 / 6 | 11 / 11 | 1226.4 |
| 旧资料元数据及来源纠偏 | 140 / 6799 | 2 / 12 | 6 / 6 | 11 / 11 | 1257.4 |
| 扩充后 | 170 / 7278 | 10 / 12 | 6 / 6 | 12 / 12 | 1230.0 |

标签 SHA-256：`d7203c3b06b476d63fbaf21fcb9658c04a692295ac580ba1273f2f75471f55bd`。完整结果见 `chinese-retrieval-results.json`。不改变原七题或 24+6 检查的标签和判定规则。

`ZH-P04` 理气活血方剂与 `ZH-P11` 养阴益气活血方均返回正确文档，但 Top3 片段未同时含两项固定 anchors，仍判失败。回链只检查引用确实来自该索引正文，不等于目标命中、回答主张覆盖或临床正确性。扩充前也有 11 个带引用回答，却仅 2 个严格命中，不能用引用数量粉饰相关性。

## 本地测试

最终全量 pytest 为 183 passed，623.81 秒，1 条既有 Starlette/httpx 弃用警告；包含 Java/Python V3 检索契约。JUnit：`runtime/chinese-pytest-final-pass-20261029.xml`。Maven 离线完整 package 为 161 测试、0 失败、0 错误、5 项既有跳过，BUILD SUCCESS；报告副本：`runtime/chinese-maven-20261029`。

BASELINE 4/11、EXPANDED 170/7278 重建、178 条许可/原文哈希/角色、654 条术语、导入 fixture 及知识报告一致性检查通过。术语自动题名检查为 14 已验证、634 无证据、6 冲突；冲突保留并由现有检索逻辑降级，不伪造验证。当前本机 `pip check` 仍报告 OpenTelemetry API 1.45.1 与 SDK/semantic-conventions 所需 1.43.0 不一致，属于已有环境局限，未无关升级；不能把本机全量通过当作干净依赖环境认证。

## 服务与浏览器验收

隔离 HTTP 验证问答及引用、版本比较、未配置模型错误、导入、故障回退、重启补同步和安全文件哈希，全部通过；证据为 `runtime/chinese-http-e2e.json`。独立端口 8082 导入测试首次同端口重启遇到短暂端口释放延迟；保留失败记录，补充最长 15 秒的释放轮询后完整重跑通过，未绕过启动器占用检查。最终日志为 `runtime/chinese-import-smoke.txt`，测试进程已退出。

只读浏览器验收使用真实 Java/Python 服务，不修改演示数据、不请求模型生成：

| 脚本 | 结果 |
|---|---|
| `smoke-unified.cjs` | 174 文档 / 7289 片段，单一知识库入口、两套原文查找、问答、引用回链、四版本比较、急症拒答通过 |
| `smoke-chinese.cjs` | 98 篇中文题名及摘要与台账一致，三参通脉来源和非商业限制可见，错误来源隔离，BASELINE 4/11 不变 |
| `smoke-overview.cjs` | 统一及历史范围的年份和登记统计、桌面底部对齐、内容裁切、缺失/无效/空元数据通过 |

统一及中文页面核验 1440/1159/800/390/320 宽度，概览核验 1440/1354/800/390/320；没有页面横向溢出、筛选重叠或 JavaScript 错误。已查看桌面、手机截图和工作台视口截图，证据保留在 `.tmp/chinese-release-unified`、`.tmp/chinese-release-source`、`.tmp/chinese-release-overview`，不进入交付包。会修改演示数据的 `smoke-ui.cjs` 未用于此次服务。

## 保留检查的复跑

原 24 正向 + 6 负向标签未改：有效 Top3 为 16/24、负向拒答 6/6、引用逐字回链 23/23 个已引响应，中位耗时 1412.5 ms。另存 `chinese-expansion-audit-relevance.json`，不覆盖原审查对比。

原七题探测按原词面规则为 4/7，3 项失败，报告为 `chinese-expansion-service-probe.md/.json`。未通过项为丹参冠心病中文问题、气虚血瘀证心衰问题、Danshen 英文问题。中医药高血压问题只返回泛高血压资料却符合原宽松词面规则，不能视为中医药相关性通过；新独立检查没有沿用这一规则。历史 3/7 报告与标签保留。

七题工具复用外部服务，报告将桥接状态标为未独立核验；本轮启动时明确设置 `RAG_QUERY_BRIDGE=false`、`RAG_MINIMUM_EVIDENCE_SCORE=0.9`、hash 后端及禁止模型下载，启动配置与服务记录另存本地。未修改原探测工具或降低安全阈值。

## 提交与交付边界

本轮分组提交为 `c122d25`（hash 索引性能与数值一致回归）、`0b9afd9`（中文采集及许可治理）、`d8b7378`（30 篇原文、元数据和索引）、`1953991`（固定中文检查及浏览器脚本）、`5b999c4`（独立重启测试的端口释放等待）。验证报告随后单独提交。

合并前刷新 `origin`，本地及远端 `main` 为 `8e8527e`，没有新增远端提交；只正常快进合并、正常推送，不改写历史或自动合并其他 PR。最终提交、合并、CI、ZIP 哈希及服务启动信息以 `runtime/delivery/chinese-release-record.json` 为准，远端 CI 不由本地测试替代。

完整工作区含非商业受限资料，不等于通用 ZIP。包内只保留明确许可宽松且纳入索引的全文与片段，并去除 JSON 内受限引文、正文复核摘录和支持原文；本地台账、原文与 Git 历史不删除。原始 BASELINE、原 50 题、既有独立 24+6 标签、共享安全文件及冻结边界相对 `8e8527e` 不变，未运行留出集。通用 ZIP 的检索表现需以实际子集另测，不套用完整库的 10/12 成绩。

实际 ZIP 为 `runtime/delivery/qihuang-rag-chinese.zip`：515 个源码成员，扩充许可子集 74 文档 / 4396 片段，104 条台账隔离；另含 BASELINE 4/11，统一视图为 78/4407。清单哈希、路径白名单、凭据模式扫描、全文和索引成员校验通过；41 个 JSON 文件逐层核验，79 处受限引文或支持原文已移除。证据为 `runtime/delivery/chinese-verification.json` 与 `chinese-json-isolation.json`，最终 ZIP SHA-256 写入运行发布记录，不嵌入源码形成自引用。

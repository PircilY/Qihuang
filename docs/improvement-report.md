# 岐黄脉证 RAG 研究原型改进报告

完成日期：2026-10-23。范围为A–F六阶段，原50题只作调参/回归集。冻结留出集未读取、未运行、未修改；未改变50题标签、safety-rules.json或既有安全断言。结果是研究原型验收，不能解释为诊疗或事实正确率认证。

## 各阶段与独立提交

|阶段|提交|完成内容|验收|
|---|---|---|---|
|A|e9d315a|运行时修订号、远程多余文档reload/整批补发、并发409合并、导入上限/限流、追加日志与原子快照、代理转发头|Java/Python完整回归通过；Python65项|
|B|7497c93|保留词频、全库IDF/平均长度/倒排缓存、SHA256 hash向量缓存、归一化weighted RRF、比例metadata、只用50题网格标定、引用/覆盖边界|Java/Python完整回归通过；Python68项；契约拒答一致100%、top1文档≥.90、平均Jaccard≥.75的原门槛保留|
|C|a8290cc|默认hash、显式可选SentenceTransformer、本地模型/缓存、按corpus/backend/model缓存、后台warmup、BUILDING健康与503/本地提示|Java/Python完整回归通过；Python73项；默认50题指标与B一致|
|D|cd3ec1c|CSV/JSONL/TXT导入、校验/过滤原因、Java620字符分块、章节页码、结构化sidecar、可选脱敏、去重候选、确定性审计与CI检查|Java/Python完整回归通过；Python79项；fixture/check/审计可复现|
|E|8aace90|显式开启A/B/C生成实验、同模型/温度/问题、完整失败记录、规则代理指标、bootstrap、独立JUDGE配置、CSV/JSONL导出|Java/Python完整回归通过；Python81项；可编程mock与默认关闭/同源路由测试；126行实际离线导出|
|F|本报告所在最终提交|非root/HEALTHCHECK容器配置、固定依赖/锁、前端运行上下文/并排证据、corpus对比修复、启动/关闭/HTTP/UI冒烟、Python旧LLM路径移除、白名单交付|完整回归与原50题复测、真实HTTP和浏览器已执行；Docker引擎不可用见下|

最终Java完整运行143 tests、0 failures、0 errors、3 skipped（冻结留出集及未启用的参数网格；本次启用原50题/生成报告）。Python94 passed；唯一警告是已装Starlette对TestClient使用httpx的弃用提示，不影响断言。未删测试或放宽门槛。

关键新增验收覆盖：GenerationExperimentServiceTest（可编程引用/无引用/失败/独立judge/参数一致/bootstrap/编号与超长引用）、GenerationExperimentControllerTest/EnabledTest（默认关闭与同源）、CompareCorpusTest（EXPANDED全版本与非法corpus）、test_import_pipeline.py、test_generation_export.py、test_delivery.py，以及A–C的同步、索引与安全边界回归。解析bug、旧配置字段移除及旧UI结果数量修正的理由均写在decisions.md。

## 原50题前后指标

同一42个可回答问题计算检索指标，全部50题计算拒答准确率，K=4。BASELINE、本地引擎，前后显式配置，原标签不变。

|版本/时期|Hit@1|Hit@3|Hit@K|MRR|Recall@K|拒答准确率|TR/FR/FA/TA|
|---|---:|---:|---:|---:|---:|---:|---|
|V0前/后|0|0|0|0|0|.88|2/0/6/42|
|V1前|.762|.952|1.000|.865|1.000|.94|5/0/3/42|
|V1后|.667|.952|.952|.790|.940|.94|5/0/3/42|
|V2前|.762|.952|1.000|.861|1.000|.94|5/0/3/42|
|V2后|.762|.976|.976|.857|.964|.94|5/0/3/42|
|V3前|.667|.881|.976|.782|.976|1.00|8/0/0/42|
|V3后|.762|.976|.976|.857|.964|1.00|8/0/0/42|

V3 Hit@1/3各增加.095、MRR增加.075，拒答没有退步。但V3 Recall下降.012，V1 Hit@1/MRR/Recall下降，V2 Hit@K/Recall略降；不能把所有版本描述为全面提高。默认metadataWeight=0、threshold=.9，按30组候选先保拒答准确率，再最大化Hit@1/MRR；全网格与84个排序变化组合见retrieval-quality.md。最终复测关键指标与B/C逐字段相同。

## 查询性能

同机Java本地V3、预热1次/20样本。并行测试负载不同，以下不代表SLA。

|语料（片段数）|改前中位/p95 ms|B阶段改后中位/p95 ms|F最终复测中位/p95 ms|
|---|---|---|---|
|BASELINE（11）|4.2288 / 13.4225|2.5887 / 7.4633|0.9810 / 1.3192|
|EXPANDED（4036）|4730.2007 / 8287.5688|66.1940 / 101.4934|106.6851 / 166.7559|

缓存分词、全库统计和向量省去逐问全库重算；向量仍线性扫描，EXPANDED首次缓存构建仍较慢。最终EXPANDED较B测量慢，保留两个实测值和并行负载局限。完整协议见performance.md。

## 语料与生成实验

BASELINE4文档/11片段、均长147.91；EXPANDED100文档/4036片段、均长612.29，年度/来源/证据标签分布见knowledge-base.md。审计不读取评测集。导入夹具显式标注[FIXTURE]/非医学，仅位于tools/fixtures或测试代码，禁止输出正式库。脱敏为启发式，不承诺识别所有姓名/地址；近似重复只报告，不自动删医学来源。

真实生成模型未配置：42题×A/B/C共126行，A/B共84行MODEL_NOT_CONFIGURED显式失败，C42行真实抽取式成功。C逐句引用支持率.7710（95% CI [.7520,.7861]）、无引用句比例.2290、引用有效性1、二元组覆盖.7745（CI [.7452,.7999]）、拒答/执行失败率0。标题等结构句也计入，分母与旧回答级引用指标不同。规则支持/二元组重合只作自动代理，不能证明医学事实正确；mock结果只是程序验收。完整定义、所有模式区间、提示与复现见generation-experiment.md。

## 演示与交付验收

- 实际HTTP自动冒烟通过：远程V3提问、同语料V0–V3对比、模型未配置、已有署名原文导入后检索、远程关闭明确local回退、Python重启自动补同步及安全SHA256一致。临时独立端口/导入库，进程全部结束。
- 浏览器Playwright/本机Chrome实际通过：五视图、过滤器、来源链接、远程问答、并排命中证据、急症拒答、请求/评估失败后重试；1440/1280/390/320宽度无页面横向溢出或JavaScript错误。UI还断言本地回退提示（真实HTTP形状的mock分支）与EXPANDED语料切换请求，真实远程中断/恢复由HTTP冒烟验证。200条评测结果与默认V3筛选50条分别断言。截图位于忽略目录.tmp/ui-final。
- 启动脚本实际启动双服务且等待READY，停止脚本核对PID/UTC创建时间后关闭自己的Java/Python。脚本不安装系统组件、不自动下载模型。
- docker compose config --quiet通过；实际docker compose build失败于本机Docker Engine管道缺失。因此镜像build/up、镜像内非root权限/HEALTHCHECK与哈希尚未验证。复核命令见deployment-validation.md，作为可选有条件验证，未伪报通过。
- requirements直接版本固定，运行锁68项，无已装依赖版本约束冲突；验证组合写在README。全新Linux/Python3.12锁安装未在本机执行，CI配置包含安装和HTTP冒烟但尚未在远程运行。
- 打包仅Git跟踪源码，路径过滤在读取前完成；依赖、模型、目标产物、日志、缓存、运行时库、冻结文件、无关用户docx不纳入。Maven jar和Docker上下文也排除冻结文件，安全规则保留。最终白名单492个文件，约7.37MB（压缩包字节数以打包输出为准）；逐文件校验通过，未发现私钥或长API token形状，原50题标签及安全规则与A阶段逐字节一致。交付manifest记录每文件SHA256，源代码可在依赖预备的环境离线测试。

## 已知局限与可选后续增强

## G–K：EXPANDED 中文检索与评测

- G：共享名称词典 654 条（药材574、疾病30、证候12、方剂12、检查14、药物类别12）；共享停用词策略、低权重查询扩展和高频主题过滤。默认查询桥接关闭，避免改变既有 BASELINE 默认路径。
- H：EXPANDED 重建为100篇/4036片段，片段级词典命中 herbs 342（8.47%）、formulas 111（2.75%）、syndromes 41（1.02%）；文档标题级命中 herbs 0%、formulas 1%、syndromes 0%。自动名称匹配可能漏标/误标，不是人工标注。
- I：自监督代理集固定种子42、2522个查询、K=4、bootstrap 1000次；语言分组为标题同语言398/跨语言0、词典目标术语同/跨语言736/812、中文原文独立目标576。已通过 Python 实际 `LlamaIndexEngine.query` 分别运行 V0–V3，使用 hash，before 为提交 `c9652d3` 的 G/H 前快照且桥接关闭，after 为重建语料且桥接开启；按类别、版本、阶段的 Hit@1/3/K、MRR 和区间见 `expanded-evaluation.md` 与机器结果 JSON。结果分组含随机和标题基线；词典目标组明确是循环风险，不能作为检索能力证明。
- J：默认仍为 hash。当前未安装 sentence-transformers、未加载 BAAI/bge-m3，真实多语言嵌入比较待验证；没有估算指标。
- K：空远程响应现按可回退故障处理；Hybrid RRF 保持排名融合并报告独立通道分数。`expanded-raw` 未引用的80个文件已先列入 `expanded-raw-orphans.md` 再删除。

BASELINE 原50题研究报告本轮已通过 `-DresearchReport=true -DrunHoldoutEvaluation=false` 重测（50题、42题计检索，K=4）。与 F 报告记录相比，V0–V3 的 Hit@1/3/K、MRR、拒答准确率逐项一致（V0 0/0/0/0、.88；V1 .667/.952/.952/.790、.94；V2 .762/.976/.976/.861、.94；V3 .762/.976/.976/.857、1.00），因此未观察到 BASELINE 指标下降。安全规则共享回归 6 项通过，规则文件与 holdout 标签均未修改；本轮未运行 holdout。Docker/真实模型网络运行仍按环境条件标记待验证。

1. Docker可用环境验证：`docker compose build`、`docker compose up -d --wait`、`docker compose exec rag-service python -c "from app.safety_rules import RULES_SHA256; print(RULES_SHA256)"`，对照`/actuator/health`与本地安全文件SHA256；命令已文档化，当前只缺可用引擎。
2. 本地语义模型与真实生成/judge均不可用：安装独立requirements-embedding.txt、显式本地权重和EMBEDDING_BACKEND后运行`python tools/compare_embeddings.py --help`；模型实验显式开启GENERATION_EXPERIMENT_ENABLED与MODEL_*、可选独立JUDGE_*后用`python tools/generation_experiment.py --limit 42 --seed 42 --temperature 0`。本次没有下载或估造效果。
3. 原50题为标定集，指标存在调参偏差；冻结留出集本次完全隔离。自动引用规则对复杂否定/多句陈述有限；无人工事实核查结果。不要把Hit/MRR、置信分或规则支持率用于临床决策。
4. 当前服务为单进程研究用途；更大语料可选ANN、跨实例持久化/统一限流、脱敏审阅与来源许可复核。hash语义能力有限，模型切换不能替代研究设计。

## 复现入口

```powershell
mvn -o -f backend/pom.xml -DrunHoldoutEvaluation=false test
python -m pytest rag-service/tests -q
python tools/import_knowledge.py --audit --report docs/knowledge-base.md --check
python tools/import_knowledge.py tools/fixtures/import.jsonl --output tools/fixtures/generated --redact --check
mvn -o -f backend/pom.xml -DresearchReport=true -DresearchOutput=../.tmp/research-final.json -DrunHoldoutEvaluation=false test
python tools/smoke_e2e.py
python tools/package_delivery.py
```

PowerShell运行Maven时建议把每个-D参数用单引号包裹；所有可选报告开关均不会打开冻结评测。原始测量、临时服务日志与完整生成结果在忽略的.tmp/runtime；可用命令重现，正式交付包保留报告、测试和夹具。

## 复核与加固（L–O）

- L 拆分为标题、词典目标术语、中文原文目标术语三组并报告同/跨语言、随机/标题基线、V0–V3、G 前后及 bootstrap 区间。标题和中文原文组是较可信的检索代理证据，仍非人工相关性评审；词典目标组明确标作循环风险，只说明扩展按设计工作。
- M 新增 `tools/probe_expanded.py`，固定七个中英文问题，在临时端口启动 Java/Python 服务及隔离导入库。全库字符串扫描给出覆盖片段数；有覆盖而前三条没命中将失败。本机报告保留实际未命中回归；`delivery-c9652d3` 对照因标签缺失只用其提交 `c9652d3` 的快照进行评测对照，端到端旧服务未重建。
- N 等待 Python `indexState=READY` 的上限由 `RAG_TEST_INDEX_READY_TIMEOUT_SECONDS` 控制，默认180秒，超时含健康状态诊断，不改变断言。Java 5 个跳过项的条件与原因、Python无skip列表见 `docs/verification.md`。在可写仓库临时目录连续运行 pytest 三轮，均为107 passed；Maven 三轮也均通过153项、0失败、0错误、5项既有条件跳过。
- O 词典自动核验：654条中1已验证、652无证据、1冲突（仅2个双语标题）；冲突条目默认不参与中英扩展，可由配置开关关闭降级。固定种子42抽取200个自动标记片段，低置信比例 herbs 0/37、formulas 0/5、syndromes 0/3、disease 8/822（0.97%）。低置信名称写入 `metadataConfidence` 并不参与检索主题特征和元数据加分。字符串规则是自动一致性代理，不等于人工核对。
- 未验证：Docker 引擎不可用、BGE-M3 及真实生成模型/judge 未下载/配置。相应复现：`docker compose build` 与 `docker compose up -d --wait`；`python tools/compare_embeddings.py --help` 后显式配置本地模型；配置 generator 与独立 judge 后运行 `python tools/generation_experiment.py --limit 42 --seed 42 --temperature 0`。无模型时结果保持待验证，不估算。

```powershell
python tools/build_expanded_eval.py --service-eval
python tools/probe_expanded.py
python tools/check_terminology_consistency.py
python tools/audit_terminology_metadata.py
python -m pytest rag-service/tests
mvn -f backend/pom.xml test
```
